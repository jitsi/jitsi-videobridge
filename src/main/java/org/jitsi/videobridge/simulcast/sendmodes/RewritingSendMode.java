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
package org.jitsi.videobridge.simulcast.sendmodes;

import org.jitsi.impl.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.simulcast.*;

import java.lang.ref.*;
import java.util.*;

/**
 * The <tt>RewritingSendMode</tt> implements the streams rewriting mode in which
 * the endpoint receiving the simulcast that we send it is not aware of all the
 * simulcast stream SSRCs and it does not manage the switching at the client
 * side. The receiving endpoint is not notified about changes in the streams
 * that it receives.
 *
 * @author George Politis
 */
public class RewritingSendMode
    extends SendMode
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingStreams</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(RewritingSendMode.class);

    /**
     * Holds the state of this {@code RewritingSendMode}. Grouping the state in
     * a single object allows for synchronized-less code.
     */
    private State state = new State(null, null);

    /**
     * A map that holds the last sequence number that we've seen for a given
     * SSRC.
     */
    private final Map<Long, Integer> lastPktSequenceNumbers = new HashMap<>();

    /**
     * Ctor.
     *
     * @param simulcastSender
     */
    public RewritingSendMode(SimulcastSender simulcastSender)
    {
        super(simulcastSender);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        State oldState = this.state;

        SimulcastStream next = oldState.getNext();

        Long pktSSRC = pkt.getSSRCAsLong();
        Integer pktSequenceNumber = pkt.getSequenceNumber();
        int diff = 1;

        Integer lastReceivedSeq = lastPktSequenceNumbers.get(pktSSRC);
        if (lastReceivedSeq != null)
        {
            diff = RTPUtils.sequenceNumberDiff(
                pkt.getSequenceNumber(), lastReceivedSeq);
        }

        boolean accept = false;
        if (next != null && next.matches(pkt) && next.isKeyFrame(pkt))
        {
            // This is the first packet of a keyframe.
            if (diff >= 0)
            {
                this.state = new State(new WeakReference<>(next), null);
                accept = true;
            }
            else
            {
                // The first packet of a keyframe arrives out of order (maybe it
                // was lost and retransmitted). Some of the remaining packets
                // from the keyframe may have already been received and dropped
                // since they were not recognized as belonging to a keyframe. In
                // this case we don't want to switch to 'next' yet, as it will
                // not be in a decodable state (even worse, some of the
                // keyframe's packets will be missing from our cache, and will
                // not be requested from the sender (since they were received)).

                // We don't expect this to happen often, so we will just ask
                // for another keyframe.
                logger.warn(
                    "Ignoring a keyframe on the stream we want to switch to. "
                    + "The packet is old: seq=" + pkt.getSequenceNumber()
                    + " diff=" + diff + " SSRC="
                    + pkt.getSSRCAsLong());

                next.askForKeyframe();
            }
        }
        else
        {
            SimulcastStream current = oldState.getCurrent();
            accept = current != null && current.matches(pkt);
        }

        if (diff >= 0)
        {
            lastPktSequenceNumbers.put(pktSSRC, pktSequenceNumber);
        }

        return accept;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(SimulcastStream simStream)
    {
        if (simStream == null)
        {
            // This is acceptable when a participant leaves.
            this.state = new State(null, null);
            return;
        }

        State oldState = this.state;
        SimulcastStream current = oldState.getCurrent();
        SimulcastStream next = oldState.getNext();

        if (current == simStream || next == simStream)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("order-" + simStream.getOrder()
                        + " stream is already the target.");
            }

            return;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("order-" + simStream.getOrder()
                    + " is the target from " +
                    getSimulcastSender().getSimulcastReceiver()
                    .getSimulcastEngine()
                    .getVideoChannel().getEndpoint().getID() + ".");
        }

        simStream.askForKeyframe();
        if (current == null)
        {
            this.state
                = new State(new WeakReference<>(simStream), oldState.weakNext);
        }
        else
        {
            this.state
                = new State(oldState.weakCurrent, new WeakReference<>(simStream));
        }
    }

    /**
     * A simple class that holds the state of a {@code RewritingSendMode}.
     */
    static class State
    {
        /**
         * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that is
         * currently being received.
         */
        private final WeakReference<SimulcastStream> weakCurrent;

        /**
         * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that will be
         * (possibly) received next.
         */
        private final WeakReference<SimulcastStream> weakNext;

        /**
         * Ctor.
         *
         * @param weakCurrent A <tt>WeakReference</tt> to the
         * <tt>SimulcastStream</tt> that is currently being received.
         * @param weakNext A <tt>WeakReference</tt> to the
         * <tt>SimulcastStream</tt> that is currently being received.
         */
        public State(
            WeakReference<SimulcastStream> weakCurrent,
            WeakReference<SimulcastStream> weakNext)
        {
            this.weakCurrent = weakCurrent;
            this.weakNext = weakNext;
        }

        /**
         * Returns the <tt>SimulcastStream</tt> that will be (possibly) received
         * next.
         *
         * @return the <tt>SimulcastStream</tt> that will be (possibly) received
         * next.
         */
        public SimulcastStream getNext()
        {
            return weakNext == null ? null : weakNext.get();
        }

        /**
         * Returns the <tt>SimulcastStream</tt> that is currently being
         * received.
         *
         * @return the <tt>SimulcastStream</tt> that is currently being
         * received.
         */
        public SimulcastStream getCurrent()
        {
            return weakCurrent == null ? null : weakCurrent.get();
        }
    }
}
