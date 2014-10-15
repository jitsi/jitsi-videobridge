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
    implements PropertyChangeListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastManager.class);

    /**
     * The associated <tt>VideoChannel</tt> of this simulcast manager.
     */
    private final VideoChannel videoChannel;

    /**
     * Defines the simulcast substream to receive, if there is no other
     */
    private static final Integer initialSimulcastLayer = 0;

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
     * packet to its associated endpoint or not.
     */
    private final Map<Endpoint, SimulcastLayer> simLayersMap
            = new WeakHashMap<Endpoint, SimulcastLayer>();

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
                        layer.starve();
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
            SimulcastLayer electedSimulcastLayer
                    = electSimulcastLayer(srcVideoChannel);

            if (electedSimulcastLayer != null)
            {
                accept = electedSimulcastLayer.contains(ssrc);
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
    private SimulcastLayer electSimulcastLayer(VideoChannel srcVideoChannel)
    {
        SimulcastLayer electedSimulcastLayer = null;

        SimulcastManager simulcastManager;
        if (srcVideoChannel != null
                && (simulcastManager = srcVideoChannel.getSimulcastManager())
                != null
                && simulcastManager.hasLayers())
        {
            Endpoint sourceEndpoint = srcVideoChannel.getEndpoint();
            if (sourceEndpoint != null)
            {
                synchronized (simulcastLayersSyncRoot)
                {
                    if (!simLayersMap.containsKey(sourceEndpoint))
                    {
                        logger.info("Setting to receive initial simulcast " +
                                "layer.");

                        Map<Endpoint, Integer> endpointsQualityMap
                                = new HashMap<Endpoint, Integer>(1);

                        endpointsQualityMap.put(sourceEndpoint,
                                initialSimulcastLayer);

                        setReceivingSimulcastLayer(endpointsQualityMap);
                    }

                    electedSimulcastLayer = simLayersMap.get(sourceEndpoint);
                }
            }
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

        if (logger.isDebugEnabled() && videoChannel.getEndpoint() != null)
        {
            logger.debug(new StringBuilder()
                    .append(videoChannel.getEndpoint().getID())
                    .append(" has signaled: ")
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
            Map<Endpoint, Integer> endpointsQualityMap)
    {
        if (endpointsQualityMap == null || endpointsQualityMap.isEmpty())
        {
            logger.info("Cannot process empty endpoints quality map.");
            return;
        }

        logger.info("Attempt to set receiving simulcast layers.");

        // TODO(gp) maybe add expired check (?)
        Endpoint self = videoChannel.getEndpoint();
        if (self == null)
        {
            logger.info("Channel endpoint not set yet.");
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
        for (Map.Entry<Endpoint, Integer> entry
                : endpointsQualityMap.entrySet())
        {
            Endpoint peer = entry.getKey();
            if (peer == self)
                continue;

            List<RtpChannel> rtpChannels = peer.getChannels(MediaType.VIDEO);

            if (rtpChannels == null || rtpChannels.isEmpty())
                continue;

            for (RtpChannel rtpChannel : rtpChannels)
            {
                if (!(rtpChannel instanceof VideoChannel))
                    continue;

                VideoChannel sourceVideoChannel = (VideoChannel) rtpChannel;

                SortedSet<SimulcastLayer> simulcastLayers = sourceVideoChannel
                        .getSimulcastManager()
                        .getSimulcastLayers();

                if (simulcastLayers != null && simulcastLayers.size() > 1)
                {
                    // If the peer hasn't signaled any simulcast streams then
                    // there's nothing to configure.

                    Iterator<SimulcastLayer> layersIterator
                            = simulcastLayers.iterator();

                    SimulcastLayer simulcastLayer = null;
                    int currentLayer = 0;
                    while (layersIterator.hasNext()
                            && currentLayer++ <= entry.getValue())
                    {
                        simulcastLayer = layersIterator.next();
                    }

                    if (simulcastLayer != null
                            && (!endpointMap.containsKey(peer)
                            || endpointMap.get(peer) != simulcastLayer))
                    {
                        if (!simulcastLayer.isStreaming())
                        {
                            logger.warn("Set to receive a simulcast layer " +
                                    "that is not streaming!");
                        }

                        // Update maps/lists.
                        endpointMap.put(peer, simulcastLayer);
                        channelMap.put(rtpChannel, simulcastLayer);
                        endpointSimulcastLayers.add(new EndpointSimulcastLayer(
                                peer.getID(),
                                simulcastLayer));

                        if (logger.isInfoEnabled())
                        {
                            StringBuilder b = new StringBuilder();
                            mapper.toJson(b, simulcastLayer);

                            logger.info(self.getID() + " now receives from "
                                    + peer.getID() + ": " + b.toString());
                        }
                    }

                    // XXX(gp) why not handle the rest of the video channels?
                    break;
                }
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
                        new int[] { (int) layer.getPrimarySSRC() });
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
                logger.error("Failed to send message on data channel.", e);
            }

            if (logger.isInfoEnabled())
            {
                for (EndpointSimulcastLayer esl
                        : event.endpointSimulcastLayers)
                {
                    StringBuilder b = new StringBuilder();
                    mapper.toJson(b, esl.simulcastLayer);

                    logger.info("Notified " + self.getID() + " that it now " +
                            "receives from " + esl.endpoint + ": "
                            + b.toString());
                }
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

                    // Add listener from the current receiving simulcast layers.
                    entry.getValue().addPropertyChangeListener(propertyChangeListener);

                    // Remove listener from the previous receiving simulcast
                    // layers.
                    //if (this.simLayersMap.containsKey(entry.getKey()))
                    //{
                    //    this.simLayersMap
                    //            .get(entry.getKey())
                    //            .removePropertyChangeListener(propertyChangeListener);
                    //}

                    // Put the association to the qualities map.
                    this.simLayersMap.put(entry.getKey(), entry.getValue());
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
            onStreamsChanged(propertyChangeEvent);
        }
        else if (Endpoint
                .SELECTED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()))
        {
            onSelectedEndpointChanged(propertyChangeEvent);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(
                propertyChangeEvent.getPropertyName()))
        {
            onEndpointChanged(propertyChangeEvent);
        }
    }

    private void onStreamsChanged(PropertyChangeEvent propertyChangeEvent)
    {
        SimulcastLayer layer
                = (SimulcastLayer) propertyChangeEvent.getSource();

        logger.info("Notified that a simulcast stream has stopped or " +
                "started streaming!");

        Endpoint peer = layer
                .getSimulcastManager()
                .getVideoChannel()
                .getEndpoint();

        if (!layer.isStreaming())
        {
            // HQ stream has stopped, switch to a lower quality stream.
            logger.info("A simulcast stream has stopped! Setting to " +
                    "receive initial simulcast layer.");

            Map<Endpoint, Integer> endpointsQualityMap
                    = new HashMap<Endpoint, Integer>(1);

            endpointsQualityMap.put(peer, 0);

            setReceivingSimulcastLayer(endpointsQualityMap);
        }
        else
        {
            Endpoint self = getVideoChannel().getEndpoint();

            if (peer.getID().equals(self.getSelectedEndpointID()))
            {
                logger.info("A simulcast stream has started and we're " +
                        "watching! Setting to receive high quality.");

                receiveHighQualityOnlyFrom(peer.getID());
            }
        }
    }

    private void onSelectedEndpointChanged(
            PropertyChangeEvent propertyChangeEvent)
    {
        Endpoint endpoint =
                (Endpoint) propertyChangeEvent.getSource();

        // endpoint == this.videoChannel.getEndpoint() is implied.

        if (logger.isDebugEnabled())
        {
            logger.debug(new StringBuilder()
                    .append("Handling selected event from associated " +
                            "endpoint: ")
                    .append(endpoint.getID()));
        }

        String oldValue = (String) propertyChangeEvent.getOldValue();
        String newValue = (String) propertyChangeEvent.getNewValue();

        // Rule 1: send an hq stream only for the selected endpoint.
        receiveHighQualityOnlyFrom(newValue);

        // Rule 2: if the old endpoint is not being watched by any of
        // the receivers, the bridge tells it to stop streaming its hq
        // stream.
        maybeSendStopHighQualityStreamCommand(oldValue);

        // Rule 3: if the new endpoint is being watched by any of the
        // receivers, the bridge tells it to start streaming its hq
        // stream.
        maybeSendStartHighQualityStreamCommand(newValue);

    }

    private void onEndpointChanged(PropertyChangeEvent propertyChangeEvent)
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

                logger.info("Stopping the HQ stream of " + oldEndpoint.getID()
                        + ".");

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
                    logger.error("Failed to send message on data channel.", e1);
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
            for (Endpoint e : videoChannel.getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the
                // selected endpoint changes while we're in the loop?

                if (newEndpoint != e
                        && (newEndpoint.getID().equals(e.getSelectedEndpointID())
                        || StringUtils.isNullOrEmpty(e.getSelectedEndpointID()))
                        )
                {
                    // somebody is watching the new endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, start
                    // the hq stream.

                    if (logger.isDebugEnabled())
                    {
                        if (StringUtils.isNullOrEmpty(
                                e.getSelectedEndpointID()))
                        {
                            logger.debug("Maybe " + e.getID() + " is watching "
                                    + newEndpoint.getID());
                        } else {

                            logger.debug(e.getID() + " is watching "
                                    + newEndpoint.getID());
                        }
                    }

                    startHighQualityStream = true;
                    break;
                }
            }

            if (startHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.info("Starting the HQ stream of " + newEndpoint.getID()
                        + ".");

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
                    logger.error("Failed to send message on data channel.", e1);
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
    private void receiveHighQualityOnlyFrom(String id)
    {

        Collection<Endpoint> endpoints = videoChannel
                .getContent()
                .getConference()
                .getEndpoints();

        if (endpoints == null || endpoints.isEmpty())
            return;

        Map<Endpoint, Integer> qualityMap
                = new HashMap<Endpoint, Integer>(endpoints.size());

        for (Endpoint e : endpoints)
        {
            if (!StringUtils.isNullOrEmpty(id)
                    && id.equals(e.getID()))
            {
                // NOTE(gp) 10 here is an arbitrary large value that, maybe, it
                // should be a constant. I'm not sure if that's good enough though.
                qualityMap.put(e, 10);
            }
            else
            {
                qualityMap.put(e, 0);
            }
        }

        this.setReceivingSimulcastLayer(qualityMap);
    }
}
