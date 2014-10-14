/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import java.io.*;
import java.net.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.sim.messages.*;

/**
 * The simulcast manager of a <tt>VideoChannel</tt>.
 *
 * @author George Politis
 */
public class SimulcastManager
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
            int acceptedSSRC = readSSRC(p.getData(), p.getOffset() + 8,
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

            // If we can't find an accepted layer, log and return as this
            // situation makes no sense.
            if (acceptedLayer == null)
            {
                if (logger.isInfoEnabled())
                {
                    logger.info("Accepted a Datagram packet of unknown source");
                }

                return;
            }

            // NOTE(gp) we expect the base layer to be always on.


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
                // We have accepted a non-base layer packet, feed the accepted
                // layer.
                acceptedLayer.feed();
            }
        }
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

    private final static SimulcastMessagesMapper mapper
            = new SimulcastMessagesMapper();

    public void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers)
    {
        synchronized (simulcastLayersSyncRoot)
        {
            this.simulcastLayers = simulcastLayers;
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

                    logger.info(self.getID() + " now receives from "
                            + esl.endpoint + ": " + b.toString());
                }
            }
        }


        synchronized (simulcastLayersSyncRoot)
        {
            this.simLayersMap.putAll(endpointMap);
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
