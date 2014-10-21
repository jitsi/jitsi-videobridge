/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.sim.messages.*;

/**
 * The simulcast manager of a <tt>VideoChannel</tt>.
 *
 * @author George Politis
 */
public class SimulcastManager
    extends PropertyChangeNotifier
    implements PropertyChangeListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastManager.class);

    private static final String SIMULCAST_LAYERS_PROPERTY =
            SimulcastManager.class.getName() + ".simulcastLayers";

    /**
     * Base layer quality order.
     */
    private static final int SIMULCAST_LAYER_ORDER_LQ = 0;

    /**
     * High quality layer order.
     */
    private static final int SIMULCAST_LAYER_ORDER_HQ = 1;

    /**
     * The associated <tt>VideoChannel</tt> of this simulcast manager.
     */
    private final VideoChannel videoChannel;

    /**
     * Defines the simulcast substream to receive, if there is no other
     */
    private static final Integer SIMULCAST_LAYER_ORDER_INIT
            = SIMULCAST_LAYER_ORDER_LQ;

    /**
     * The <tt>simulcastLayers</tt> SyncRoot.
     */
    private final Object simulcastLayersSyncRoot = new Object();

    /**
     * The simulcast layers of this <tt>VideoChannel</tt>.
     */
    private SortedSet<SimulcastLayer> simulcastLayers;

    /**
     * Associates sending endpoints to receiving simulcast layer. This simulcast
     * manager uses this map to determine whether or not to forward a video RTP
     * packet to its associated endpoint or not.  An entry in a this map will
     * automatically be removed when its key is no longer in ordinary use.
     */
    private final Map<Endpoint, ReceivingLayers> simLayersMap
            = new WeakHashMap<Endpoint, ReceivingLayers>();

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to <tt>Conference</tt> in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s.
     */
    private final PropertyChangeListener propertyChangeListener
            = new WeakReferencePropertyChangeListener(this);

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     * @param p the <tt>DatagramPacket</tt> received on the data
     * <tt>DatagramSocket</tt> of this <tt>Channel</tt>
     */
    public void acceptedDataInputStreamDatagramPacket(DatagramPacket p)
    {
        // With native simulcast we don't have a notification when a stream
        // has started/stopped. The simulcast manager implements a timeout
        // for the high quality stream and it needs to be notified when
        // the channel has accepted a datagram packet for the timeout to
        // function correctly.

        if (hasLayers() && p != null)
        {
            int acceptedSSRC = readSSRC(p.getData(), p.getOffset(),
                    p.getLength());

            SortedSet<SimulcastLayer> layers = null;
            SimulcastLayer acceptedLayer = null;

            if (acceptedSSRC != 0)
            {
                layers = getSimulcastLayers();

                // Find the accepted layer.
                for (SimulcastLayer layer : layers)
                {
                    if ((int) layer.getPrimarySSRC() == acceptedSSRC)
                    {
                        acceptedLayer = layer;
                        break;
                    }
                }
            }

            // If this is not an RTP packet or if we can't find an accepted
            // layer, log and return as this situation makes no sense.
            if (acceptedLayer == null)
            {
                return;
            }

            // NOTE(gp) we expect the base layer to be always on, so we never
            // touch it or starve it.

            if (acceptedLayer == layers.first())
            {
                // We have accepted a base layer packet, starve the higher
                // quality layers.
                for (SimulcastLayer layer : layers)
                {
                    if (acceptedLayer != layer)
                    {
                        layer.maybeTimeout();
                    }
                }
            }
            else
            {
                // We have accepted a non-base layer packet, touch the accepted
                // layer.
                acceptedLayer.touch();
            }
        }
    }

    public SimulcastManager(VideoChannel videoChannel)
    {
        this.videoChannel = videoChannel;
        this.videoChannel.addPropertyChangeListener(propertyChangeListener);
    }

    /**
     * Determines whether the packet belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @return
     */
    public boolean acceptSimulcastLayer(byte[] buffer, int offset, int length,
                                        VideoChannel sourceVideoChannel)
    {
        boolean accept = true;

        SimulcastManager simulcastManager;
        if (sourceVideoChannel != null
                && (simulcastManager = sourceVideoChannel.getSimulcastManager())
                != null
                && simulcastManager.hasLayers())
        {

            // FIXME(gp) inconsistent usage of longs and ints.

            // Get the SSRC of the packet.
            long ssrc = readSSRC(buffer, offset, length) & 0xffffffffl;

            if (ssrc > 0)
            {
                accept = acceptSimulcastLayer(ssrc, sourceVideoChannel);
            }
        }

        return accept;
    }

    /**
     * Determines whether the SSRC belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @param srcVideoChannel
     * @param ssrc
     * @return
     */
    public boolean acceptSimulcastLayer(long ssrc,
                                        VideoChannel srcVideoChannel)
    {
        boolean accept = true;

        SimulcastManager simulcastManager;
        if (ssrc > 0
                && srcVideoChannel != null
                && (simulcastManager = srcVideoChannel.getSimulcastManager())
                != null
                && simulcastManager.hasLayers())
        {
            ReceivingLayers receivingLayers
                    = getOrElectReceivingLayers(srcVideoChannel);

            if (receivingLayers != null)
            {
                accept = receivingLayers.accept(ssrc);
            }
        }

        return accept;
    }

    /**
     * Returns true if the endpoint has signaled two or more simulcast layers.
     *
     * @return
     */
    private boolean hasLayers()
    {
        synchronized (simulcastLayersSyncRoot)
        {
            return simulcastLayers != null
                    && simulcastLayers.size() > 1;
        }
    }

    /**
     * Determines which simulcast layer from the srcVideoChannel is currently
     * being received by this video channel.
     *
     * @param srcVideoChannel
     * @return
     */
    private ReceivingLayers getOrElectReceivingLayers(VideoChannel srcVideoChannel)
    {
        ReceivingLayers electedSimulcastLayer = null;

        SimulcastManager simulcastManager;
        if (srcVideoChannel == null
                || (simulcastManager = srcVideoChannel.getSimulcastManager())
                == null
                || !simulcastManager.hasLayers())
        {
            return electedSimulcastLayer;
        }

        Endpoint sourceEndpoint = srcVideoChannel.getEndpoint();
        if (sourceEndpoint == null)
        {
            return electedSimulcastLayer;
        }

        synchronized (simulcastLayersSyncRoot)
        {
            if (!simLayersMap.containsKey(sourceEndpoint))
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(new StringBuilder()
                            .append(getVideoChannel().getEndpoint().getID())
                            .append(" elects initial simulcast layer for ")
                            .append(srcVideoChannel.getEndpoint().getID())
                            .append("."));
                }

                Map<Endpoint, ReceivingSimulcastOptions> endpointsQualityMap
                        = new HashMap<Endpoint, ReceivingSimulcastOptions>(1);

                ReceivingSimulcastOptions options =
                        new ReceivingSimulcastOptions(
                                SIMULCAST_LAYER_ORDER_INIT, true);

                endpointsQualityMap.put(sourceEndpoint, options);

                setReceivingSimulcastLayer(endpointsQualityMap);
            }

            electedSimulcastLayer = simLayersMap.get(sourceEndpoint);
        }

        return electedSimulcastLayer;
    }

    private final static SimulcastMessagesMapper mapper
            = new SimulcastMessagesMapper();

    public void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers)
    {
        synchronized (simulcastLayersSyncRoot)
        {
            this.simulcastLayers = simulcastLayers;
        }

        // FIXME(gp) use an event dispatcher.
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                firePropertyChange(SIMULCAST_LAYERS_PROPERTY, null, null);
            }
        }).start();

        if (logger.isDebugEnabled() && videoChannel.getEndpoint() != null)
        {
            logger.debug(new StringBuilder()
                    .append(videoChannel.getEndpoint().getID())
                    .append(" signals ")
                    .append(mapper.toJson(simulcastLayers)));
        }
    }

    /**
     * Gets the simulcast layers of this simulcast manager.
     *
     * @return
     */
    public SortedSet<SimulcastLayer> getSimulcastLayers()
    {
        synchronized (simulcastLayersSyncRoot)
        {
            return simulcastLayers == null
                    ? null : new TreeSet<SimulcastLayer>(simulcastLayers);
        }
    }

    public VideoChannel getVideoChannel()
    {
        return videoChannel;
    }

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param endpointsQualityMap
     */
    public void setReceivingSimulcastLayer(
            Map<Endpoint, ReceivingSimulcastOptions> endpointsQualityMap)
    {
        // TODO(gp) maybe add expired check (?)
        Endpoint self = videoChannel.getEndpoint();
        if (self == null)
        {
            logger.warn("Cannot set receiving simulcast layers because the " +
                    "channel endpoint not been set yet.");
            return;
        }

        if (endpointsQualityMap == null || endpointsQualityMap.isEmpty())
        {
            logger.warn(self.getID() + " cannot set receiving simulcast " +
                    "layers with an empty endpoints quality map.");
            return;
        }

        // Used later on to register listeners.
        Map<Endpoint, SimulcastLayer> endpointMap
                = new HashMap<Endpoint, SimulcastLayer>(
                        endpointsQualityMap.size());

        // Used later on to send FIR requests.
        Map<RtpChannel, SimulcastLayer> channelMap
                = new HashMap<RtpChannel, SimulcastLayer>(endpointsQualityMap.size());

        // Used later on to send data channel messages.
        List<EndpointSimulcastLayer> endpointSimulcastLayers
                = new ArrayList<EndpointSimulcastLayer>(
                        endpointsQualityMap.size());

        // Build the maps/lists.
        for (Map.Entry<Endpoint, ReceivingSimulcastOptions> entry
                : endpointsQualityMap.entrySet())
        {
            int targetOrder = entry.getValue().getTargetOrder();
            boolean hardSwitch = entry.getValue().isHardSwitch();

            Endpoint peer = entry.getKey();
            if (peer == self)
                continue;

            List<RtpChannel> rtpChannels = peer.getChannels(MediaType.VIDEO);

            if (rtpChannels == null || rtpChannels.isEmpty())
            {
                logger.warn(self.getID() + " cannot set receiving simulcast " +
                        "layers because the peer has no RTP channels.");
                continue;
            }

            for (RtpChannel rtpChannel : rtpChannels)
            {
                if (!(rtpChannel instanceof VideoChannel))
                    continue;

                VideoChannel sourceVideoChannel = (VideoChannel) rtpChannel;

                SimulcastManager sourceManager =
                        sourceVideoChannel.getSimulcastManager();

                if (!sourceManager.hasLayers())
                    continue;

                SortedSet<SimulcastLayer> simulcastLayers
                        = sourceManager.getSimulcastLayers();

                // If the peer hasn't signaled any simulcast streams then
                // there's nothing to configure.

                Iterator<SimulcastLayer> layersIterator
                        = simulcastLayers.iterator();

                SimulcastLayer simulcastLayer = null;
                int currentLayer = SIMULCAST_LAYER_ORDER_LQ;
                while (layersIterator.hasNext()
                        && currentLayer++ <= targetOrder)
                {
                    simulcastLayer = layersIterator.next();
                }

                // Do NOT switch to hq if it's not streaming.
                if (simulcastLayer == null
                        || (simulcastLayer.getOrder() != SIMULCAST_LAYER_ORDER_LQ
                            && !simulcastLayer.isStreaming()))
                {
                    continue;
                }

                // Do NOT switch to an already receiving layer.
                synchronized (simulcastLayersSyncRoot)
                {
                    if ((simLayersMap.containsKey(peer)
                            && simLayersMap.get(peer).getCurrent()
                                == simulcastLayer))
                    {
                        if (logger.isInfoEnabled())
                        {
                            logger.info(new StringBuilder()
                                    .append(self.getID())
                                    .append(" already receives SSRC ")
                                    .append(simulcastLayer.getPrimarySSRC())
                                    .append(" of order ")
                                    .append(simulcastLayer.getOrder())
                                    .append(" from ")
                                    .append(simulcastLayer
                                            .getSimulcastManager()
                                            .getVideoChannel()
                                            .getEndpoint()
                                            .getID())
                                    .append("."));
                        }
                        continue;
                    }
                }

                // Update maps/lists.
                endpointMap.put(peer, simulcastLayer);
                if (hardSwitch)
                {
                    channelMap.put(rtpChannel, simulcastLayer);
                }

                endpointSimulcastLayers.add(new EndpointSimulcastLayer(
                        peer.getID(),
                        simulcastLayer));

                // XXX(gp) why not handle the rest of the video channels?
                break;
            }
        }

        // TODO(gp) run these in the event dispatcher thread?

        // Send FIR requests first.
        if (!channelMap.isEmpty())
        {
            for (Map.Entry<RtpChannel, SimulcastLayer> entry
                    : channelMap.entrySet())
            {
                SimulcastLayer layer = entry.getValue();
                RtpChannel channel = entry.getKey();
                channel.askForKeyframes(
                        new int[]{(int) layer.getPrimarySSRC()});
                if (logger.isDebugEnabled())
                {
                    logger.debug(new StringBuilder()
                            .append(self.getID())
                            .append(" asked a key frame from ")
                            .append(channel.getEndpoint().getID())
                            .append(" for its SSRC ")
                            .append(layer.getPrimarySSRC())
                            .append(" of order ")
                            .append(layer.getOrder())
                            .append("."));
                }
            }
        }

        // TODO(gp) remove the SimulcastLayersChangedEvent event. Receivers
        // should listen for MediaStreamTrackActivity instead. It was probably
        // a bad idea to begin with.
        if (!endpointSimulcastLayers.isEmpty())
        {
            // Receiving simulcast layers changed, create and send an event
            // through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent event
                    = new SimulcastLayersChangedEvent();

            event.endpointSimulcastLayers = endpointSimulcastLayers.toArray(
                    new EndpointSimulcastLayer[endpointSimulcastLayers.size()]);

            String json = mapper.toJson(event);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to send
                // a data message. We want to be able to handle those errors
                // ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on data " +
                        "channel.", e);
            }
        }

        if (!endpointMap.isEmpty())
        {
            synchronized (simulcastLayersSyncRoot)
            {
                for (Map.Entry<Endpoint, SimulcastLayer> entry
                        : endpointMap.entrySet())
                {
                    // FIXME(gp) don't waste resources add only once

                    ReceivingLayers receivingLayers;

                    if (!simLayersMap.containsKey(entry.getKey()))
                    {
                        receivingLayers = new ReceivingLayers(
                                entry.getKey(),
                                this.videoChannel.getEndpoint());

                        // Put the association to the qualities map.
                        this.simLayersMap.put(entry.getKey(), receivingLayers);

                        // This is an endpoint we have never seen before, hook
                        // its SimulcastManager.
                        SimulcastManager manager = entry.getValue()
                                .getSimulcastManager();

                        onSimulcastLayersChanged(manager);
                        manager.addPropertyChangeListener(
                                propertyChangeListener);
                    }
                    else
                    {
                        receivingLayers = simLayersMap.get(entry.getKey());
                    }

                    receivingLayers.setCurrent(entry.getValue());
                }
            }
        }
    }

    /**
     *
     * @param buffer
     * @param offset
     * @param length
     * @return
     */
    private int readSSRC(byte[] buffer, int offset, int length)
    {
        if (length >= RTPHeader.SIZE)
        {
            int v = ((buffer[offset] & 0xc0) >>> 6);

            if (v == 2)
            {
                return RTPTranslatorImpl.readInt(buffer, offset + 8);
            }
        }

        return 0;
    }

    @Override
    public void propertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        if (SimulcastLayer.IS_STREAMING_PROPERTY
                .equals(propertyChangeEvent.getPropertyName()))
        {
            SimulcastLayer layer
                    = (SimulcastLayer) propertyChangeEvent.getSource();

            onStreamsChanged(layer);
        }
        else if (Endpoint
                .SELECTED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()))
        {
            // endpoint == this.videoChannel.getEndpoint() is implied.

            String oldValue = (String) propertyChangeEvent.getOldValue();
            String newValue = (String) propertyChangeEvent.getNewValue();

            if (logger.isDebugEnabled())
            {
                logger.debug(new StringBuilder()
                        .append(this.videoChannel.getEndpoint().getID())
                        .append(" is now watching ")
                        .append(newValue)
                        .append("."));
            }

            onSelectedEndpointChanged(oldValue, newValue);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(
                propertyChangeEvent.getPropertyName()))
        {
            onEndpointChanged();
        }
        else if (SIMULCAST_LAYERS_PROPERTY.equals(
                propertyChangeEvent.getPropertyName()))
        {
            SimulcastManager manager = (SimulcastManager) propertyChangeEvent
                    .getSource();

            onSimulcastLayersChanged(manager);
        }
    }

    private void onSimulcastLayersChanged(SimulcastManager manager)
    {
        if (manager.hasLayers())
        {
            for (SimulcastLayer layer : manager.getSimulcastLayers())
            {
                // Add listener from the current receiving simulcast layers.
                layer.addPropertyChangeListener(propertyChangeListener);
            }
        }
    }

    private void onStreamsChanged(SimulcastLayer owner)
    {

        Endpoint peer = owner
                .getSimulcastManager()
                .getVideoChannel()
                .getEndpoint();

        if (!owner.isStreaming())
        {
            // HQ stream has stopped, switch to a lower quality stream.

            Map<Endpoint, ReceivingSimulcastOptions> endpointsQualityMap
                    = new HashMap<Endpoint, ReceivingSimulcastOptions>(1);

            ReceivingSimulcastOptions options =
                    new ReceivingSimulcastOptions(
                            SIMULCAST_LAYER_ORDER_LQ, true);

            endpointsQualityMap.put(peer, options);

            setReceivingSimulcastLayer(endpointsQualityMap);
        }
        else
        {
            Endpoint self = getVideoChannel().getEndpoint();

            if (peer.getID().equals(self.getSelectedEndpointID()))
            {
                receiveHighQualityOnlyFrom(peer.getID(), false);
            }
        }
    }

    private void onSelectedEndpointChanged(String oldValue, String newValue)
    {
        // Rule 1: send an hq stream only for the selected endpoint.
        receiveHighQualityOnlyFrom(newValue, true);

        // Rule 2: if the old endpoint is not being watched by any of
        // the receivers, the bridge tells it to stop streaming its hq
        // stream.
        maybeSendStopHighQualityStreamCommand(oldValue);

        // Rule 3: if the new endpoint is being watched by any of the
        // receivers, the bridge tells it to start streaming its hq
        // stream.
        maybeSendStartHighQualityStreamCommand(newValue);

    }

    private void onEndpointChanged()
    {
        Endpoint self = videoChannel.getEndpoint();
        if (self != null)
        {
            self.addPropertyChangeListener(propertyChangeListener);
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
            oldEndpoint = videoChannel
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
            for (Endpoint e : videoChannel
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

                logger.debug(this.videoChannel.getEndpoint().getID() +
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
            newEndpoint = videoChannel.getContent().getConference().getEndpoint(id);
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
            for (Endpoint e
                    : videoChannel.getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the
                // selected endpoint changes while we're in the loop?

                if (newEndpoint != e
                        && (newEndpoint.getID().equals(
                            e.getSelectedEndpointID())
                        || (SIMULCAST_LAYER_ORDER_INIT
                                > SIMULCAST_LAYER_ORDER_LQ
                            && StringUtils.isNullOrEmpty(
                                e.getSelectedEndpointID())))
                        )
                {
                    // somebody is watching the new endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, start
                    // the hq stream.

                    if (logger.isDebugEnabled())
                    {
                        logger.debug(new StringBuilder()
                                .append(e.getID())
                                .append(" is ")
                                .append(StringUtils.isNullOrEmpty(
                                        e.getSelectedEndpointID()) ?
                                        "(maybe) " : "")
                                .append("watching ")
                                .append(newEndpoint.getID())
                                .append("."));
                    }

                    startHighQualityStream = true;
                    break;
                }
            }

            if (startHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(this.videoChannel.getEndpoint().getID() +
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

    /**
     * Configures the simulcast manager of the receiver to receive a high
     * quality stream only from the designated sender.
     *
     * @param id
     */
    private void receiveHighQualityOnlyFrom(String id, boolean hardSwitch)
    {

        Collection<Endpoint> endpoints = videoChannel
                .getContent()
                .getConference()
                .getEndpoints();

        if (endpoints == null || endpoints.isEmpty())
            return;

        Map<Endpoint, ReceivingSimulcastOptions> qualityMap
                = new HashMap<Endpoint, ReceivingSimulcastOptions>(
                    endpoints.size());

        for (Endpoint e : endpoints)
        {
            if (!StringUtils.isNullOrEmpty(id)
                    && id.equals(e.getID()))
            {
                ReceivingSimulcastOptions options
                        = new ReceivingSimulcastOptions(
                            SIMULCAST_LAYER_ORDER_HQ, hardSwitch);
                qualityMap.put(e, options);
            }
            else
            {
                ReceivingSimulcastOptions options
                        = new ReceivingSimulcastOptions(
                            SIMULCAST_LAYER_ORDER_LQ, hardSwitch);

                qualityMap.put(e, options);
            }
        }

        this.setReceivingSimulcastLayer(qualityMap);
    }
}
