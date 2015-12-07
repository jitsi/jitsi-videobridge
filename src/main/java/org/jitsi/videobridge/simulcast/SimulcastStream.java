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
package org.jitsi.videobridge.simulcast;

import java.util.concurrent.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.impl.neomedia.codec.video.*;

/**
 * The <tt>SimulcastStream</tt> of a <tt>SimulcastReceiver</tt> represents a
 * simulcast stream. It determines when a simulcast stream has been
 * stopped/started and fires a property change event when that happens. It also
 * gathers bitrate statistics for the associated stream.
 *
 * This class is thread safe.
 *
 * TODO Rename SimulcastStream -&gt; SimulcastStream to be consistent with
 * the webrtc.org codebase.
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class SimulcastStream
    extends PropertyChangeNotifier
    implements Comparable<SimulcastStream>
{

    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastStream</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastStream.class);

    /**
     * The name of the property that gets fired when this stream stops or starts
     * streaming.
     */
    public static final String IS_STREAMING_PNAME =
            SimulcastStream.class.getName() + ".isStreaming";

    /**
     * Base simlucast stream quality order.
     */
    public static final int SIMULCAST_LAYER_ORDER_BASE = 0;

    private static final byte REDPT = 0x74;

    private static final byte VP8PT = 0x64;
    /**
     * The pool of threads utilized by <tt>SimulcastReceiver</tt>.
     */
    private static final ExecutorService executorService
        = ExecutorUtils.newCachedThreadPool(
                true,
                SimulcastStream.class.getName());

    /**
     * The <tt>Runnable</tt> that requests key frames.
     */
    private final KeyFrameRequestRunnable keyFrameRequestRunnable
        = new KeyFrameRequestRunnable();

    /**
     * The <tt>SimulcastReceiver</tt> that owns this simulcast stream.
     */
    private final SimulcastReceiver simulcastReceiver;

    /**
     * The primary SSRC for this simulcast stream.
     */
    private long primarySSRC = -1; // FIXME We need an INVALID_SSRC cnt.

    /**
     * The RTX SSRC for this simulcast stream.
     */
    private long rtxSSRC = -1; // FIXME We need an INVALID_SSRC cnt.

    /**
     * The FEC SSRC for this simulcast stream.
     *
     * XXX This isn't currently used anywhere because Chrome doens't use a
     * separate SSRC for FEC.
     */
    private long fecSSRC = -1; // FIXME We need an INVALID_SSRC cnt.

    /**
     * The order of this simulcast stream.
     */
    private final int order;

    /**
     * Holds a boolean indicating whether or not this simulcast stream is
     * streaming.
     */
    private boolean isStreaming = false;

    /**
     * The value of the RTP marker (bit) of the last {@code RawPacket} seen by
     * this {@code SimulcastStream}. Technically, the order of the receipt of RTP
     * packets may be disturbed by the network transport (e.g. UDP) and/or RTP
     * packet retransmissions so the value of {@code lastPktMarker} may not come
     * from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission abberations.
     */
    private Boolean lastPktMarker;

    /**
     * The {@code sequenceNumber} of the last {@code RawPacket} seen by this
     * {@code SimulcastStream}. Technically, the order of the receipt of RTP
     * packets may be disturbed by the network transport (e.g. UDP) and/or RTP
     * packet retransmissions so the value of {@code lastPktSequenceNumber} may
     * not come from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission abberations.
     */
    private int lastPktSequenceNumber = -1;

    /**
     * The {@code timestamp} of the last {@code RawPacket} seen by this
     * {@code SimulcastStream}. Technically, the order of the receipt of RTP
     * packets may be disturbed by the network transport (e.g. UDP) and/or RTP
     * packet retransmissions so the value of {@code lastPktTimestamp} may not
     * come from the last received {@code RawPacket} but from a received
     * {@code RawPacket} which would have been the last received if there were
     * no network transport and RTP packet retransmission abberations.
     */
    private long lastPktTimestamp = -1;

    /**
     * Ctor.
     *
     * @param simulcastReicever
     * @param primarySSRC
     * @param order
     */
    public SimulcastStream(
            SimulcastReceiver simulcastReicever,
            long primarySSRC,
            int order)
    {
        this.simulcastReceiver = simulcastReicever;
        this.primarySSRC = primarySSRC;
        this.order = order;
    }

    /**
     * Gets the primary SSRC for this simulcast stream.
     *
     * @return the primary SSRC for this simulcast stream.
     */
    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    /**
     * Gets the RTX SSRC for this simulcast stream.
     *
     * @return the RTX SSRC for this simulcast stream.
     */
    public long getRTXSSRC()
    {
        return rtxSSRC;
    }

    /**
     * Gets the FEC SSRC for this simulcast stream.
     *
     * @return the FEC SSRC for this simulcast stream.
     */
    public long getFECSSRC()
    {
        return fecSSRC;
    }

    /**
     * Sets the RTX SSRC for this simulcast stream.
     *
     * @param rtxSSRC the new RTX SSRC for this simulcast stream.
     */
    public void setRTXSSRC(long rtxSSRC)
    {
        this.rtxSSRC = rtxSSRC;
    }

    /**
     * Sets the FEC SSRC for this simulcast stream.
     *
     * @param fecSSRC the new FEC SSRC for this simulcast stream.
     */
    public void setFECSSRC(long fecSSRC)
    {
        this.fecSSRC = fecSSRC;
    }

    /**
     * Gets the order of this simulcast stream.
     *
     * @return the order of this simulcast stream.
     */
    public int getOrder()
    {
        return order;
    }

    /**
     * Determines whether a packet belongs to this simulcast stream or not and
     * returns a boolean to the caller indicating that.
     *
     * @param pkt
     * @return true if the packet belongs to this simulcast stream, false
     * otherwise.
     */
    public boolean match(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        long ssrc = pkt.getSSRC() & 0xffffffffl;
        return ssrc == primarySSRC || ssrc == rtxSSRC || ssrc == fecSSRC;
    }

    /**
     * Compares this simulcast stream with another, implementing an order.
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(SimulcastStream o)
    {
        return (o == null) ? 1 : (order - o.order);
    }

    /**
     *
     * Gets a boolean indicating whether or not this simulcast stream is
     * streaming.
     *
     * @return true if this simulcast stream is streaming, false otherwise.
     */
    public boolean isStreaming()
    {
        // NOTE(gp) we assume 1. that the base stream is always streaming, and
        // 2. if stream N is streaming, then stream N-1 is streaming. N == order
        // in this class TAG(simulcast-assumption,arbitrary-sim-simStreams).
        return isStreaming ? isStreaming : order == 0;
    }

    /**
     * Increases the number of base stream packets that we've seen, and
     * potentially marks this stream as stopped and fires an event.
     *
     * @param useFrameBasedLogic {@code true} to use the (new) frame-based logic
     * for automagic {@code SimulcastStream} drop detection instead of the (old)
     * packet-based logic; otherwise, {@code false}
     * @param pkt the {@code RawPacket} which possibly influenced the decision
     * to trigger a check on this {@code SimulcastStream}. Most likely,
     * {@code pkt} is not part of the RTP stream represented by this
     * {@code SimulcastStream}.
     */
    public synchronized void maybeTimeout(
            RawPacket pkt)
    {
        if (this.isStreaming)
        {
            timeout(pkt);
        }
    }

    /**
     * Marks this {@code SimulcastStream} as stopped and fires an event.
     *
     * @param pkt the {@code RawPacket} which possibly influenced the decision
     * to time this {@code SimulcastStream} out. Most likely, {@code pkt} is not
     * part of the RTP stream represented by this {@code SimulcastStream}.
     */
    private synchronized void timeout(RawPacket pkt)
    {
        this.isStreaming = false;

        if (logger.isDebugEnabled())
        {
            logDebug(
                    "order-" + getOrder() + " stream (" + getPrimarySSRC()
                        + ") stopped on seqnum " + pkt.getSequenceNumber()
                        + ".");
        }

        // XXX(gp) One could try to ask for a key frame now, if the packet that
        // caused the resuming of the high quality stream isn't a key frame; But
        // the correct approach is to handle  this with the SimulcastSender
        // because stream switches happen not only when a stream resumes or drops
        // but also when the selected endpoint at a given receiving endpoint
        // changes, for example.
        firePropertyChange(IS_STREAMING_PNAME, true, false);
    }

    /**
     * Increases the number of packets of this stream that we've seen, and
     * potentially marks this stream as started and fires an event.
     *
     * @param pkt the {@code RawPacket} which has been received by the local
     * peer (i.e. Videobridge) from the remote peer, is part of (the flow of)
     * the RTP stream represented by this {@code SimulcastStream}, and has caused
     * the method invocation
     * @return {@code true} if {@code pkt} signals the receipt of (a piece of) a
     * new (i.e. unobserved until now) frame; otherwise, {@code false}
     */
    public synchronized boolean touch(RawPacket pkt)
    {
        // Attempt to speed up the detection of paused simulcast streams by
        // counting (video) frames instead of or in addition to counting
        // packets. The reasoning for why counting frames may be an optimization
        // is that (1) frames may span varying number of packets and (2) the
        // webrtc.org implementation consecutively (from low quality to high
        // quality) sends frames for all sent (i.e. non-paused) simulcast
        // streams. RTP packets which transport pieces of one and the same frame
        // have one and the same timestamp and the last RTP packet has the
        // marker bit set. Since the RTP packet with the set marker bit may get
        // lost, it sounds more reliably to distinguish frames by looking at the
        // timestamps of the RTP packets.
        long pktTimestamp = pkt.readUnsignedIntAsLong(4);
        boolean frameStarted = false;

        if (lastPktTimestamp <= pktTimestamp)
        {
            if (lastPktTimestamp < pktTimestamp)
            {
                // The current pkt signals the receit of a piece of a new (i.e.
                // unobserved until now) frame.
                lastPktTimestamp = pktTimestamp;
                frameStarted = true;
            }

            int pktSequenceNumber = pkt.getSequenceNumber();
            boolean pktSequenceNumberIsInOrder = true;

            if (lastPktSequenceNumber != -1)
            {
                int expectedPktSequenceNumber = lastPktSequenceNumber + 1;

                // sequence number: 16 bits
                if (expectedPktSequenceNumber > 0xFFFF)
                    expectedPktSequenceNumber = 0;

                if (pktSequenceNumber == expectedPktSequenceNumber)
                {
                    // It appears no pkt was lost (or delayed). We can rely on
                    // lastPktMarker.

                    // XXX Sequences of packets have been observed with
                    // increasing RTP timestamps but without the marker bit set.
                    // Supposedly, they are probes to detect whether the
                    // bandwidth may increase. They may cause a SimulcastStream
                    // (other than this, of course) to time out. As a
                    // workaround, we will consider them to not signal new
                    // frames.
                    if (frameStarted && lastPktMarker != null && !lastPktMarker)
                    {
                        frameStarted = false;
                        if (logger.isTraceEnabled())
                        {
                            logTrace(
                                    "order-" + getOrder() + " stream ("
                                        + getPrimarySSRC()
                                        + ") detected an alien pkt: seqnum "
                                        + pkt.getSequenceNumber() + ", ts "
                                        + pktTimestamp + ", "
                                        + (pkt.isPacketMarked()
                                                ? "marker, "
                                                : "")
                                        + "payload "
                                        + (pkt.getLength()
                                                - pkt.getHeaderLength()
                                                - pkt.getPaddingSize())
                                        + " bytes, "
                                        + (isKeyFrame(pkt) ? "key" : "delta")
                                        + " frame.");
                        }
                    }
                }
                else if (pktSequenceNumber > lastPktSequenceNumber)
                {
                    // It looks like at least one pkt was lost (or delayed). We
                    // cannot rely on lastPktMarker.
                }
                else
                {
                    pktSequenceNumberIsInOrder = false;
                }
            }
            if (pktSequenceNumberIsInOrder)
            {
                lastPktMarker
                    = pkt.isPacketMarked() ? Boolean.TRUE : Boolean.FALSE;
                lastPktSequenceNumber = pktSequenceNumber;
            }
        }

        boolean base = order == 0;
        if (!base)
            touchNonBase(pkt, frameStarted);

        // XXX (1) We didn't go with a full-blown observer/listener pattern
        // implementation because that would have required much more effort to
        // optimize. (2) We didn't go with a direct methon invocation on
        // simulcastReceiver because that would have executed in the
        // synchronization block of this method.
        return frameStarted;
    }

    /**
     * Notifies this {@code SimulcastStream} that a specific {@code RawPacket}
     * has been received (over the network from the remote peer) which is part
     * of (the flow of) the RTP stream represented by this
     * {@code SimulcastStream}. This {@code SimulcastStream} is NOT the base
     * ({@code SimulcastStream}) of {@link #simulcastReceiver}.
     *
     * @param pkt the {@code RawPacket} which has been received by the local
     * peer (i.e. Videobridge) from the remote peer, is part of (the flow of)
     * the RTP stream represented by this {@code SimulcastStream}, and has caused
     * the method invocation
     * @param frameStarted {@code true} if {@code pkt} signals the receipt of (a
     * piece of) a new (i.e. unobserved until now) frame; otherwise,
     * {@code false}
     */
    private void touchNonBase(RawPacket pkt, boolean frameStarted)
    {
        if (this.isStreaming)
        {
            return;
        }

        // Allow the value of the constant TIMEOUT_ON_FRAME_COUNT to disable (at
        // compile time) the frame-based approach to the detection of stream
        // drops.

        // If the frame-based approach to the detection of stream drops works
        // (i.e. there will always be at least 1 high quality frame among
        // SimulcastReceiver#TIMEOUT_ON_FRAME_COUNT consecutive low quality
        // frames), then it may be argued that a late pkt (i.e. which does not
        // start a new frame after this SimulcastStream has been stopped) should
        // not start this SimulcastStream.
        if (SimulcastReceiver.TIMEOUT_ON_FRAME_COUNT > 1 && !frameStarted)
            return;

        // Do not activate the hq stream if the bitrate estimation is not
        // above 300kbps.

        this.isStreaming = true;

        if (logger.isDebugEnabled())
        {
            logDebug(
                    "order-" + getOrder() + " stream (" + getPrimarySSRC()
                        + ") resumed on seqnum " + pkt.getSequenceNumber()
                        + ", " + (isKeyFrame(pkt) ? "key" : "delta")
                        + " frame.");
        }

        firePropertyChange(IS_STREAMING_PNAME, false, true);
    }

    public boolean isKeyFrame(RawPacket pkt)
    {
        return Utils.isKeyFrame(pkt, REDPT, VP8PT);
    }

    /**
     * Utility method that asks for a keyframe for a specific simulcast stream.
     * This is typically done when switching streams. This method is executed in
     * the same thread that processes incoming packets. We must not block packet
     * reading so we request the key frame on a newly spawned thread.
     */
    public void askForKeyframe()
    {
        executorService.execute(keyFrameRequestRunnable);
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logInfo(String msg)
    {
        if (logger.isInfoEnabled())
        {
            msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.info(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }

    private void logTrace(String msg)
    {
        if (!logger.isTraceEnabled())
        {
            return;
        }

        msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
            .getEndpoint().getID() + ": " + msg;
        logger.trace(msg);
    }

    /**
     * The <tt>Runnable</tt> that requests key frames for this instance.
     */
    class KeyFrameRequestRunnable implements Runnable
    {
        @Override
        public void run()
        {
            SimulcastEngine peerSM
                = simulcastReceiver.getSimulcastEngine();
            if (peerSM == null)
            {
                logWarn("Requested a key frame but the peer simulcast " +
                        "manager is null!");
                return;
            }
            else
            {
                logDebug("Asking for a key frame for " + primarySSRC);
            }

            peerSM.getVideoChannel().askForKeyframes(
                    new int[]{(int) getPrimarySSRC()});
        }
    }
}
