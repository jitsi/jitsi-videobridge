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
 * The <tt>RewritingSendMode</tt> implements the layers rewriting mode in which
 * the endpoint receiving the simulcast that we send it is not aware of all the
 * simulcast layer SSRCs and it does not manage the switching at the client
 * side. The receiving endpoint is not notified about changes in the layers that
 * it receives.
 *
 * @author George Politis
 */
public class RewritingSendMode
    extends SendMode
{
    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastLayer> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastLayer> weakNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that overrides
     * the layer that is currently being received. Originally introduced for
     * adaptive bitrate control and the <tt>SimulcastAdaptor</tt>.
     */
    private WeakReference<SimulcastLayer> weakOverride;

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
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
        SimulcastLayer next = getNext();
        if (next != null && next.match(pkt) && next.isKeyFrame(pkt))
        {
            // There's a next simulcast stream. Let's see if we can switch to
            // it.
            weakCurrent = new WeakReference<SimulcastLayer>(next);
            return true;
        }

        SimulcastLayer current = getCurrent();
        boolean accept = false;
        if (current != null && current.match(pkt))
        {
            return true;
        }

        return false;
    }

    @Override
    public void receiveHigh()
    {
        logDebug("Targeting high from " + getSimulcastSender()
            .getSimulcastReceiver().getSimulcastEngine().getVideoChannel()
            .getEndpoint().getID() + ".");
        SimulcastReceiver simulcastReceiver
            = getSimulcastSender().getSimulcastReceiver();

        SimulcastLayer highLayer = simulcastReceiver.getSimulcastLayer(
            SimulcastLayer.SIMULCAST_LAYER_ORDER_HQ);

        weakNext = new WeakReference<SimulcastLayer>(highLayer);
    }

    @Override
    public void receiveLow(boolean urgent)
    {
        logDebug("Targeting low (urgent:" + urgent + ") from " +
            getSimulcastSender().getSimulcastReceiver().getSimulcastEngine()
                .getVideoChannel().getEndpoint().getID() + ".");

        SimulcastReceiver simulcastReceiver
            = getSimulcastSender().getSimulcastReceiver();

        SimulcastLayer lowLayer = simulcastReceiver.getSimulcastLayer(
            SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ);

        if (urgent)
            weakCurrent = new WeakReference<SimulcastLayer>(lowLayer);
        else
            weakNext = new WeakReference<SimulcastLayer>(lowLayer);
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
     * Gets the <tt>SimulcastLayer</tt> that is currently being received.
     *
     * @return
     */
    private SimulcastLayer getCurrent()
    {
        WeakReference<SimulcastLayer> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that was previously being received.
     *
     * @return
     */
    private SimulcastLayer getNext()
    {
        WeakReference<SimulcastLayer> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that overrides the layer that is
     * currently being received. Originally introduced for the
     * <tt>SimulcastAdaptor</tt>.
     *
     * @return
     */
    private SimulcastLayer getOverride()
    {
        WeakReference<SimulcastLayer> wr = this.weakOverride;
        return (wr != null) ? wr.get() : null;
    }
}
