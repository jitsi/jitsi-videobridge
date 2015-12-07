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
    extends PropertyChangeNotifier
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
     * Ctor.
     *
     * @param simulcastSenderManager the <tt>SimulcastSender</tt> that owns this
     * instance.
     * @param simulcastReceiver the associated <tt>SimulcastReceiver</tt>.
     */
    public SimulcastSender(
        SimulcastSenderManager simulcastSenderManager,
        SimulcastReceiver simulcastReceiver)
    {
        this.simulcastSenderManager = simulcastSenderManager;

        // We don't own the receiver, keep a weak reference so that it can be
        // garbage collected.
        this.weakSimulcastReceiver = new WeakReference<>(simulcastReceiver);
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
            logWarn("Self is null!");

            if (logger.isDebugEnabled())
            {
                logDebug(Arrays.toString(
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
            logWarn("The peer simulcast manager is null!");
            if (logger.isDebugEnabled())
            {
                logDebug(
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
            logWarn("Send endpoint is null!");

            if (logger.isDebugEnabled())
            {
                logDebug(Arrays.toString(
                    Thread.currentThread().getStackTrace()));
            }
        }

        return sendEndpoint;
    }

    private void react(boolean urgent)
    {
        SimulcastReceiver simulcastReceiver = getSimulcastReceiver();
        SimulcastStream closestMatch
            = simulcastReceiver.getSimulcastStream(targetOrder);
        sendMode.receive(closestMatch, urgent);
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

        if (SimulcastStream.IS_STREAMING_PNAME.equals(propertyName))
        {
            SimulcastStream l = (SimulcastStream) ev.getSource();
            boolean isUrgent = l == sendMode.getCurrent() && !l.isStreaming();
            react(isUrgent);
        }
        else if (SimulcastReceiver.SIMULCAST_LAYERS_PNAME.equals(propertyName))
        {
            logDebug("Handling streams change.");
            // The simulcast streams of the peer have changed, (re)attach.
            receiveStreamsChanged();
        }
        else if (Endpoint.SELECTED_ENDPOINT_PROPERTY_NAME.equals(propertyName)
            || Endpoint.PINNED_ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            // Here we update the targetOrder value.

            Endpoint oldEndpoint = (Endpoint) ev.getOldValue();
            Endpoint newEndpoint = (Endpoint) ev.getNewValue();

            if (newEndpoint == null)
            {
                logDebug("Now I'm not watching anybody. What?!");
            }
            else
            {
                logDebug("Now I'm watching " + newEndpoint.getID());
            }

            SimulcastReceiver simulcastReceiver = getSimulcastReceiver();
            if (simulcastReceiver == null)
            {
                logWarn("The simulcastReceiver has been garbage collected. " +
                        "This simulcastSender is now defunkt.");
                return;
            }

            SimulcastStream[] simStreams = simulcastReceiver.getSimulcastStreams();
            if (simStreams == null || simStreams.length == 0)
            {
                logWarn("The remote endpoint hasn't signaled simulcast. " +
                        "This simulcastSender is now disabled.");
                return;
            }

            int hqOrder = simStreams.length - 1;
            if (newEndpoint == getSendEndpoint() && targetOrder != hqOrder)
            {
                targetOrder = hqOrder;
                react(false);
                getSimulcastReceiver().maybeSendStartHighQualityStreamCommand();
            }

            // Send LQ stream for the previously selected endpoint.
            if (oldEndpoint == getSendEndpoint()
                && targetOrder != SimulcastStream.SIMULCAST_LAYER_ORDER_BASE)
            {
                targetOrder = SimulcastStream.SIMULCAST_LAYER_ORDER_BASE;
                react(false);
                getSimulcastReceiver().maybeSendStopHighQualityStreamCommand();
            }
        }
        else if (VideoChannel.SIMULCAST_MODE_PNAME.equals(propertyName))
        {
            logDebug("The simulcast mode has changed.");

            SimulcastMode oldMode = (SimulcastMode) ev.getOldValue();
            SimulcastMode newMode = (SimulcastMode) ev.getNewValue();

            sendModeChanged(newMode, oldMode);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(propertyName))
        {
            logDebug("The endpoint owner has changed.");

            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) ev.getNewValue();
            Endpoint oldValue = (Endpoint) ev.getOldValue();

            receiveEndpointChanged(newValue, oldValue);
        }
    }

    /**
     * Returns a boolean indicating whether the caller must drop, or accept, the
     * packet passed in as a parameter.
     *
     * @param pkt the <tt>RawPacket</tt> that needs to be accepted or dropped.
     * @return true if the packet is to be accepted, false otherwise.
     */
    public boolean accept(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        this.assertInitialized();

        if (sendMode == null)
        {
            logDebug("sendMode is null.");
        }

        return (sendMode != null) ? sendMode.accept(pkt) : null;
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
        // endpoint change. It will wall the {#receiveStreamsChanged()} method.
        simulcastReceiver.addPropertyChangeListener(propertyChangeListener);

        // Manually trigger the {#receiveStreamsChanged()} method so that w
        receiveStreamsChanged();

        VideoChannel sendVideoChannel = getSimulcastSenderManager()
            .getSimulcastEngine().getVideoChannel();
        // We want to be notified and react when the simulcast mode of the
        // send-<tt>VideoChannel</tt> changes.
        sendVideoChannel.addPropertyChangeListener(propertyChangeListener);

        // We want to be notified and react when the selected endpoint has
        // changed at the client.
        Endpoint receiveEndpoint = getReceiveEndpoint();
        receiveEndpointChanged(receiveEndpoint, null);
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
            // Now, what would you want to do that?
            sendMode = null;
        }
        else if (newMode == SimulcastMode.REWRITING)
        {
            sendMode = new RewritingSendMode(this);
        }
        else if (newMode == SimulcastMode.SWITCHING)
        {
            sendMode = new SwitchingSendMode(this);
        }

        if (sendMode != null)
        {
            react(false);
        }
    }

    /**
     * Notifies this instance about a change in the simulcast streams of the
     * associated peer. We keep this in a separate method for readability and
     * re-usability.
     */
    private void receiveStreamsChanged()
    {
        SimulcastReceiver simulcastReceiver = getSimulcastReceiver();
        if (simulcastReceiver == null || !simulcastReceiver.isSimulcastSignaled())
        {
            return;
        }

        for (SimulcastStream simStream : simulcastReceiver.getSimulcastStreams())
        {
            // Add listener from the current receiving simulcast streams.
            simStream.addPropertyChangeListener(propertyChangeListener);
        }

        // Initialize the send mode.
        SimulcastMode simulcastMode = getSimulcastSenderManager()
            .getSimulcastEngine().getVideoChannel().getSimulcastMode();

        sendModeChanged(simulcastMode, null);
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
            logWarn("Cannot listen on self, it's null!");
            if (logger.isDebugEnabled())
            {
                logDebug(Arrays.toString(
                    Thread.currentThread().getStackTrace()));
            }
        }

        if (oldValue != null)
        {
            // Not strictly necessary since we're using a
            // WeakReferencePropertyChangeListener but why not.
            oldValue.removePropertyChangeListener(propertyChangeListener);
        }
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg = getReceiveEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg = getReceiveEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }
}
