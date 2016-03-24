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

        SimulcastStream next
            = oldState.weakNext != null ? oldState.weakNext.get() : null;

        if (next != null && next.matches(pkt) && next.isKeyFrame(pkt))
        {
            // There's a next simulcast stream. Let's see if we can switch to
            // it.
            this.state = new State(new WeakReference<>(next), null);
            return true;
        }

        SimulcastStream current
            = oldState.weakCurrent != null ? oldState.weakCurrent.get() : null;
        return current != null && current.matches(pkt);
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
        SimulcastStream current
            = oldState.weakCurrent != null ? oldState.weakCurrent.get() : null;
        SimulcastStream next
            = oldState.weakNext != null ? oldState.weakNext.get() : null;

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
         * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that is
         * currently being received.
         */
        private final WeakReference<SimulcastStream> weakCurrent;

        /**
         * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that will be
         * (possibly) received next.
         */
        private final WeakReference<SimulcastStream> weakNext;
    }
}
