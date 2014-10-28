/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import java.net.*;
import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.sim.messages.*;

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

    protected static final String SIMULCAST_LAYERS_PROPERTY =
            SimulcastManager.class.getName() + ".simulcastLayers";

    /**
     * Base layer quality order.
     */
    protected static final int SIMULCAST_LAYER_ORDER_LQ = 0;

    /**
     * High quality layer order.
     */
    protected static final int SIMULCAST_LAYER_ORDER_HQ = 1;

    /**
     * The associated <tt>VideoChannel</tt> of this simulcast manager.
     */
    private final VideoChannel videoChannel;

    /**
     * Defines the simulcast substream to receive, if there is no other
     */
    protected static final Integer SIMULCAST_LAYER_ORDER_INIT
            = SIMULCAST_LAYER_ORDER_LQ;

    /**
     * The <tt>simulcastLayers</tt> SyncRoot.
     */
    private final Object simulcastLayersSyncRoot = new Object();

    /**
     * The simulcast layers of this <tt>VideoChannel</tt>.
     *
     * TODO(gp) Move to SimulcastSender
     *
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
     *
     */
    private final Map<SimulcastManager, SimulcastReceiver> simulcastReceivers
            = new WeakHashMap<SimulcastManager, SimulcastReceiver>();

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     * TODO(gp) Move to SimulcastSender.
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
    }

    /**
     * Determines whether the packet belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @return
     */
    public boolean accept(byte[] buffer, int offset, int length,
                          VideoChannel peerVC)
    {
        boolean accept = true;

        SimulcastManager peerSM;
        if (peerVC != null
                && (peerSM = peerVC.getSimulcastManager()) != null
                && peerSM.hasLayers())
        {

            // FIXME(gp) inconsistent usage of longs and ints.

            // Get the SSRC of the packet.
            long ssrc = readSSRC(buffer, offset, length) & 0xffffffffl;

            if (ssrc > 0)
            {
                accept = accept(ssrc, peerVC);
            }
        }

        return accept;
    }

    /**
     * Determines whether the SSRC belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @param peerVC
     * @param ssrc
     * @return
     */
    public boolean accept(long ssrc,
                          VideoChannel peerVC)
    {
        boolean accept = true;

        SimulcastManager peerSM;
        if (ssrc > 0
                && peerVC != null
                && (peerSM = peerVC.getSimulcastManager()) != null
                && peerSM.hasLayers())
        {
            SimulcastReceiver sr = getOrCreateSimulcastReceiver(peerSM);

            if (sr != null)
            {
                accept = sr.accept(ssrc);
            }
        }

        return accept;
    }

    /**
     * Returns true if the endpoint has signaled two or more simulcast layers.
     *
     * @return
     */
    protected boolean hasLayers()
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
    private SimulcastReceiver getOrCreateSimulcastReceiver(
            SimulcastManager peerSM)
    {
        SimulcastReceiver sr = null;

        if (peerSM != null)
        {
            synchronized (simulcastReceivers)
            {
                if (!simulcastReceivers.containsKey(peerSM))
                {
                    // Create a new receiver.
                    sr = new SimulcastReceiver(this, peerSM);

                    // Initialize the receiver.
                    SimulcastReceiverOptions options
                            = new SimulcastReceiverOptions();

                    options.setTargetOrder(SIMULCAST_LAYER_ORDER_LQ);
                    options.setHardSwitch(false);
                    options.setUrgent(false);

                    sr.configure(options);

                    simulcastReceivers.put(peerSM, sr);
                }
                else
                {
                    // Get the receiver that handles this peer simulcast
                    // manager
                    sr = simulcastReceivers.get(peerSM);
                }
            }
        }

        return sr;
    }

    private final static SimulcastMessagesMapper mapper
            = new SimulcastMessagesMapper();

    public void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers)
    {
        synchronized (simulcastLayersSyncRoot)
        {
            this.simulcastLayers = simulcastLayers;
        }

        // FIXME(gp) use an event dispatcher or a thread pool.
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
}
