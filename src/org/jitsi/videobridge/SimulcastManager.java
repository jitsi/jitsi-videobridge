/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.json.simple.*;

/**
 * The simulcast manager of a <tt>VideoChannel</tt>.
 *
 * @author George Politis
 */
public class SimulcastManager
{
    /**
     * The <tt>Logger</tt> used by the <tt>Simulcast</tt> class and its
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
     * Represents a notification/event that is sent to an endpoint through data
     * channels when there is a change in the simulcast substream the bridge is
     * pushing to that specific endpoint.
     */
    static class SimulcastLayersChangedEvent
    {
        final String colibriClass = "SimulcastLayersChangedEvent";
        EndpointSimulcastLayer[] endpointSimulcastLayers;
    }

    /**
     * Associates a simulcast layer with an endpoint ID.
     */
    static class EndpointSimulcastLayer
    {
        public EndpointSimulcastLayer(String endpoint,
                                      SimulcastLayer simulcastLayer)
        {
            this.endpoint = endpoint;
            this.simulcastLayer = simulcastLayer;
        }

        final String endpoint;
        final SimulcastLayer simulcastLayer;
    }

    public SimulcastManager(VideoChannel videoChannel)
    {
        this.videoChannel = videoChannel;
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

        if (sourceVideoChannel != null
                && sourceVideoChannel.getSimulcastManager().hasLayers())
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

        if (ssrc > 0
                && srcVideoChannel != null
                && srcVideoChannel.getSimulcastManager().hasLayers())
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

        if (srcVideoChannel != null)
        {
            // No need to waste resources if the source hasn't signaled any
            // simulcast layers.
            if (srcVideoChannel.getSimulcastManager().hasLayers())
            {
                synchronized (simulcastLayersSyncRoot)
                {
                    Endpoint sourceEndpoint = srcVideoChannel.getEndpoint();

                    if (!simLayersMap.containsKey(sourceEndpoint))
                    {
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

    /**
     * Updates the receiving simulcast layers of this <tt>Simulcast</tt>
     * instance.
     *
     * @param sourceGroups
     */
    public void updateSimulcastLayers(
            List<SourceGroupPacketExtension> sourceGroups)
    {
        if (sourceGroups == null)
            return;

        synchronized (simulcastLayersSyncRoot)
        {
            if (sourceGroups.size() == 0)
                simulcastLayers = null;
        }

        Map<Long, SimulcastLayer> reverseMap
                = new HashMap<Long, SimulcastLayer>();

        // Build the simulcast layers.
        SortedSet<SimulcastLayer> layers = new TreeSet<SimulcastLayer>();
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            List<SourcePacketExtension> sources = sourceGroup.getSources();

            if (sources == null || sources.size() == 0
                    || !"SIM".equals(sourceGroup.getSemantics()))
            {
                continue;
            }

            // sources are in low to high order.
            int order = 0;
            for (SourcePacketExtension source : sources)
            {
                Long primarySSRC = source.getSSRC();
                SimulcastLayer simulcastLayer = new SimulcastLayer(primarySSRC,
                        order++);

                // Add the layer to the reverse map.
                reverseMap.put(primarySSRC, simulcastLayer);

                // Add the layer to the sorted set.
                layers.add(simulcastLayer);
            }

        }

        // Append associated SSRCs from other source groups.
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            List<SourcePacketExtension> sources = sourceGroup.getSources();

            if (sources == null || sources.size() == 0
                    || "SIM".equals(sourceGroup.getSemantics()))
            {
                continue;
            }

            SimulcastLayer simulcastLayer = null;

            // Find all the associated ssrcs for this group.
            Set<Long> ssrcs = new HashSet<Long>();
            for (SourcePacketExtension source : sources)
            {
                Long ssrc = source.getSSRC();
                ssrcs.add(source.getSSRC());
                if (reverseMap.containsKey(ssrc))
                {
                    simulcastLayer = reverseMap.get(ssrc);
                }
            }

            if (simulcastLayer != null)
            {
                simulcastLayer.associateSSRCs(ssrcs);
            }
        }

        synchronized (simulcastLayersSyncRoot)
        {
            simulcastLayers = layers;
        }

        // Debug print signaling information.
        if (logger.isInfoEnabled())
        {
            synchronized (simulcastLayersSyncRoot)
            {
                logger.info("Endpoint " + videoChannel.getEndpoint().getID()
                        + " has signaled :" + MyJsonEncoder.toJson(simulcastLayers));
            }
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
            return;

        // TODO(gp) maybe add expired check (?)
        Endpoint self = videoChannel.getEndpoint();
        if (self == null)
            return;

        Map<Endpoint, SimulcastLayer> endpointMap
                = new HashMap<Endpoint, SimulcastLayer>(
                        endpointsQualityMap.size());

        Map<RtpChannel, SimulcastLayer> channelMap
                = new HashMap<RtpChannel, SimulcastLayer>(endpointsQualityMap.size());

        List<EndpointSimulcastLayer> endpointSimulcastLayers
                = new ArrayList<EndpointSimulcastLayer>(endpointsQualityMap.size());

        for (Map.Entry<Endpoint, Integer> entry
                : endpointsQualityMap.entrySet())
        {
            Endpoint peer = entry.getKey();
            if (peer == self)
                continue;

            List<RtpChannel> rtpChannels = peer
                    .getChannels(MediaType.VIDEO);

            if (rtpChannels != null && !rtpChannels.isEmpty())
            {
                for (RtpChannel rtpChannel : rtpChannels)
                {
                    if (rtpChannel instanceof VideoChannel)
                    {
                        VideoChannel sourceVideoChannel
                                = (VideoChannel) rtpChannel;

                        SortedSet<SimulcastLayer> simulcastLayers =
                                sourceVideoChannel
                                        .getSimulcastManager()
                                        .getSimulcastLayers();

                        if (simulcastLayers != null
                                && simulcastLayers.size() > 1)
                        {
                            // If the peer hasn't signaled any simulcast streams
                            // then there's nothing to configure.

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
                                endpointMap.put(peer, simulcastLayer);
                                channelMap.put(rtpChannel, simulcastLayer);

                                EndpointSimulcastLayer endpointSimulcastLayer
                                        = new EndpointSimulcastLayer(
                                                peer.getID(),
                                                simulcastLayer);

                                endpointSimulcastLayers.add(
                                        endpointSimulcastLayer);
                            }

                            break;
                        }
                    }
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

            String json = MyJsonEncoder.toJson(event);
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
                    MyJsonEncoder.toJson(b, esl.simulcastLayer);

                    logger.info(self.getID() + " now receives from "
                            + esl.endpoint + ": " + b.toString());
                }
            }
        }

        // Send FIR requests
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

        synchronized (simulcastLayersSyncRoot)
        {
            this.simLayersMap.putAll(endpointMap);
        }
    }

    static class MyJsonEncoder
    {
        // NOTE(gp) custom JSON encoders/decoders are a maintenance burden and
        // a source of bugs. We should consider using a specialized library that
        // does that automatically, like Gson or Jackson. It would work like
        // this;
        //
        // Gson gson = new Gson();
        // String json = gson.toJson(event);
        //
        // So, basically it would work exactly like this custom encoder, but
        // without having to write a single line of code.

        private static String toJson(SortedSet<SimulcastLayer> simulcastLayers)
        {
            StringBuilder b = new StringBuilder("[");
            for (SimulcastLayer simulcastLayer : simulcastLayers)
            {
                toJson(b, simulcastLayer);
            }
            b.append("]");

            return b.toString();
        }

        private static String toJson(SimulcastLayersChangedEvent event)
        {
            StringBuilder b = new StringBuilder(
                    "{\"colibriClass\":\"SimulcastLayersChangedEvent\"");

            b.append(",\"endpointSimulcastLayers\":[");
            for (int i = 0; i < event.endpointSimulcastLayers.length; i++)
            {
                toJson(b, event.endpointSimulcastLayers[i]);
                if (i != event.endpointSimulcastLayers.length - 1)
                    b.append(",");
            }
            b.append("]}");

            return b.toString();
        }

        private static void toJson(StringBuilder b,
                                   EndpointSimulcastLayer endpointSimulcastLayer)
        {
            b.append("{\"endpoint\":");
            b.append(JSONValue.toJSONString(endpointSimulcastLayer.endpoint));
            b.append(",\"simulcastLayer\":");
            toJson(b, endpointSimulcastLayer.simulcastLayer);
            b.append("}");
        }

        private static void toJson(StringBuilder b, SimulcastLayer simulcastLayer)
        {
            b.append("{\"primarySSRC\":");
            b.append(JSONValue.escape(
                    Long.toString(simulcastLayer.getPrimarySSRC())));

            List<Long> associatedSSRCs = simulcastLayer.getAssociatedSSRCs();
            if (associatedSSRCs != null && associatedSSRCs.size() != 0)
            {
                b.append(",\"asociatedSSRCs\":[");
                for (int i = 0; i < associatedSSRCs.size(); i++)
                {
                    b.append(JSONValue.escape(
                            Long.toString(associatedSSRCs.get(i))));

                    if (i != associatedSSRCs.size() - 1)
                        b.append(",");
                }
                b.append("]");
            }
            b.append("}");
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
}
