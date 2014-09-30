/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.json.simple.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

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
    private static final Integer initialSimulcastLayer = 1;

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
    private Map<WeakReference<Endpoint>, SimulcastLayer> simLayersMap
            = new HashMap<WeakReference<Endpoint>, SimulcastLayer>();

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

        // Iterate the simulcast layers of the endpoint.
        SortedSet<SimulcastLayer> simulcastLayers
                = sourceVideoChannel.getSimulcastManager().getSimulcastLayers();

        if (simulcastLayers == null || simulcastLayers.size() < 2)
            return accept;

        // FIXME(gp) inconsistent usage of longs and ints.

        // Get the SSRC of the packet.
        long ssrc = readSSRC(buffer, offset, length) & 0xffffffffl;

        accept = acceptSimulcastLayer(ssrc, sourceVideoChannel);

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

        // Iterate the simulcast layers of the endpoint.
        SortedSet<SimulcastLayer> srcSimulcastLayers
                = srcVideoChannel.getSimulcastManager().getSimulcastLayers();

        if (srcSimulcastLayers == null || srcSimulcastLayers.size() < 2)
            return accept;

        SimulcastLayer electedSimulcastLayer
                = electSimulcastLayer(srcVideoChannel);

        accept = electedSimulcastLayer.contains(ssrc);

        return accept;
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
        Endpoint targetEndpoint = videoChannel.getEndpoint();
        Endpoint sourceEndpoint =  srcVideoChannel.getEndpoint();
        SimulcastLayer electedSimulcastLayer = null;
        // TODO(gp) should be Map<WeakReference<VideoChannel>, SimulcastLayer>
        synchronized (simulcastLayersSyncRoot)
        {
            for (WeakReference<Endpoint> wr : simLayersMap.keySet())
            {
                Endpoint e = wr.get();
                if (e != null)
                {
                    if (e.equals(sourceEndpoint))
                    {
                        electedSimulcastLayer = simLayersMap.get(wr);
                        break;
                    }
                }
            }

            if (electedSimulcastLayer == null)
            {
                SortedSet<SimulcastLayer> srcSimulcastLayers
                        = srcVideoChannel.getSimulcastManager()
                                .getSimulcastLayers();

                // start with some predefined initial quality layer.
                Iterator<SimulcastLayer> layersIterator
                        = srcSimulcastLayers.iterator();
                int currentLayer = 0;
                while (layersIterator.hasNext()
                        && currentLayer++ <= initialSimulcastLayer)
                {

                    electedSimulcastLayer = layersIterator.next();
                }

                WeakReference<Endpoint> wr
                        = new WeakReference<Endpoint>(sourceEndpoint);

                simLayersMap.put(wr, electedSimulcastLayer);
            }
        }

        // Source is currently being watched at the target.
        if (sourceEndpoint.getID().equals(
                targetEndpoint.getSelectedEndpointID())

                // The elected simulcast layer is not the high quality one.
                && electedSimulcastLayer != srcVideoChannel
                        .getSimulcastManager().getSimulcastLayers().last()

                // Data channel to target endpoint is open.
                && videoChannel.getEndpoint().getSctpConnection().isReady()
                && !videoChannel.getEndpoint().getSctpConnection().isExpired())
        {
            SimulcastLayer hqLayer = srcVideoChannel.getSimulcastManager()
                    .getSimulcastLayers().last();

            // Receiving simulcast layers changed, create and send an event
            // through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent event
                    = new SimulcastLayersChangedEvent();

            event.endpointSimulcastLayers = new EndpointSimulcastLayer[1];
            event.endpointSimulcastLayers[0] = new EndpointSimulcastLayer(
                    srcVideoChannel.getEndpoint().getID(),
                    hqLayer);

            String json = MyJsonEncoder.toJson(event);
            boolean sent = false;
            try
            {
                videoChannel.getEndpoint().sendMessageOnDataChannel(json);
                sent = true;

                if (logger.isInfoEnabled())
                {
                    logger.info("Receiving simulcast layers for endpoint "
                            + videoChannel.getEndpoint().getID() + " changed:" +
                            json);
                }
            }
            catch (IOException e)
            {
                logger.error("Failed to send message on data channel " +
                        "(although it was reported to be ready!).", e);
            }

            if (sent)
            {
                // Only update the receiving simulcast layer if we managed to
                // send a notification to the endpoint.

                electedSimulcastLayer = hqLayer;
                synchronized (simulcastLayersSyncRoot)
                {
                    WeakReference<Endpoint> wr
                            = new WeakReference<Endpoint>(sourceEndpoint);

                    simLayersMap.put(wr, electedSimulcastLayer);
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
     * @param endpoints
     * @param receivingSimulcastLayer
     */
    public void setReceivingSimulcastLayer(
            Collection<Endpoint> endpoints,
            Integer receivingSimulcastLayer)
    {
        Content content = videoChannel.getContent();
        if (content == null)
            return;

        Conference conference = content.getConference();
        if (conference == null)
            return;

        if (endpoints == null || endpoints.isEmpty())
            return;

        // TODO(gp) add expired check.
        Endpoint self = videoChannel.getEndpoint();
        if (self == null)
            return;

        Map<WeakReference<Endpoint>, SimulcastLayer> map
                = new HashMap<WeakReference<Endpoint>, SimulcastLayer>(
                endpoints.size());

        List<EndpointSimulcastLayer> endpointSimulcastLayers
                = new ArrayList<EndpointSimulcastLayer>(endpoints.size());

        for (Endpoint peer : endpoints)
        {
            if (peer == self)
                continue;

            WeakReference<Endpoint> wr
                    = new WeakReference<Endpoint>(peer);


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
                                sourceVideoChannel.getSimulcastManager()
                                        .getSimulcastLayers();

                        Iterator<SimulcastLayer> layersIterator
                                = simulcastLayers.iterator();

                        SimulcastLayer simulcastLayer = null;
                        int currentLayer = 0;
                        while (layersIterator.hasNext()
                                && currentLayer++ <= receivingSimulcastLayer)
                        {

                            simulcastLayer = layersIterator.next();
                        }

                        if (simulcastLayer != null)
                        {
                            map.put(wr, simulcastLayer);
                            EndpointSimulcastLayer endpointSimulcastLayer
                                    = new EndpointSimulcastLayer(peer.getID(),
                                    simulcastLayer);

                            endpointSimulcastLayers.add(endpointSimulcastLayer);
                        }

                        break;
                    }
                }
            }
        }

        // TODO(gp) remove the SimulcastLayersChangedEvent event receivers
        // should listen for MediaStreamTrackActivity instead. It was a bad
        // idea to begin with.
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
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error("Failed to send message on data channel.", e);
            }

            if (logger.isInfoEnabled())
            {
                logger.info("Receiving simulcast layers for endpoint "
                        + self.getID() + " changed:" + json);
            }
        }

        synchronized (simulcastLayersSyncRoot)
        {
            this.simLayersMap = map;
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
