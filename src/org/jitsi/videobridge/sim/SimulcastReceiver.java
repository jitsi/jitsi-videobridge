/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import java.beans.*;
import java.io.*;
import java.lang.ref.*;
import java.util.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.sim.messages.*;

/**
* @author George Politis
*/
class SimulcastReceiver
        implements PropertyChangeListener
{
    /**
     * Ctor.
     *
     * @param mySM
     * @param peerSM
     */
    public SimulcastReceiver(SimulcastManager mySM, SimulcastManager peerSM)
    {
        this.weakPeerSM = new WeakReference<SimulcastManager>(peerSM);
        this.mySM = mySM;

        // Listen for property changes.
        peerSM.addPropertyChangeListener(weakPropertyChangeListener);
        onPeerLayersChanged(peerSM);

        mySM.getVideoChannel()
                .addPropertyChangeListener(weakPropertyChangeListener);

        Endpoint self = getSelf();
        onEndpointChanged(self, null);
    }

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastReceiver.class);

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to <tt>Conference</tt> in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s.
     */
    private final PropertyChangeListener weakPropertyChangeListener
            = new WeakReferencePropertyChangeListener(this);

    /**
     * The <tt>SimulcastManager</tt> of the parent endpoint.
     */
    private final SimulcastManager mySM;

    /**
     * The <tt>SimulcastManager</tt> of the peer endpoint.
     */
    private final WeakReference<SimulcastManager> weakPeerSM;

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
     * The sync root object for synchronizing access to the receive layers.
     */
    private final Object receiveLayersSyncRoot = new Object();

    /**
     * Holds the number of packets of the next layer have been seen so far.
     */
    private int seenNext;

    /**
     * Defines how many packets of the next layer must be seen before switching
     * to that layer.
     */
    private static final int MAX_NEXT_SEEN = 250; // < 5 seconds approx.

    /**
     * Helper object that <tt>SimulcastReceiver</tt> instances use to build
     * JSON messages.
     */
    private final static SimulcastMessagesMapper mapper
            = new SimulcastMessagesMapper();

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
     *
     * @param ssrc
     * @return
     */
    public boolean accept(long ssrc)
    {
        boolean accept = false;

        SimulcastLayer current = getCurrent();
        if (current != null)
        {
            accept = current.accept(ssrc);
        }

        SimulcastLayer next;
        if (!accept && (next = getNext()) != null)
        {
            accept = next.accept(ssrc);
            if (accept)
            {
                maybeSwitchToNext();
            }
        }

        return accept;
    }

    private void maybeForgetNext()
    {
        SimulcastLayer next = getNext();
        if (next != null && !next.isStreaming())
        {
            synchronized (receiveLayersSyncRoot)
            {
                this.weakNext = null;
                this.seenNext = 0;
            }
        }
    }

    /**
     *
     */
    private void maybeSwitchToNext()
    {
        SimulcastLayer next = getNext();

        // If there is a previous layer to timeout, and we have received
        // "enough" packets from the current layer, expire the previous
        // layer.
        if (next != null)
        {
            synchronized (receiveLayersSyncRoot)
            {
                seenNext++;
                if (seenNext > MAX_NEXT_SEEN)
                {
                    this.sendSimulcastLayersChangedEvent(next);

                    this.weakCurrent = weakNext;
                    this.weakNext = null;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} has now switched to " +
                                "the next layer of order {next.order} " +
                                "({next.primarySSRC})."));
                    }
                }
            }
        }
    }

    private Endpoint getPeer()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastManager sm;
        VideoChannel vc;
        Endpoint peer;

        peer = ((sm = getPeerSM()) != null && (vc = sm.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (peer == null)
        {
            logger.warn("Peer is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return peer;
    }

    private SimulcastManager getPeerSM()
    {
        WeakReference<SimulcastManager> wr = this.weakPeerSM;
        SimulcastManager peerSM = (wr != null) ? wr.get() : null;

        if (peerSM == null)
        {
            logger.warn("The peer simulcast manager is null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        Arrays.toString(
                                Thread.currentThread().getStackTrace()));
            }
        }

        return peerSM;
    }

    private Endpoint getSelf()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastManager sm = this.mySM;
        VideoChannel vc;
        Endpoint self;

        self = (sm != null && (vc = sm.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (self == null)
        {
            logger.warn("Self is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return self;
    }

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param options
     */
    protected void configure(SimulcastReceiverOptions options)
    {
        if (options == null)
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{self.id} cannot set receiving simulcast " +
                        "options because the parameter is null."));
            }

            return;
        }

        SimulcastManager peerSM = this.getPeerSM();

        if (peerSM == null || !peerSM.hasLayers())
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{self.id} hasn't any simulcast layers."));
            }

            return;
        }

        SimulcastLayer next
                = peerSM.getSimulcastLayer(options.getTargetOrder());

        // Do NOT switch to hq if it's not streaming.
        if (next == null
                || (next.getOrder()
                != SimulcastManager.SIMULCAST_LAYER_ORDER_LQ
                && !next.isStreaming()))
        {
            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("{self.id} ignoring request to switch to " +
                        "higher order layer because it is not currently " +
                        "being streamed."));
            }
            return;
        }

        SimulcastLayer current = getCurrent();

        // Do NOT switch to an already receiving layer.
        if (current == next)
        {
            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(4);
                map.put("self", getSelf());
                map.put("peer", getPeer());
                map.put("current", current);
                map.put("next", next);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The simulcast receiver of {self.id} for " +
                        "{peer.id} already receives layer {next.order} " +
                        "({next.primarySSRC})."));
            }

            return;
        }
        else
        {
            // If current has changed, request an FIR, notify the parent
            // endpoint and change the receiving streams.

            if (options.isHardSwitch() && next != getNext())
            {
                // XXX(gp) run these in the event dispatcher thread?

                // Send FIR requests first.
                this.askForKeyframe(next);
            }

            synchronized (receiveLayersSyncRoot)
            {
                if (options.isUrgent() || current == null)
                {
                    // Receiving simulcast layers have brutally changed. Create
                    // and send an event through data channels to the receiving
                    // endpoint.
                    this.sendSimulcastLayersChangedEvent(next);

                    this.weakCurrent = new WeakReference<SimulcastLayer>(next);
                    this.weakNext = null;

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(4);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        map.put("urgently", options.isUrgent()
                                ? "urgently" : "");
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver " +
                                "of {self.id} for {peer.id} has {urgently} " +
                                "switched to layer {next.order} " +
                                "({next.primarySSRC}).").toString()
                                .replaceAll("\\s+", " "));
                    }
                }
                else
                {
                    // Receiving simulcast layers are changing, create and send
                    // an event through data channels to the receiving endpoint.
                    this.sendSimulcastLayersChangingEvent(next);

                    // If the layer we receive has changed (hasn't dropped),
                    // then continue streaming the previous layer for a short
                    // period of time while the client receives adjusts its
                    // video.
                    this.weakNext = new WeakReference<SimulcastLayer>(next);

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} is going to switch " +
                                "to layer {next.order} ({next.primarySSRC}) " +
                                "in a few moments.."));
                    }
                }
            }
        }

    }

    private void askForKeyframe(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested a key frame for null layer!");
            return;
        }

        SimulcastManager peerSM = getPeerSM();
        if (peerSM == null)
        {
            logger.warn("Requested a key frame but the peer simulcast " +
                    "manager is null!");
            return;
        }

        peerSM.getVideoChannel().askForKeyframes(
                new int[]{(int) layer.getPrimarySSRC()});

        if (logger.isDebugEnabled())
        {
            Map<String, Object> map = new HashMap<String, Object>(3);
            map.put("self", getSelf());
            map.put("peer", getPeer());
            map.put("layer", layer);
            StringCompiler sc = new StringCompiler(map);

            logger.debug(sc.c("The simulcast receiver of {self.id} for " +
                    "{peer.id} has asked for a key frame for layer " +
                    "{layer.order} ({layer.primarySSRC})."));
        }
    }

    private void sendSimulcastLayersChangedEvent(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changed event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changed event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent ev
                    = new SimulcastLayersChangedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }

    }

    private void sendSimulcastLayersChangingEvent(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changing event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changing event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving
            // endpoint.
            SimulcastLayersChangingEvent ev
                    = new SimulcastLayersChangingEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changing event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        if (SimulcastLayer.IS_STREAMING_PROPERTY
                .equals(propertyChangeEvent.getPropertyName()))
        {
            // A remote simulcast layer has either started or stopped
            // streaming. Deal with it.
            SimulcastLayer layer
                    = (SimulcastLayer) propertyChangeEvent.getSource();

            onPeerLayerChanged(layer);
        }
        else if (Endpoint
                .SELECTED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()))
        {
            // endpoint == this.manager.getVideoChannel().getEndpoint() is
            // implied.

            String oldValue = (String) propertyChangeEvent.getOldValue();
            String newValue = (String) propertyChangeEvent.getNewValue();

            onSelectedEndpointChanged(oldValue, newValue);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) propertyChangeEvent.getNewValue();
            Endpoint oldValue = (Endpoint) propertyChangeEvent.getOldValue();

            onEndpointChanged(newValue, oldValue);
        }
        else if (SimulcastManager.SIMULCAST_LAYERS_PROPERTY.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // The simulcast layers of the peer have changed, (re)attach.
            SimulcastManager peerSM
                    = (SimulcastManager) propertyChangeEvent.getSource();

            onPeerLayersChanged(peerSM);
        }
    }

    private void onPeerLayersChanged(SimulcastManager peerSM)
    {
        if (peerSM != null && peerSM.hasLayers())
        {
            for (SimulcastLayer layer : peerSM.getSimulcastLayers())
            {
                // Add listener from the current receiving simulcast layers.
                layer.addPropertyChangeListener(weakPropertyChangeListener);
            }

            // normally getPeer() == peerSM.getVideoChannel().getEndpoint()
            // holds.

            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(2);
                map.put("self", getSelf());
                map.put("peer", getPeer());
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("{self.id} listens on layer changes from " +
                        "{peer.id}."));
            }
        }
    }

    private void onPeerLayerChanged(SimulcastLayer layer)
    {
        if (!layer.isStreaming())
        {
            // HQ stream has stopped, switch to a lower quality stream.

            SimulcastReceiverOptions options = new SimulcastReceiverOptions();

            options.setTargetOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_LQ);
            options.setHardSwitch(true);
            options.setUrgent(true);

            configure(options);

            maybeForgetNext();
        }
        else
        {
            Endpoint self = getSelf();
            Endpoint peer = getPeer();

            if (peer != null && self != null &&
                    peer.getID().equals(self.getSelectedEndpointID()))
            {
                SimulcastReceiverOptions options
                        = new SimulcastReceiverOptions();

                options.setTargetOrder(
                        SimulcastManager.SIMULCAST_LAYER_ORDER_HQ);
                options.setHardSwitch(false);
                options.setUrgent(false);

                configure(options);
            }
        }
    }

    private void onSelectedEndpointChanged(
            String oldValue, String newValue)
    {
        synchronized (receiveLayersSyncRoot)
        {
            // Rule 1: send an hq stream only for the selected endpoint.
            if (this.maybeReceiveHighFrom(newValue))
            {
                // Rule 1.1: if the new endpoint is being watched by any of the
                // receivers, the bridge tells it to start streaming its hq
                // stream.
                this.maybeSendStartHighQualityStreamCommand(newValue);
            }

            // Rule 2: send an lq stream only for the previously selected
            // endpoint.
            if (this.maybeReceiveLowFrom(oldValue))
            {
                // Rule 2.1: if the old endpoint is not being watched by any of
                // the receivers, the bridge tells it to stop streaming its hq
                // stream.
                this.maybeSendStopHighQualityStreamCommand(oldValue);
            }
        }
    }

    private void onEndpointChanged(Endpoint newValue, Endpoint oldValue)
    {
        if (newValue != null)
        {
            newValue.addPropertyChangeListener(weakPropertyChangeListener);
        }
        else
        {
            logger.warn("Cannot listen on self, it's null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        if (oldValue != null)
        {
            oldValue.removePropertyChangeListener(weakPropertyChangeListener);
        }
    }

    /**
     *
     * @param id
     */
    private boolean maybeReceiveHighFrom(String id)
    {
        Endpoint peer;
        if (!StringUtils.isNullOrEmpty(id)
                && (peer = getPeer()) != null
                && id.equals(peer.getID()))
        {
            SimulcastReceiverOptions options = new SimulcastReceiverOptions();

            options.setTargetOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_HQ);
            options.setHardSwitch(true);
            options.setUrgent(false);

            configure(options);

            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Configures the simulcast manager of the receiver to receive a high
     * quality stream only from the designated sender.
     *
     * @param id
     */
    private boolean maybeReceiveLowFrom(String id)
    {
        Endpoint peer;
        if (!StringUtils.isNullOrEmpty(id)
                && (peer = getPeer()) != null
                && id.equals(peer.getID()))
        {
            SimulcastReceiverOptions options = new SimulcastReceiverOptions();

            options.setTargetOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_LQ);
            options.setHardSwitch(true);
            options.setUrgent(false);

            configure(options);

            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Sends a data channel command to a simulcast enabled video sender to make
     * it stop streaming its hq stream, if it's not being watched by any
     * receiver.
     *
     * @param id
     */
    private void maybeSendStopHighQualityStreamCommand(String id)
    {
        Endpoint oldEndpoint = null;
        if (!StringUtils.isNullOrEmpty(id) &&
                id != Endpoint.SELECTED_ENDPOINT_NOT_WATCHING_VIDEO)
        {
            oldEndpoint = this.mySM.getVideoChannel()
                    .getContent()
                    .getConference()
                    .getEndpoint(id);
        }

        List<RtpChannel> oldVideoChannels = null;
        if (oldEndpoint != null)
        {
            oldVideoChannels = oldEndpoint.getChannels(MediaType.VIDEO);
        }

        VideoChannel oldVideoChannel = null;
        if (oldVideoChannels != null && oldVideoChannels.size() != 0)
        {
            oldVideoChannel = (VideoChannel) oldVideoChannels.get(0);
        }

        SortedSet<SimulcastLayer> oldSimulcastLayers = null;
        if (oldVideoChannel != null)
        {
            oldSimulcastLayers = oldVideoChannel.getSimulcastManager()
                    .getSimulcastLayers();
        }

        if (oldSimulcastLayers != null
                && oldSimulcastLayers.size() > 1
                /* oldEndpoint != null is implied*/
                && oldEndpoint.getSctpConnection().isReady()
                && !oldEndpoint.getSctpConnection().isExpired())
        {
            // we have an old endpoint and it has an SCTP connection that is
            // ready and not expired. if nobody else is watching the old
            // endpoint, stop its hq stream.

            boolean stopHighQualityStream = true;
            for (Endpoint e : this.mySM.getVideoChannel()
                    .getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the selected
                // endpoint changes while we're in the loop?

                if (oldEndpoint != e
                        && (oldEndpoint.getID().equals(e.getSelectedEndpointID())
                        || StringUtils.isNullOrEmpty(e.getSelectedEndpointID()))
                        )
                {
                    // somebody is watching the old endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, don't
                    // stop the hq stream.
                    stopHighQualityStream = false;
                    break;
                }
            }

            if (stopHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(this.mySM.getVideoChannel().getEndpoint().getID() +
                        " notifies " + oldEndpoint.getID() + " to stop " +
                        "its HQ stream.");

                SimulcastLayer hqLayer = oldSimulcastLayers.last();

                StopSimulcastLayerCommand command
                        = new StopSimulcastLayerCommand(hqLayer);

                String json = mapper.toJson(command);

                try
                {
                    oldEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e1)
                {
                    logger.error(oldEndpoint.getID() + " failed to send " +
                            "message on data channel.", e1);
                }
            }
        }
    }

    /**
     * Sends a data channel command to a simulcast enabled video sender to make
     * it start streaming its hq stream, if it's being watched by some receiver.
     *
     * @param id
     */
    private void maybeSendStartHighQualityStreamCommand(String id)
    {
        Endpoint newEndpoint = null;
        if (!StringUtils.isNullOrEmpty(id) &&
                id != Endpoint.SELECTED_ENDPOINT_NOT_WATCHING_VIDEO)
        {
            newEndpoint = this.mySM.getVideoChannel()
                    .getContent().getConference().getEndpoint(id);
        }

        List<RtpChannel> newVideoChannels = null;
        if (newEndpoint != null)
        {
            newVideoChannels = newEndpoint.getChannels(MediaType.VIDEO);
        }

        VideoChannel newVideoChannel = null;
        if (newVideoChannels != null && newVideoChannels.size() != 0)
        {
            newVideoChannel = (VideoChannel) newVideoChannels.get(0);
        }

        SortedSet<SimulcastLayer> newSimulcastLayers = null;
        if (newVideoChannel != null)
        {
            newSimulcastLayers = newVideoChannel.getSimulcastManager()
                    .getSimulcastLayers();
        }

        if (newSimulcastLayers != null
                && newSimulcastLayers.size() > 1
                /* newEndpoint != null is implied*/
                && newEndpoint.getSctpConnection().isReady()
                && !newEndpoint.getSctpConnection().isExpired())
        {
            // we have a new endpoint and it has an SCTP connection that is
            // ready and not expired. if somebody else is watching the new
            // endpoint, start its hq stream.

            boolean startHighQualityStream = false;
            for (Endpoint e : this.mySM.getVideoChannel()
                    .getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the
                // selected endpoint changes while we're in the loop?

                if (newEndpoint != e
                        && (newEndpoint.getID().equals(
                        e.getSelectedEndpointID())
                        || (SimulcastManager.SIMULCAST_LAYER_ORDER_INIT
                        > SimulcastManager.SIMULCAST_LAYER_ORDER_LQ
                        && StringUtils.isNullOrEmpty(
                        e.getSelectedEndpointID())))
                        )
                {
                    // somebody is watching the new endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, start
                    // the hq stream.

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("e", e);
                        map.put("newEndpoint", newEndpoint);
                        map.put("maybe", StringUtils.isNullOrEmpty(
                                e.getSelectedEndpointID()) ?
                                "(maybe) " : "");
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("{e.id} is {maybe} watching " +
                                "{newEndpoint.id}.")
                                .toString().replaceAll("\\s+", " "));
                    }

                    startHighQualityStream = true;
                    break;
                }
            }

            if (startHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(this.mySM.getVideoChannel().getEndpoint().getID() +
                        " notifies " + newEndpoint.getID() + " to start " +
                        "its HQ stream.");

                SimulcastLayer hqLayer = newSimulcastLayers.last();

                StartSimulcastLayerCommand command
                        = new StartSimulcastLayerCommand(hqLayer);

                String json = mapper.toJson(command);
                try
                {
                    newEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e1)
                {
                    logger.error(newEndpoint.getID() + " failed to send " +
                            "message on data channel.", e1);
                }
            }
        }
    }
}
