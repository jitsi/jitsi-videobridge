/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.impl.neomedia.transform.delay.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.*;

import java.util.*;

import static org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer.isKeyFrame;

/**
 * Implements a <tt>TransformEngine</tt> for a specific <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
 * @author George Politis
 * @author Pawel Domas
 */
public class RtpChannelTransformEngine
    extends TransformEngineChain
{
    /**
     * The {@link Logger} used by the {@link RtpChannelTransformEngine} class
     * to print debug information. Note that {@link Conference} instances should
     * use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(RtpChannelTransformEngine.class);
    /**
     * The payload type number for RED packets. We should set this dynamically
     * but it is not clear exactly how to do it, because it isn't used on this
     * <tt>RtpChannel</tt>, but on other channels from the <tt>Content</tt>.
     */
    private static final byte RED_PAYLOAD_TYPE = 116;

    /**
     * The <tt>RtpChannel</tt> associated with this transformer.
     */
    private final RtpChannel channel;

    /**
     * The transformer which strips RED encapsulation.
     */
    private REDFilterTransformEngine redFilter;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>RtpChannelTransformEngine</tt> for a specific
     * <tt>RtpChannel</tt>.
     * @param channel the <tt>RtpChannel</tt>.
     * @param manipulators chain manipulators
     */
    public RtpChannelTransformEngine(
            RtpChannel channel,
            Iterable<TransformChainManipulator> manipulators)
    {
        this.channel = channel;
        this.logger
            = Logger.getLogger(
                    classLogger,
                    channel.getContent().getConference().getLogger());

        TransformEngine[] chain = createChain();

        if (manipulators != null)
        {
            for (TransformChainManipulator manipulator : manipulators)
            {
                chain = manipulator.manipulate(chain, channel);
            }
        }
        engineChain = chain;
    }

    /**
     * A packet details logging transform engine that logs packet details of
     * incoming keyframes.
     */
    class PacketDetailsObserver
        extends SinglePacketTransformerAdapter
        implements TransformEngine
    {
        @Override
        public PacketTransformer getRTPTransformer()
        {
            return this;
        }

        @Override
        public PacketTransformer getRTCPTransformer()
        {
            return null;
        }

        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (pkt == null || !classLogger.isDebugEnabled() || !isKeyFrame(
                pkt.getBuffer(),
                pkt.getPayloadOffset(),
                pkt.getPayloadLength()))
            {
                return pkt;
            }

            classLogger.debug(new StringBuilder()
                .append("Observed an incoming keyframe ").append(
                    ((MediaStreamImpl) channel.getStream()).packetToString(pkt)));

            return pkt;
        }
    }

    /**
     * Initializes the transformers used by this instance and returns them as
     * an array.
     */
    private TransformEngine[] createChain()
    {
        boolean video = (channel instanceof VideoChannel);

        // Bridge-end (the first transformer in the list executes first for
        // packets from the bridge, and last for packets to the bridge)
        List<TransformEngine> transformerList;

        if (video)
        {
            VideoChannel videoChannel = (VideoChannel) channel;

            transformerList = new LinkedList<>();

            BitrateController bitrateController
                = videoChannel.getBitrateController();

            if (bitrateController != null)
            {
                transformerList.add(bitrateController);
            }

            redFilter = new REDFilterTransformEngine(RED_PAYLOAD_TYPE);
            transformerList.add(redFilter);

            transformerList.add(new PacketDetailsObserver());
        }
        else
        {
            transformerList = Collections.emptyList();
        }

        // Endpoint-end (the last transformer in the list executes last for
        // packets from the bridge, and first for packets to the bridge)

        return
            transformerList.toArray(
                    new TransformEngine[transformerList.size()]);
    }

    /**
     * Enables stripping the RED encapsulation.
     * @param enabled whether to enable or disable.
     */
    public void enableREDFilter(boolean enabled)
    {
        if (redFilter != null)
            redFilter.setEnabled(enabled);
    }

    /**
     * Sets a delay of the RTP stream expressed in a number of packets.
     * The property is immutable which means than once set can not be changed
     * later.
     *
     * @param packetDelay tells by how many packets RTP stream should be
     * delayed. Will have effect only if greater than 0.
     *
     * @return <tt>true</tt> if the delay has been set or <tt>false</tt>
     * otherwise.
     */
    public boolean setPacketDelay(int packetDelay)
    {
        if (packetDelay > 0)
        {
            // Do not allow to add second delaying transformer
            // Note that replacing existing transformer will make any packets
            // queued in it's buffer disappear, so it's not safe to allow
            // replacement
            for (TransformEngine engine : engineChain)
            {
                if (engine instanceof DelayingTransformEngine)
                {
                    logger.warn(
                        "Can not modify packet-delay once it has been set.");
                    return false;
                }
            }

            if (addEngine(new DelayingTransformEngine(packetDelay)))
            {
                logger.info("Adding delaying packet transformer to "
                        + channel.getID() + ", packet delay: " + packetDelay);
                return true;
            }
            else
            {
                logger.warn("Failed to add delaying packet transformer");
                return false;
            }
        }
        return false;
    }
}
