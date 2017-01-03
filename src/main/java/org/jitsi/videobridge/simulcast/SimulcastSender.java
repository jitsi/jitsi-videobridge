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

import java.beans.*;
import java.lang.ref.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.util.Logger;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.sendmodes.*;

/**
 * The <tt>SimulcastSender</tt> is coupled with a <tt>SimulcastReceiver</tt> and
 * it decides which packets (based on SSRC) to accept/forward from that
 * <tt>SimulcastReceiver</tt>. It defines the rules that determine whether LQ or
 * HQ should be forwarded. It also handles spontaneous drops in simulcast
 * streams.
 *
 * @author George Politis
 */
public class SimulcastSender
    implements PropertyChangeListener
{
    /**
     * The {@link Logger} used by the {@link SimulcastSender} class to print
     * debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(SimulcastSender.class);

    /**
     * The <tt>SimulcastSenderManager</tt> that owns this instance.
     */
    private final SimulcastSenderManager simulcastSenderManager;

    /**
     * The <tt>SimulcastReceiver</tt> from which this <tt>SimulcastSender</tt>
     * receives the simulcast.
     */
    private final WeakReference<SimulcastReceiver> weakSimulcastReceiver;

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance.
     */
    private final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * The listener that listens for simulcast receiver changes.
     */
    private final SimulcastReceiver.Listener simulcastReceiverListener
        = new SimulcastReceiverListener();

    /**
     * The current <tt>SimulcastMode</tt> for this <tt>SimulcastSender</tt>. The
     * default is rewriting.
     */
    private SendMode sendMode;

    /**
     * The simulcast target order for this <tt>SimulcastSender</tt>.
     */
    private int targetOrder;

    /**
     * Indicates whether this <tt>SimulcastSender</tt> has been initialized or
     * not.
     */
    private boolean initialized = false;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Remembers whether we switched to the desired simulcast order.
     */
    private boolean retry = false;

    /**
     * Ctor.
     *
     * @param simulcastSenderManager the <tt>SimulcastSender</tt> that owns this
     * instance.
     * @param simulcastReceiver the associated <tt>SimulcastReceiver</tt>.
     */
    public SimulcastSender(
        SimulcastSenderManager simulcastSenderManager,
        SimulcastReceiver simulcastReceiver,
        int targetOrder)
    {
        this.simulcastSenderManager = simulcastSenderManager;

        // We don't own the receiver, keep a weak reference so that it can be
        // garbage collected.
        this.weakSimulcastReceiver = new WeakReference<>(simulcastReceiver);
        this.targetOrder = targetOrder;
        this.logger
            = Logger.getLogger(
                    classLogger,
                    simulcastSenderManager.getSimulcastEngine().getLogger());
    }

    /**
     * @return the target order of this {@link SimulcastSender}.
     */
    public int getTargetOrder()
    {
        return targetOrder;
    }

    /**
     * "Getter" on steroids that gets the <tt>Endpoint</tt> associated to this
     * instance.
     *
     * @return the <tt>Endpoint</tt> associated to this instance.
     */
    public Endpoint getReceiveEndpoint()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastEngine sendSimulcastEngine
            = getSimulcastSenderManager().getSimulcastEngine();

        if (sendSimulcastEngine == null)
        {
            return null;
        }

        VideoChannel sendVideoChannel
            = sendSimulcastEngine.getVideoChannel();

        if (sendVideoChannel == null)
        {
            return null;
        }

        Endpoint receiveEndpoint = sendVideoChannel.getEndpoint();

        if (receiveEndpoint == null)
        {
            logger.warn("Self is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                    Thread.currentThread().getStackTrace()));
            }
        }

        return receiveEndpoint;
    }

    /**
     * Gets the <tt>SimulcastReceiver</tt> of the peer.
     *
     * @return the <tt>SimulcastReceiver</tt> of the peer.
     */
    public SimulcastReceiver getSimulcastReceiver()
    {
        WeakReference<SimulcastReceiver> ws = this.weakSimulcastReceiver;
        return ws == null ? null : ws.get();
    }

    /**
     * Gets the <tt>SimulcastSenderManager</tt> that owns this instance.
     *
     * @return the <tt>SimulcastSenderManager</tt> that owns this instance.
     */
    public SimulcastSenderManager getSimulcastSenderManager()
    {
        return simulcastSenderManager;
    }

    /**
     * "Getter" on steroids that gets the <tt>Endpoint</tt> that sends the
     * simulcast.
     *
     * @return the peer <tt>Endpoint</tt>.
     */
    public Endpoint getSendEndpoint()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastReceiver simulcastReceiver = getSimulcastReceiver();
        if (simulcastReceiver == null)
        {
            return null;
        }

        SimulcastEngine receiveSimulcastEngine
            = simulcastReceiver.getSimulcastEngine();

        if (receiveSimulcastEngine == null)
        {
            logger.warn("The peer simulcast manager is null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        Arrays.toString(
                                Thread.currentThread().getStackTrace()));
            }

            return null;
        }

        VideoChannel receiveVideoChannel
            = receiveSimulcastEngine.getVideoChannel();

        if (receiveVideoChannel == null)
        {
            return null;
        }

        Endpoint sendEndpoint = receiveVideoChannel.getEndpoint();
        if (sendEndpoint == null)
        {
            logger.warn("Send endpoint is null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        Arrays.toString(
                                Thread.currentThread().getStackTrace()));
            }
        }

        return sendEndpoint;
    }

    /**
     * {@inheritDoc}
     *
     * Implements most, if not all, of our stream switching logic.
     */
    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        String propertyName = ev.getPropertyName();

        if (Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            Set<String> oldEndpoints = (Set<String>) ev.getOldValue();
            Set<String> newEndpoints = (Set<String>) ev.getNewValue();

            selectedEndpointChanged(oldEndpoints, newEndpoints);
        }
        else if (VideoChannel.SIMULCAST_MODE_PNAME.equals(propertyName))
        {
            logger.debug("The simulcast mode has changed.");

            SimulcastMode oldMode = (SimulcastMode) ev.getOldValue();
            SimulcastMode newMode = (SimulcastMode) ev.getNewValue();

            sendModeChanged(newMode, oldMode);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            logger.debug("The endpoint owner has changed.");

            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) ev.getNewValue();
            Endpoint oldValue = (Endpoint) ev.getOldValue();

            receiveEndpointChanged(newValue, oldValue);
        }
    }

    /**
     * Handles a change in the selected endpoint.
     * @param oldEndpoints Set of the old selected endpoints.
     * @param newEndpoints Set of the new selected endpoints.
     */
    private void selectedEndpointChanged(
            Set<String> oldEndpoints, Set<String> newEndpoints)
    {
        // Here we update the targetOrder value.

        if (logger.isDebugEnabled())
        {
            if (newEndpoints.isEmpty())
            {
                logger.debug("Now I'm not watching anybody. What?!");
            }
            else
            {
                StringBuilder sb = new StringBuilder("selected_endpoints,");
                sb.append(getLoggingId()).append(" selected=");
                for (String eId : newEndpoints)
                {
                    sb.append(eId);
                    sb.append(";");
                }
                logger.debug(Logger.Category.STATISTICS, sb.toString());
            }
        }

        SimulcastReceiver simulcastReceiver = getSimulcastReceiver();
        if (simulcastReceiver == null)
        {
            logger.warn(
                    "The simulcastReceiver has been garbage collected. "
                        + "This simulcastSender is now defunct.");
            return;
        }

        SimulcastStream[] simStreams = simulcastReceiver.getSimulcastStreams();
        if (simStreams == null || simStreams.length == 0)
        {
            logger.warn(
                    "The remote endpoint hasn't signaled simulcast. "
                        + "This simulcastSender is now disabled.");
            return;
        }

        Endpoint sendEndpoint = getSendEndpoint();
        // Send LQ stream for the previously selected endpoint.
        int lqOrder = SimulcastStream.SIMULCAST_LAYER_ORDER_BASE;
        int hqOrder = simStreams.length - 1;
        // XXX Practically, we should react once anyway. But since we have to if
        // statements, it is technically possible to react twice. Which is
        // unnecessary.

        int newTargetOrder;
        int oldTargetOrder = targetOrder;

        boolean thisWasInTheSelectedEndpoints
                = oldEndpoints.contains(sendEndpoint.getID());
        boolean thisWillBeInTheSelectedEndpoints
                = newEndpoints.contains(sendEndpoint.getID());

        if (thisWillBeInTheSelectedEndpoints)
        {
            int overrideOrder = getSimulcastSenderManager().getOverrideOrder();
            if (overrideOrder
                    == SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
            {
                newTargetOrder = hqOrder;
            }
            else
            {
                newTargetOrder = Math.min(hqOrder, overrideOrder);
            }
        }
        else if(thisWasInTheSelectedEndpoints)
        {
            // It was in the old selected endpoints but it is not present in the
            // new ones
            newTargetOrder = lqOrder;
        }
        else
        {
            newTargetOrder = oldTargetOrder;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug(getReceiveEndpoint().getID() + "/" +
                getSendEndpoint().getID() + " old endpoints: " + oldEndpoints);
            logger.debug(getReceiveEndpoint().getID() + "/" +
                getSendEndpoint().getID() + " new endpoints: " + newEndpoints);
            logger.debug(getReceiveEndpoint().getID() + "/" +
                getSendEndpoint().getID() + " oldTargetOrder=" + oldTargetOrder);
            logger.debug(getReceiveEndpoint().getID() + "/" +
                getSendEndpoint().getID() + " newTargetOrder=" + newTargetOrder);
        }


        if (oldTargetOrder != newTargetOrder)
        {
            targetOrder = newTargetOrder;

            SendMode sm = this.sendMode;
            if (sm != null)
            {
                retry = !this.sendMode.receive(newTargetOrder);
            }
        }
    }

    /**
     * Determines whether the caller must drop or accept a specific
     * {@code RawPacket}.
     *
     * @param pkt the <tt>RawPacket</tt> to accept or drop.
     * @return {@code true} to accept {@code pkt}; {@code false}, otherwise.
     */
    public boolean accept(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        assertInitialized();

        if (sendMode == null)
        {
            logger.debug("sendMode is null.");
            return true;
        }
        else
        {
            if (retry)
            {
                retry = !this.sendMode.receive(targetOrder);
            }
            return sendMode.accept(pkt);
        }
    }

    /**
     * Initializes this <tt>SimulcastSender</tt>.
     */
    private void assertInitialized()
    {
        if (initialized)
        {
            return;
        }

        initialized = true;

        SimulcastReceiver simulcastReceiver = getSimulcastReceiver();

        // We want to be notified when the simulcast streams of the sending
        // endpoint change. It will call the receiveStreamsChanged() method.
        simulcastReceiver.addWeakListener(
                new WeakReference<>(simulcastReceiverListener));

        // Manually trigger the receiveStreamsChanged() method so that we
        // initialize the send mode.
        VideoChannel sendVideoChannel
            = getSimulcastSenderManager().getSimulcastEngine().getVideoChannel();
        SimulcastMode simulcastMode = sendVideoChannel.getSimulcastMode();

        sendModeChanged(simulcastMode, null);

        // We want to be notified and react when the simulcast mode of the send
        // VideoChannel changes.
        sendVideoChannel.addPropertyChangeListener(propertyChangeListener);

        // We want to be notified and react when the selected endpoint has
        // changed at the client.
        receiveEndpointChanged(getReceiveEndpoint(), null);
    }

    /**
     * Notifies this instance about a change in the simulcast mode of the
     * sending <tt>VideoChannel</tt>. We keep this in a separate method for
     * readability and re-usability.
     *
     * @param newMode
     * @param oldMode
     */
    private void sendModeChanged(
        SimulcastMode newMode, SimulcastMode oldMode)
    {
        if (newMode == null)
        {
            // Now, why would you want to do that?
            sendMode = null;
            if (logger.isDebugEnabled())
            {
                logger.debug(Logger.Category.STATISTICS,
                             "set_send_mode," + getLoggingId()
                                 + " send_mode=null");
            }
            return;
        }
        else if (newMode == SimulcastMode.REWRITING)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(Logger.Category.STATISTICS,
                             "set_send_mode," + getLoggingId()
                                 + " send_mode=" + SimulcastMode.REWRITING
                                 .toString());
            }
            sendMode = new RewritingSendMode(this);
        }
        else if (newMode == SimulcastMode.SWITCHING)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(Logger.Category.STATISTICS,
                             "set_send_mode," + getLoggingId()
                                 + " send_mode=" + SimulcastMode.SWITCHING
                                 .toString());
            }
            sendMode = new SwitchingSendMode(this);
        }

        retry = !this.sendMode.receive(targetOrder);
    }

    /**
     * Notifies this instance that the <tt>Endpoint</tt> that receives the
     * simulcast has changed. We keep this in a separate method for readability
     * and re-usability.
     *
     * @param newValue
     * @param oldValue
     */
    private void receiveEndpointChanged(Endpoint newValue, Endpoint oldValue)
    {
        if (newValue != null)
        {
            newValue.addPropertyChangeListener(propertyChangeListener);
        }
        else
        {
            // This is acceptable when a participant leaves.
        }

        if (oldValue != null)
        {
            // Not strictly necessary since we're using a
            // WeakReferencePropertyChangeListener but why not.
            oldValue.removePropertyChangeListener(propertyChangeListener);
        }
    }

    /**
     * Notifies this instance that the overrider order of the parent
     * {@link SimulcastSenderManager} has changed.
     *
     */
    void overrideOrderChanged()
    {
        // FIXME: this may not be thread safe. Making it thread safe, though,
        // requires changes to the whole class.

        int overrideOrder = getSimulcastSenderManager().getOverrideOrder();
        // FIXME: this only drop the target order if the override is low. If
        // doesn't support clearing the override order (for this, we need to
        // reevaluate the list of selected endpoints).
        if (overrideOrder
            != SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            targetOrder = Math.min(targetOrder, overrideOrder);

            SendMode sm = this.sendMode;
            if (sm != null)
            {
                this.sendMode.receive(targetOrder);
            }
        }
    }

    /**
     * Implements a <tt>SimulcastReceiver.Listener</tt> to be used wit this
     * <tt>SimulcastSender</tt>.
     */
    class SimulcastReceiverListener
        implements SimulcastReceiver.Listener
    {
        @Override
        public void simulcastStreamsSignaled()
        {
            logger.debug("Handling simulcast signaling.");
            // Initialize the send mode.
            SimulcastMode simulcastMode = getSimulcastSenderManager()
                .getSimulcastEngine().getVideoChannel().getSimulcastMode();

            sendModeChanged(simulcastMode, null);
        }

        @Override
        public void simulcastStreamsChanged(SimulcastStream... simulcastStreams)
        {
            if (simulcastStreams == null || simulcastStreams.length == 0)
            {
                return;
            }

            SendMode sm = sendMode;
            if (sm == null)
            {
                return;
            }

            retry = !sm.receive(targetOrder);
        }
    }

    /**
     * @return a string which identifies this {@link SimulcastSender} for the
     * purposes of logging. The string is a comma-separated list of "key=value"
     * pairs.
     */
    public String getLoggingId()
    {
        // The conference and endpoint IDs should be sufficient for debugging.
        Endpoint receiveEndpoint = getReceiveEndpoint();
        Endpoint sendEndpoint = getSendEndpoint();
        Conference conference = null;
        if (receiveEndpoint != null)
        {
            conference = receiveEndpoint.getConference();
        }
        else if (sendEndpoint != null)
        {
            conference = sendEndpoint.getConference();
        }

        return Conference.getLoggingId(conference)
            + ",from_endp=" +
                (receiveEndpoint == null ? "null" : receiveEndpoint.getID())
            + ",to_endp="
                + (sendEndpoint == null ? "null" : sendEndpoint.getID());
    }
}
