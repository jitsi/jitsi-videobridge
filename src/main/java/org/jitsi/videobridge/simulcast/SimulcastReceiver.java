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

import org.jitsi.impl.neomedia.*;
import org.jitsi.util.*;
import java.util.concurrent.*;

import java.lang.ref.*;
import java.util.*;

/**
 * The <tt>SimulcastReceiver</tt> of a <tt>SimulcastEngine</tt> receives and
 * manages 2 or more simulcast streams from a simulcast enabled participant.
 * Listeners get notified whenever the simulcast streams start, stop or change
 * all together.
 *
 * This class is thread safe.
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class SimulcastReceiver
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingStreams</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastReceiver.class);

    /**
     * The number of (video) frames which defines the interval of time
     * (indirectly) during which a {@code SimulcastStream} needs to receive data
     * from its remote peer or it will be declared paused/stopped/not streaming
     * by its {@code SimulcastReceiver}.
     */
    private static final int TIMEOUT_ON_FRAME_COUNT = 5;

    /**
     * The list of listeners to be notified by this receiver when a change in
     * the simulcast reception happens.
     *
     * Here we're assuming that we're iterating much more than updating, thus
     * using a lockless CopyOnWriteArrayList makes sense. Updating this list
     * typically happens when a participant joins or leaves the conference,
     * while iterating happens everytime there is a change in the simulcast
     * streams. So our assumption seems to hold, without conducting any
     * experiments though.
     */
    private final List<WeakReference<Listener>> weakListeners
        = new CopyOnWriteArrayList<>();

    /**
     * The pool of threads utilized by this class. This could be a private
     * static final field but we want to be able to override it for testing.
     */
    static ExecutorService executorService = ExecutorUtils
        .newCachedThreadPool(true, SimulcastReceiver.class.getName());

    /**
     * The <tt>SimulcastEngine</tt> that owns this receiver.
     */
    private final SimulcastEngine simulcastEngine;

    /**
     * The simulcast stream of this <tt>VideoChannel</tt>.
     */
    private SimulcastStream[] simulcastStreams;

    /**
     * The history of the order/sequence of receipt of (video) frames by
     * {@link #simulcastStreams}. Used in an attempt to speed up the detection of
     * paused/stopped {@code SimulcastStream}s by counting (video) frames.
     */
    private final List<SimulcastStream> simulcastStreamFrameHistory
        = new LinkedList<>();

    /**
     * Ctor.
     *
     * @param simulcastEngine the <tt>SimulcastEngine</tt> that owns this
     * receiver.
     */
    public SimulcastReceiver(SimulcastEngine simulcastEngine)
    {
        this.simulcastEngine = simulcastEngine;
    }

    /**
     * Gets the <tt>SimulcastEngine</tt> that owns this receiver.
     *
     * @return the <tt>SimulcastEngine</tt> that owns this receiver.
     */
    public SimulcastEngine getSimulcastEngine()
    {
        return this.simulcastEngine;
    }

    /**
     * Returns true if the endpoint has signaled two or more simulcast streams.
     *
     * @return true if the endpoint has signaled two or more simulcast streams,
     * false otherwise.
     */
    public boolean isSimulcastSignaled()
    {
        SimulcastStream[] sl = simulcastStreams;
        return sl != null && sl.length != 0;
    }

    /**
     * Returns a <tt>SimulcastStream</tt> that is the closest match to the target
     * order, or null if simulcast hasn't been configured for this receiver.
     *
     * @param targetOrder the simulcast stream target order.
     * @return a <tt>SimulcastStream</tt> that is the closest match to the target
     * order, or null.
     */
    public SimulcastStream getSimulcastStream(int targetOrder)
    {
        SimulcastStream[] simStreams = getSimulcastStreams();
        if (simStreams == null || simStreams.length == 0)
        {
            return null;
        }

        // Iterate through the simulcast streams that we own and return the one
        // that matches best the targetOrder parameter.
        SimulcastStream next = simStreams[0];
        for (int i = 1; i < Math.min(targetOrder + 1, simStreams.length); i++)
        {
            if (!simStreams[i].isStreaming())
            {
                break;
            }

            next = simStreams[i];
        }

        return next;
    }

    /**
     * Gets the simulcast streams of this simulcast manager in a new
     * <tt>SortedSet</tt> so that the caller won't have to worry about the
     * structure changing by some other thread.
     *
     * @return the simulcast streams of this receiver in a new sorted set if
     * simulcast is signaled, or null.
     */
    public SimulcastStream[]  getSimulcastStreams()
    {
        return simulcastStreams;
    }

    public void setSimulcastStreams(long[] ssrcs)
    {
        SimulcastStream[] streams = null;
        if (ssrcs != null && ssrcs.length != 0)
        {
            streams = new SimulcastStream[ssrcs.length];
            for (int i = 0; i < ssrcs.length; i++)
            {
                streams[i] = new SimulcastStream(this, ssrcs[i], i);
            }
        }

        setSimulcastStreams(streams);
    }
    /**
     * Sets the simulcast streams for this receiver and fires an event about it.
     *
     * @param simulcastStreams the simulcast streams for this receiver.
     */
    public void setSimulcastStreams(SimulcastStream[] simulcastStreams)
    {
        this.simulcastStreams = simulcastStreams;

        if (logger.isInfoEnabled())
        {
            if (simulcastStreams == null)
            {
                logger.info("Simulcast disabled.");
            }
            else
            {
                for (SimulcastStream l : simulcastStreams)
                {
                    logger.info(l.getOrder() + ": " + l.getPrimarySSRC());
                }
            }
        }

       fireSimulcastStreamsSignaled();

        // TODO If simulcastStreams has changed, then simulcastStreamFrameHistory
        // has very likely become irrelevant. In other words, clear
        // simulcastStreamFrameHistory.
    }

    /**
     * Adds a weak listener to the list of listeners to be notified about
     * changes in this <tt>SimulcastReceiver</tt>.
     *
     * @param weakListener the weak listener to be added in the list of
     * listeners to be notified about changes in this
     * <tt>SimulcastReceiver</tt>.
     */
    public void addWeakListener(WeakReference<Listener> weakListener)
    {
        // Adds the listener to the list. Expensive operation.
        weakListeners.add(weakListener);
    }

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     * @param pkt the accepted <tt>RawPacket</tt>.
     */
    public void accepted(RawPacket pkt)
    {
        // With native simulcast we don't have a notification when a stream
        // has started/stopped. The simulcast manager implements a timeout
        // for the high quality stream and it needs to be notified when
        // the channel has accepted a datagram packet for the timeout to
        // function correctly.

        if (pkt == null)
        {
            return;
        }

        SimulcastStream[] simStreams = getSimulcastStreams();
        if (simStreams == null || simStreams.length == 0)
        {
            return;
        }

        // Find the simulcast stream that corresponds to this packet.
        long acceptedSSRC = pkt.getSSRCAsLong();
        SimulcastStream acceptedStream = null;
        for (SimulcastStream simStream : simStreams)
        {
            // We only care about the primary SSRC and not the RTX ssrc (or
            // future FEC ssrc).
            if (simStream.getPrimarySSRC() == acceptedSSRC)
            {
                acceptedStream = simStream;
                break;
            }
        }

        // If this is not an RTP packet or if we can't find an accepted
        // simulcast stream, log and return as it makes no sense to continue in
        // this situation.
        if (acceptedStream == null)
        {
            return;
        }

        // There are sequences of packets with increasing timestamps but without
        // the marker bit set. Supposedly, they are probes to detect whether the
        // bandwidth may increase. We think that they should cause neither the
        // start nor the stop of any SimulcastStream.

        // XXX There's RawPacket#getPayloadLength() but the implementation
        // includes pkt.paddingSize at the time of this writing and we do not
        // know whether that's going to stay that way.
        int pktPayloadLength = pkt.getLength() - pkt.getHeaderLength();
        int pktPaddingSize = pkt.getPaddingSize();

        if (pktPayloadLength <= pktPaddingSize)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace(
                    "pkt.payloadLength= " + pktPayloadLength
                        + " <= pkt.paddingSize= " + pktPaddingSize + "("
                        + pkt.getSequenceNumber() + ")");
            }
            return;
        }

        // NOTE(gp) we expect the base stream to be always on, so we never touch
        // it or starve it.

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

        if (acceptedStream.lastPktTimestamp <= pktTimestamp)
        {
            if (acceptedStream.lastPktTimestamp < pktTimestamp)
            {
                // The current pkt signals the receit of a piece of a new (i.e.
                // unobserved until now) frame.
                acceptedStream.lastPktTimestamp = pktTimestamp;
                frameStarted = true;
            }

            int pktSequenceNumber = pkt.getSequenceNumber();
            boolean pktSequenceNumberIsInOrder = true;

            if (acceptedStream.lastPktSequenceNumber != -1)
            {
                int expectedPktSequenceNumber
                    = acceptedStream.lastPktSequenceNumber + 1;

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
                    if (frameStarted && acceptedStream.lastPktMarker != null
                        && !acceptedStream.lastPktMarker)
                    {
                        frameStarted = false;
                        if (logger.isTraceEnabled())
                        {
                            logger.trace(
                                    "order-" + acceptedStream.getOrder() + " stream ("
                                        + acceptedStream.getPrimarySSRC()
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
                                        + (acceptedStream.isKeyFrame(pkt) ? "key" : "delta")
                                        + " frame.");
                        }
                    }
                }
                else if (pktSequenceNumber > acceptedStream.lastPktSequenceNumber)
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
                acceptedStream.lastPktMarker
                    = pkt.isPacketMarked() ? Boolean.TRUE : Boolean.FALSE;
                acceptedStream.lastPktSequenceNumber = pktSequenceNumber;
            }
        }

        if (!frameStarted)
        {
            return;
        }

        if (acceptedStream.isKeyFrame(pkt))
        {
            acceptedStream.keyFrameRequested.set(false);
        }

        if (acceptedStream.getOrder() != 0 && !acceptedStream.isStreaming)
        {
            // If the frame-based approach to the detection of stream drops works
            // (i.e. there will always be at least 1 high quality frame among
            // SimulcastReceiver#TIMEOUT_ON_FRAME_COUNT consecutive low quality
            // frames), then it may be argued that a late pkt (i.e. which does not
            // start a new frame after this SimulcastStream has been stopped) should
            // not start this SimulcastStream.

            // Do not activate the hq stream if the bitrate estimation is not
            // above 300kbps.

            acceptedStream.isStreaming = true;

            if (logger.isDebugEnabled())
            {
                logger.debug(
                    "order-" + acceptedStream.getOrder() + " stream (" +
                        acceptedStream.getPrimarySSRC()
                        + ") resumed on seqnum " + pkt.getSequenceNumber()
                        + ", " + (acceptedStream.isKeyFrame(pkt) ? "key" : "delta")
                        + " frame.");
            }

            fireSimulcastStreamsChangedAsync(acceptedStream);
        }

        // Determine whether any of {@link #simulcastStreams} other than
        // {@code acceptedStream} have been paused/stopped by the remote peer.
        // The determination is based on counting (video) frames.

        // Timeouts in simulcast streams caused by source may occur only based
        // on the span (of time or received frames) during which source has
        // received TIMEOUT_ON_FRAME_COUNT number of frames. The current method
        // invocation signals the receipt of 1 frame by source.
        int indexOfLastSourceOccurrenceInHistory = -1;
        int sourceFrameCount = 0;
        int ix = 0;

        for (Iterator<SimulcastStream> it
             = simulcastStreamFrameHistory.iterator();
             it.hasNext();
             ++ix)
        {
            if (it.next() == acceptedStream)
            {
                if (indexOfLastSourceOccurrenceInHistory != -1)
                {
                    // Prune simulcastStreamFrameHistory so that it does not
                    // become unnecessarily long.
                    it.remove();
                }
                else if (++sourceFrameCount >= TIMEOUT_ON_FRAME_COUNT - 1)
                {
                    // The span of TIMEOUT_ON_FRAME_COUNT number of frames
                    // received by source only is to be examined for the
                    // purposes of timeouts. The current method invocations
                    // signals the receipt of 1 frame by source so
                    // TIMEOUT_ON_FRAME_COUNT - 1 occurrences of source in
                    // simulcastStreamFrameHistory is enough.
                    indexOfLastSourceOccurrenceInHistory = ix;
                }
            }
        }

        if (indexOfLastSourceOccurrenceInHistory == -1)
        {
            return;
        }

        // Presumably, if a SimulcastStream is active, all SimulcastStreams
        // before it (according to SimulcastStream's order) are active as
        // well. Consequently, timeouts may occur in SimulcastStreams which
        // are after source.
        boolean maybeTimeout = false;

        for (SimulcastStream simStream : simStreams)
        {
            if (maybeTimeout)
            {
                // There's no point in timing stream out if it's timed out
                // already.
                if (simStream.isStreaming())
                {
                    maybeTimeout(
                        acceptedStream,
                        pkt,
                        simStream,
                        indexOfLastSourceOccurrenceInHistory);
                }
            }
            else if (simStream == acceptedStream)
            {
                maybeTimeout = true;
            }
        }


        // As previously stated, the current method invocation signals the
        // receipt of 1 frame by source.
        simulcastStreamFrameHistory.add(0, acceptedStream);
        // TODO Prune simulcastStreamFrameHistory by forgetting so that it does
        // not become too long.
    }

    /**
     * Asks for a keyframe for the <tt>SimulcastStream</tt> passed in as a
     * param.
     *
     * @param simulcastStream
     */
    public void askForKeyframe(final SimulcastStream simulcastStream)
    {
        if (simulcastStream == null)
        {
            logger.warn("Didn't ask for key frame because the simulcastStream" +
                    " is null!");
            return;
        }

        if (!simulcastStream.keyFrameRequested.compareAndSet(false, true))
        {
            // TODO this should probably managed at a lower level.
            logger.debug("Keyframe request throttling.");
            return;
        }

        executorService.execute(new Runnable()
        {
            @Override
            public void run()
            {
                SimulcastEngine peerSM = getSimulcastEngine();
                if (peerSM == null)
                {
                    logger.warn("Requested a key frame but the peer simulcast " +
                            "manager is null!");
                    return;
                }
                else
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Asking for a key frame for "
                                + simulcastStream.getPrimarySSRC());
                    }
                }

                peerSM.getVideoChannel().askForKeyframes(
                        new int[]{(int) simulcastStream.getPrimarySSRC()});
            }
        });
    }

    /**
     * Determines whether {@code effect} has been paused/stopped by the remote
     * peer. The determination is based on counting frames and is triggered by
     * the receipt of (a piece of) a new (video) frame by {@code cause}.
     *
     * @param cause the {@code SimulcastStream} which has received (a piece of) a
     * new (video) frame and has thus triggered a check on {@code effect}
     * @param pkt the {@code RawPacket} which was received by {@code cause} and
     * possibly influenced the decision to trigger a check on {@code effect}
     * @param effect the {@code SimulcastStream} which is to be checked whether
     * it looks like it has been paused/stopped by the remote peer
     * @param endIndexInSimulcastStreamFrameHistory
     */
    private void maybeTimeout(
            SimulcastStream cause,
            RawPacket pkt,
            SimulcastStream effect,
            int endIndexInSimulcastStreamFrameHistory)
    {
        Iterator<SimulcastStream> it = simulcastStreamFrameHistory.iterator();
        boolean timeout = true;

        for (int ix = 0;
                it.hasNext() && ix < endIndexInSimulcastStreamFrameHistory;
                ++ix)
        {
            if (it.next() == effect)
            {
                timeout = false;
                break;
            }
        }
        if (timeout)
        {
            if (!effect.isStreaming())
            {
                // Since effect has been determined to have been paused/stopped
                // by the remote peer, its possible presence in
                // simulcastStreamFrameHistory is irrelevant now. In other words,
                // remove effect from simulcastStreamFrameHistory.
                while (it.hasNext())
                {
                    if (it.next() == effect)
                        it.remove();
                }
            }
            else
            {
                effect.isStreaming = false;

                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "order-" + effect.getOrder() + " stream (" + effect.getPrimarySSRC()
                            + ") stopped on seqnum " + pkt.getSequenceNumber()
                            + ".");
                }

                // XXX(gp) One could try to ask for a key frame now, if the packet that
                // caused the resuming of the high quality stream isn't a key frame; But
                // the correct approach is to handle  this with the SimulcastSender
                // because stream switches happen not only when a stream resumes or drops
                // but also when the selected endpoint at a given receiving endpoint
                // changes, for example.

                // TODO merge with other fire event
                fireSimulcastStreamsChangedAsync(effect);
            }
        }
    }

    /**
     */
    private void fireSimulcastStreamsSignaled()
    {
        // This can be synchronous as its not called from inside a time
        // critical method (like reading/writing packets).
        Iterator<WeakReference<SimulcastReceiver.Listener>>
            it = weakListeners.iterator();

        while (it.hasNext())
        {
            WeakReference<SimulcastReceiver.Listener> weakNext = it.next();
            SimulcastReceiver.Listener next = weakNext.get();
            if (next == null)
            {
                // Clean-up the list. Expensive operation.
                it.remove();
            }
            else
            {
                next.simulcastStreamsSignaled();
            }
        }
    }

    /**
     * @param simulcastStreams the <tt>SimulcastStream</tt>s that have changed.
     */
    private void fireSimulcastStreamsChangedAsync(
        SimulcastStream... simulcastStreams)
    {
        // This operation needs to be async because it may end up requesting a
        // key frame.
        executorService.execute(
            new SimulcastStreamsChangedRunnable(simulcastStreams));
    }

    /**
     */
    public interface Listener
    {
        void simulcastStreamsChanged(SimulcastStream ... simulcastStreams);

        void simulcastStreamsSignaled();
    }

    /**
     */
    class SimulcastStreamsChangedRunnable
        implements Runnable
    {
        /**
         * Ctor.
         *
         * @param simulcastStreams the <tt>SimulcastStream</tt>s that have
         * changed.
         */
        public SimulcastStreamsChangedRunnable(
                SimulcastStream... simulcastStreams)
        {
            this.simulcastStreams = simulcastStreams;
        }

        /**
         * The <tt>SimulcastStream</tt>s that have changed.
         */
        private final SimulcastStream[] simulcastStreams;

        @Override
        public void run()
        {
            Iterator<WeakReference<SimulcastReceiver.Listener>>
                it = weakListeners.iterator();

            while (it.hasNext())
            {
                WeakReference<SimulcastReceiver.Listener> weakNext = it.next();
                SimulcastReceiver.Listener next = weakNext.get();
                if (next == null)
                {
                    // Clean-up the list.
                    it.remove();
                }
                else
                {
                    next.simulcastStreamsChanged(simulcastStreams);
                }
            }
        }
    }
}
