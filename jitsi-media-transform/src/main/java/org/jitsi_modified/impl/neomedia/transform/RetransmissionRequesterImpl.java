/*
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi_modified.impl.neomedia.transform;

import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Creates classes to handle both the detection of loss and the creation
 * and sending of nack packets, and a scheduler to allow for nacks to be
 * re-transmitted at a set interval
 *
 * @author bbaldino
 */
public class RetransmissionRequesterImpl
        extends SinglePacketTransformerAdapter
        implements TransformEngine, RetransmissionRequester
{
    /**
     * The <tt>Logger</tt> used by the <tt>RetransmissionRequesterDelegate</tt> class
     * and its instances to print debug information.
     */
//    private static final Logger logger
//            = Logger.getLogger(RetransmissionRequesterImpl.class);

    /**
     * Whether this {@link RetransmissionRequester} is enabled or not.
     */
    private boolean enabled = true;

    /**
     * Whether this <tt>PacketTransformer</tt> has been closed.
     */
    private boolean closed = false;

    /**
     * The delegate for this {@link RetransmissionRequesterImpl} which handles
     * the main logic for determining when to send nacks
     */
    private final RetransmissionRequesterDelegate retransmissionRequesterDelegate;

    public Map<Byte, MediaFormat> payloadTypeFormats = new ConcurrentHashMap<>();

    /**
     * The {@link MediaStream} that this instance belongs to.
     */
//    private final MediaStream stream;

    /**
     * Create a single executor to service the nack processing for all the
     * {@link RetransmissionRequesterImpl} instances
     */
    private static RecurringRunnableExecutor recurringRunnableExecutor
            = new RecurringRunnableExecutor(
            RetransmissionRequesterImpl.class.getSimpleName());

    public RetransmissionRequesterImpl(/*MediaStream stream,*/ Consumer<RawPacket> rtcpSender)
    {
//        this.stream = stream;
        retransmissionRequesterDelegate = new RetransmissionRequesterDelegate(/*stream,*/ new TimeProvider());
        retransmissionRequesterDelegate.rtcpSender = rtcpSender;
        recurringRunnableExecutor.registerRecurringRunnable(retransmissionRequesterDelegate);
        retransmissionRequesterDelegate.setWorkReadyCallback(
                recurringRunnableExecutor::startOrNotifyThread);
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link SinglePacketTransformer#reverseTransform(RawPacket)}.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        if (enabled && !closed)
        {
            Long ssrc;
            int seq;

            MediaFormat format = payloadTypeFormats.get(pkt.getPayloadType());
            if (format == null)
            {
                ssrc = null;
                seq = -1;

//                logger.warn("format_not_found" +
//                        ",stream_hash=" + stream.hashCode());
            }
//            else if (Constants.RTX.equalsIgnoreCase(format.getEncoding()))
//            {
//                //TODO: BRIAN re-add this once we get RTX.
                    // --> Actually we shouldn't need this block anymore since we strip
                    // RTX before this
//                MediaStreamTrackReceiver receiver
//                        = stream.getMediaStreamTrackReceiver();
//
//                RTPEncodingDesc encoding = receiver.findRTPEncodingDesc(pkt);
//
//                if (encoding != null)
//                {
//                    ssrc = encoding.getPrimarySSRC();
//                    seq = pkt.getOriginalSequenceNumber();
//                }
//                else
//                {
//                    ssrc = null;
//                    seq = -1;
//
////                    logger.warn("encoding_not_found" +
////                            ",stream_hash=" + stream.hashCode());
//                }
//            }
            else
            {
                ssrc = pkt.getSSRCAsLong();
                seq = pkt.getSequenceNumber();
            }


            if (ssrc != null)
            {
                retransmissionRequesterDelegate.packetReceived(ssrc, seq);
            }
        }
        return pkt;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        closed = true;
        recurringRunnableExecutor.deRegisterRecurringRunnable(retransmissionRequesterDelegate);
    }

    // TransformEngine methods
    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    // RetransmissionRequester methods
    /**
     * {@inheritDoc}
     */
    @Override
    public void enable(boolean enable)
    {
        this.enabled = enable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSenderSsrc(long ssrc)
    {
        this.retransmissionRequesterDelegate.setSenderSsrc(ssrc);
    }
}
