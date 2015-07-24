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

import java.net.*;
import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.messages.*;

/**
 * The simulcast manager of a <tt>VideoChannel</tt>.
 *
 * TODO(gp) Add a SimulcastSender that will hold the SimulcastLayers.
 *
 * @author George Politis
 */
public class SimulcastManager
    extends PropertyChangeNotifier
{
    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(SimulcastManager.class);

    private final static SimulcastMessagesMapper mapper
        = new SimulcastMessagesMapper();

    /**
     * High quality layer order.
     */
    protected static final int SIMULCAST_LAYER_ORDER_HQ = 1;

    /**
     * Base layer quality order.
     */
    public static final int SIMULCAST_LAYER_ORDER_LQ = 0;

    /**
     * Defines the simulcast substream to receive, if there is no other
     */
    protected static final int SIMULCAST_LAYER_ORDER_INIT
        = SIMULCAST_LAYER_ORDER_LQ;

    /**
     * The order when there's no override layer.
     */
    public static final int SIMULCAST_LAYER_ORDER_NO_OVERRIDE = -1;

    protected static final String SIMULCAST_LAYERS_PROPERTY
        = SimulcastManager.class.getName() + ".simulcastLayers";

    /**
     * The simulcast layers of this <tt>VideoChannel</tt>.
     *
     * TODO(gp) Move to SimulcastSender
     */
    private SortedSet<SimulcastLayer> simulcastLayers;

    /**
     * Associates sending endpoints to receiving simulcast layer. This simulcast
     * manager uses this map to determine whether or not to forward a video RTP
     * packet to its associated endpoint or not.  An entry in a this map will
     * automatically be removed when its key is no longer in ordinary use.
     *
     * TODO(gp) must associate <tt>SimulcastSender</tt>s to
     * <tt>SimulcastReceiver</tt>s
     */
    private final Map<SimulcastManager, SimulcastReceiver> simulcastReceivers
        = new WeakHashMap<SimulcastManager, SimulcastReceiver>();

    /**
     * The associated <tt>VideoChannel</tt> of this simulcast manager.
     */
    private final VideoChannel videoChannel;

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
    public boolean accept(
            byte[] buffer, int offset, int length,
            VideoChannel peerVC)
    {
        SimulcastManager peerSM;
        boolean accept = true;

        if (peerVC != null
                && (peerSM = peerVC.getSimulcastManager()) != null
                && peerSM.hasLayers())
        {
            // FIXME(gp) inconsistent usage of longs and ints.

            // Get the SSRC of the packet.
            long ssrc = readSSRC(buffer, offset, length) & 0xffffffffl;

            if (ssrc > 0)
                accept = accept(ssrc, peerVC);
        }

        return accept;
    }

    /**
     * Determines whether the SSRC belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @param ssrc
     * @param peerVC
     * @return
     */
    public boolean accept(long ssrc, VideoChannel peerVC)
    {
        SimulcastManager peerSM;
        boolean accept = true;

        if (ssrc > 0
                && peerVC != null
                && (peerSM = peerVC.getSimulcastManager()) != null
                && peerSM.hasLayers())
        {
            SimulcastReceiver sr = getOrCreateSimulcastReceiver(peerSM);

            if (sr != null)
                accept = sr.accept(ssrc);
        }

        return accept;
    }

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     * TODO(gp) Move to SimulcastSender.
     *
     * @param p the <tt>DatagramPacket</tt> received on the data
     *          <tt>DatagramSocket</tt> of this <tt>Channel</tt>
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

            acceptedLayer.acceptedDataInputStreamDatagramPacket(p);

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

    /**
     * .
     * @param peerSM
     * @return
     */
    public long getIncomingBitrate(SimulcastManager peerSM, boolean noOverride)
    {
        long bitrate = 0;

        if (peerSM == null || !peerSM.hasLayers())
        {
            return bitrate;
        }

        SimulcastReceiver sr = getOrCreateSimulcastReceiver(peerSM);
        if (sr != null)
        {
            bitrate = sr.getIncomingBitrate(noOverride);
        }

        return bitrate;
    }

    /**
     * Determines which simulcast layer from the srcVideoChannel is currently
     * being received by this video channel.
     *
     * @param srcVideoChannel
     * @return
     */
    private SimulcastReceiver getOrCreateSimulcastReceiver(
            SimulcastManager peerSM)
    {
        SimulcastReceiver sr = null;

        if (peerSM != null && peerSM.hasLayers())
        {
            synchronized (simulcastReceivers)
            {
                if (!simulcastReceivers.containsKey(peerSM))
                {
                    // Create a new receiver.
                    sr = new SimulcastReceiver(this, peerSM);
                    simulcastReceivers.put(peerSM, sr);
                }
                else
                {
                    // Get the receiver that handles this peer simulcast manager
                    sr = simulcastReceivers.get(peerSM);
                }
            }
        }

        return sr;
    }

    protected SimulcastLayer getSimulcastLayer(int targetOrder)
    {
        SimulcastLayer next = null;

        SortedSet<SimulcastLayer> layers = getSimulcastLayers();

        if (layers != null && !layers.isEmpty())
        {
            Iterator<SimulcastLayer> it = layers.iterator();

            int currentLayer = SimulcastManager.SIMULCAST_LAYER_ORDER_LQ;
            while (it.hasNext()
                    && currentLayer++ <= targetOrder)
            {
                next = it.next();
            }
        }

        return next;
    }

    /**
     * Gets the simulcast layers of this simulcast manager.
     *
     * @return
     */
    public SortedSet<SimulcastLayer> getSimulcastLayers()
    {
        SortedSet<SimulcastLayer> sl = simulcastLayers;
        return
                (sl == null)
                        ? null
                        : new TreeSet<SimulcastLayer>(sl);
    }

    public VideoChannel getVideoChannel()
    {
        return videoChannel;
    }

    /**
     * Returns true if the endpoint has signaled two or more simulcast layers.
     *
     * @return
     */
    public boolean hasLayers()
    {
        SortedSet<SimulcastLayer> sl = simulcastLayers;
        return sl != null && sl.size() > 1;
    }

    public boolean override(int overrideOrder)
    {
        synchronized (simulcastReceivers)
        {
            Integer oldOverrideOrder
                = SimulcastReceiver.initOptions.getOverrideOrder();

            if (oldOverrideOrder == null
                    || oldOverrideOrder.intValue() != overrideOrder)
            {
                SimulcastReceiver.initOptions.setOverrideOrder(overrideOrder);

                if (!simulcastReceivers.isEmpty())
                {
                    SimulcastReceiverOptions options
                        = new SimulcastReceiverOptions();

                    options.setOverrideOrder(overrideOrder);
                    for (SimulcastReceiver sr : simulcastReceivers.values())
                    {
                        sr.configure(options);
                    }
                }

                return true;
            }
            else
            {
                return false;
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
                return RTPTranslatorImpl.readInt(buffer, offset + 8);
        }
        return 0;
    }

    public void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers)
    {

        this.simulcastLayers = simulcastLayers;

        // FIXME(gp) use an event dispatcher or a thread pool.
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                firePropertyChange(SIMULCAST_LAYERS_PROPERTY, null, null);
            }
        }).start();

        if (logger.isDebugEnabled())
        {
            Map<String, Object> map = new HashMap<String, Object>(2);

            map.put("self", videoChannel.getEndpoint());
            map.put("simulcastLayers", mapper.toJson(simulcastLayers));

            StringCompiler sc = new StringCompiler(map);

            logger.debug(sc.c("{self.id} signals {simulcastLayers}"));
        }
    }
}
