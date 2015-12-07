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
     * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastStream> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastStream> weakNext;

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingStreams</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(RewritingSendMode.class);

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

        SimulcastStream next = getNext();
        if (next != null && next.match(pkt) && next.isKeyFrame(pkt))
        {
            // There's a next simulcast stream. Let's see if we can switch to
            // it.
            weakCurrent = new WeakReference<>(next);
            weakNext = null;
            return true;
        }

        SimulcastStream current = getCurrent();
        if (current != null && current.match(pkt))
        {
            return true;
        }

        return false;
    }

    @Override
    public void receive(SimulcastStream simStream, boolean urgent)
    {
        SimulcastStream current = getCurrent();
        SimulcastStream next = getNext();

        if (current == simStream || next == simStream)
        {
            if (logger.isDebugEnabled())
            {
                logDebug("order-" + simStream.getOrder()
                        + " stream is already the target.");
            }

            return;
        }

        if (logger.isDebugEnabled())
        {
            logDebug("order-" + simStream.getOrder()
                    + " is the target (urgent:" + urgent + ") from " +
                    getSimulcastSender().getSimulcastReceiver()
                    .getSimulcastEngine()
                    .getVideoChannel().getEndpoint().getID() + ".");
        }

        simStream.askForKeyframe();
        if (urgent || current == null)
        {
            weakCurrent = new WeakReference<>(simStream);
        }
        else
        {
            weakNext = new WeakReference<>(simStream);
        }
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg =
                getSimulcastSender().getReceiveEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg =
                getSimulcastSender().getReceiveEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }

    /**
     * Gets the <tt>SimulcastStream</tt> that is currently being received.
     *
     * @return
     */
    public SimulcastStream getCurrent()
    {
        WeakReference<SimulcastStream> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastStream</tt> that was previously being received.
     *
     * @return
     */
    public SimulcastStream getNext()
    {
        WeakReference<SimulcastStream> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }
}
