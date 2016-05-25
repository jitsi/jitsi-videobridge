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
 * @author Boris Grozev
 */
public class SimulcastSender
    implements PropertyChangeListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingStreams</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
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
     * @return the target order of this {@link SimulcastSender}.
     */
    public int getTargetOrder()
    {
        return targetOrder;
    }

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

        // Initialize the send mode early (before the first call to accept()),
        // so that the it registers as a user for the stream(s) it wants to
        // stream.
        initializeSendMode();
        assertInitialized();
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
            = simulcastReceiver.getVideoChannel()
                    .getTransformEngine().getSimulcastEngine();

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

        if (Endpoint.SELECTED_ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            Set<Endpoint> oldEndpoints = (Set<Endpoint>) ev.getOldValue();
            Set<Endpoint> newEndpoints = (Set<Endpoint>) ev.getNewValue();

            selectedEndpointsChanged(oldEndpoints, newEndpoints);
        }
        else if (VideoChannel.SIMULCAST_MODE_PNAME.equals(propertyName))
        {
            logger.debug("The simulcast mode has changed.");

            SimulcastMode newMode = (SimulcastMode) ev.getNewValue();

            setSimulcastMode(newMode);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            logger.debug("The endpoint owner has changed.");

            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) ev.getNewValue();
            Endpoint oldValue = (Endpoint) ev.getOldValue();

            receiveEndpointChanged(oldValue, newValue);
        }
    }

    /**
     * Handles a change in the selected endpoint.
     * @param oldEndpoints Set of the old selected endpoints.
     * @param newEndpoints Set of the new selected endpoints.
     */
    void selectedEndpointsChanged(
            Set<Endpoint> oldEndpoints, Set<Endpoint> newEndpoints)
    {
        // Here we update the targetOrder value.

        if (logger.isDebugEnabled())
        {
            if (newEndpoints.isEmpty())
                logger.debug("Now I'm not watching anybody. What?!");
            else
            {
                StringBuilder newEndpointsIDList = new StringBuilder();
                for (Endpoint e : newEndpoints)
                {
                    newEndpointsIDList.append(e.getID());
                    newEndpointsIDList.append(", ");
                }
                logger.debug(getReceiveEndpoint().getID() + " now I'm watching: "
                        + newEndpointsIDList.toString());
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
                = oldEndpoints.contains(sendEndpoint);
        boolean thisWillBeInTheSelectedEndpoints
                = newEndpoints.contains(sendEndpoint);

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

            SendMode sm = sendMode;
            if (sm != null)
            {
                sendMode.receive(newTargetOrder);
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

        if (sendMode == null)
        {
            logger.debug("sendMode is null.");
            return true;
        }
        else
        {
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
        // endpoint change.
        simulcastReceiver.addWeakListener(
                new WeakReference<>(simulcastReceiverListener));

        VideoChannel sendVideoChannel
            = getSimulcastSenderManager().getSimulcastEngine().getVideoChannel();

        // We want to be notified and react when the simulcast mode of the send
        // VideoChannel changes.
        sendVideoChannel.addPropertyChangeListener(propertyChangeListener);

        // We want to be notified and react when the selected endpoint has
        // changed at the client.
        receiveEndpointChanged(null, getReceiveEndpoint());
    }

    /**
     * Sets {@link #sendMode} in accord to {@code newMode}.
     */
    private void setSimulcastMode(SimulcastMode newMode)
    {
        SendMode oldSendMode = sendMode;
        boolean changed = false;

        if (newMode == null)
        {
            sendMode = null;
            logger.debug("Setting simulcastMode to null.");
            changed = oldSendMode != null;
        }
        else if (newMode == SimulcastMode.REWRITING)
        {
            if (oldSendMode == null ||
                !(oldSendMode instanceof RewritingSendMode))
            {
                logger.debug("Setting simulcastMode to rewriting mode.");
                sendMode = new RewritingSendMode(this);
                changed = true;
            }
        }
        else if (newMode == SimulcastMode.SWITCHING)
        {
            if (oldSendMode == null ||
                !(oldSendMode instanceof SwitchingSendMode))
            {
                logger.debug("Setting simulcastMode to switching mode.");
                sendMode = new SwitchingSendMode(this);
                changed = true;
            }
        }

        if (changed && oldSendMode != null)
        {
            oldSendMode.free();
        }

        if (sendMode != null)
        {
            sendMode.receive(targetOrder);
        }
    }

    /**
     * Notifies this instance that the <tt>Endpoint</tt> that receives the
     * simulcast has changed. We keep this in a separate method for readability
     * and re-usability.
     */
    private void receiveEndpointChanged(Endpoint oldValue, Endpoint newValue)
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
     * Notifies this instance that the overridden order of the parent
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
     * Initializes the send mode if this sender with the more configured for
     * the owning {@link VideoChannel}.
     */
    private void initializeSendMode()
    {
        logger.debug("Handling simulcast signaling.");
        // Initialize the send mode.
        SimulcastMode simulcastMode = getSimulcastSenderManager()
            .getSimulcastEngine().getVideoChannel().getSimulcastMode();

        setSimulcastMode(simulcastMode);
    }

    /**
     * Closes this {@link SimulcastSender} releasing its resources.
     */
    public void close()
    {
        setSimulcastMode(null);
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
            // XXX FIXME: we need to re-evaluate the target order based on the
            // selected endpoints.
            if (sendMode != null)
                sendMode.receive(targetOrder);
        }

        @Override
        public void simulcastStreamsChanged()
        {
            SendMode sm = sendMode;
            if (sm == null)
            {
                return;
            }

            sm.receive(targetOrder);
        }
    }
}
