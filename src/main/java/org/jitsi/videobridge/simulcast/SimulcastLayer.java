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

import java.util.*;
import java.util.concurrent.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;

/**
 * The <tt>SimulcastLayer</tt> of a <tt>SimulcastReceiver</tt> represents a
 * simulcast layer. It determines when a simulcast layer has been
 * stopped/started and fires a property change event when that happens. It also
 * gathers bitrate statistics for the associated layer.
 *
 * This class is thread safe.
 *
 * @author George Politis
 */
public class SimulcastLayer
    extends PropertyChangeNotifier
    implements Comparable<SimulcastLayer>
{

    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastLayer</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastLayer.class);

    /**
     * The name of the property that gets fired when this layer stop or starts
     * streaming.
     */
    public static final String IS_STREAMING_PNAME =
            SimulcastLayer.class.getName() + ".isStreaming";

    /**
     * Base layer quality order.
     */
    public static final int SIMULCAST_LAYER_ORDER_LQ = 0;

    /**
     * High quality layer order.
     */
    public static final int SIMULCAST_LAYER_ORDER_HQ = 1;

    /**
     * The number of packets of the base layer that we must see before marking
     * this substream as stopped.
     *
     * XXX we expect that this needs to take into account not the base layer,
     * but the previous layer (order-1) in the sorted set of simulcast layers.
     * TAG(simulcast-assumption,arbitrary-sim-layers). We can easily get the
     * previous item in a treeset, so it might be a good idea to bake a
     * structure instead of using the stock one.
     */
    private static final int MAX_SEEN_BASE = 25;

    /**
     * The pool of threads utilized by <tt>SimulcastReceiver</tt>.
     */
    private static final ExecutorService executorService = ExecutorUtils
        .newCachedThreadPool(true, SimulcastLayer.class.getName());

    /**
     * The <tt>SimulcastReceiver</tt> that owns this layer.
     */
    private final SimulcastReceiver simulcastReceiver;

    /**
     * The primary SSRC for this simulcast layer.
     */
    private final long primarySSRC;

    /**
     * The RTX SSRC for this simulcast layer.
     */
    private long rtxSSRC;

    /**
     * The FEC SSRC for this simulcast layer.
     *
     * XXX This isn't currently used anywhere because Chrome doens't use a
     * separete SSRC for FEC.
     */
    private long fecSSRC;

    /**
     * The order of this simulcast layer.
     */
    private final int order;

    /**
     * Holds a boolean indicating whether or not this layer is streaming.
     */
    private boolean isStreaming = false;

    /**
     * How many high quality packets have we seen for this layer. We increase
     * this in the touch method.
     *
     * XXX once we support arbitrary number of simulcast layers this will have
     * to somehow change. TAG(arbitrary-sim-layers)
     */
    private int seenHigh = -1;

    /**
     * How many low quality packets have we seen for the base layer. We increase
     * this in the maybeTimeout method.
     *
     * XXX once we support arbitrary number of simulcast layers this will have
     * to somehow change. TAG(arbitrary-sim-layers)
     */
    private int seenBase = 0;

    /**
     * Ctor.
     *
     * @param primarySSRC
     * @param order
     */
    public SimulcastLayer(
        SimulcastReceiver simulcastReicever, long primarySSRC, int order)
    {
        this.simulcastReceiver = simulcastReicever;
        this.primarySSRC = primarySSRC;
        this.order = order;
    }

    /**
     * Gets the primary SSRC for this simulcast layer.
     *
     * @return the primary SSRC for this simulcast layer.
     */
    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    /**
     * Gets the RTX SSRC for this simulcast layer.
     *
     * @return the RTX SSRC for this simulcast layer.
     */
    public long getRTXSSRC()
    {
        return rtxSSRC;
    }

    /**
     * Gets the FEC SSRC for this simulcast layer.
     *
     * @return the FEC SSRC for this simulcast layer.
     */
    public long getFECSSRC()
    {
        return fecSSRC;
    }

    /**
     * Sets the RTX SSRC for this simulcast layer.
     *
     * @param rtxSSRC the new RTX SSRC for this simulcast layer.
     */
    public void setRTXSSRC(long rtxSSRC)
    {
        this.rtxSSRC = rtxSSRC;
    }

    /**
     * Sets the FEC SSRC for this simulcast layer.
     *
     * @param fecSSRC the new FEC SSRC for this simulcast layer.
     */
    public void setFECSSRC(long fecSSRC)
    {
        this.fecSSRC = fecSSRC;
    }

    /**
     * Gets the order of this simulcast layer.
     *
     * @return the order of this simulcast layer.
     */
    public int getOrder()
    {
        return order;
    }

    @Override
    public String toString()
    {
        return "[ssrc: " + getPrimarySSRC() + ", order: " + getOrder() + "]";
    }

    /**
     * Determines whether a packet belongs to this simulcast layer or not and
     * returns a boolean to the caller indicating that.
     *
     * @param pkt
     * @return true if the packet belongs to this simulcast layer, false
     * otherwise.
     */
    public boolean match(RawPacket pkt)
    {
        long ssrc = pkt.getSSRC() & 0xffffffffl;
        return ssrc == primarySSRC || ssrc == rtxSSRC || ssrc == fecSSRC;
    }

    /**
     * Compares this simulcast layer with another, implementing an order.
     *
     * @param o
     * @return
     */
    public int compareTo(SimulcastLayer o)
    {
        return order - o.order;
    }

    /**
     *
     * Gets a boolean indicating whether or not this layer is streaming.
     *
     * @return true if this layer is streaming, false otherwise.
     */
    public boolean isStreaming()
    {
        // NOTE(gp) we assume 1. that the base layer is always streaming, and
        // 2. if layer N is streaming, then layers N-1 is streaming. N == order
        // in this class TAG(simulcast-assumption,arbitrary-sim-layers).
        return isStreaming ? isStreaming : order == 0;
    }

    /**
     * Increases the number of base layer packets that we've seen, and
     * potentially marks this layer as stopped and fires an event.
     */
    public synchronized void maybeTimeout()
    {
        if (++seenBase % MAX_SEEN_BASE != 0)
        {
            return;
        }

        // Every base layer packet we have observed 10 low quality packets.
        //
        // If for every MAX_SEEN_BASE base quality packets we have not seen
        // at least one high quality packet, then the high quality layer
        // must have been dropped (this means approximately MAX_SEEN_BASE*10
        // packets loss).

        if (this.isStreaming && this.seenHigh == 0)
        {
            this.isStreaming = false;

            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", this);
                StringCompiler sc = new StringCompiler(map);

                logDebug(sc.c("order-{self.order} layer " +
                        "({self.primarySSRC}) stopped.").toString());
            }

            executorService.execute(new Runnable()
            {
                public void run()
                {
                    firePropertyChange(IS_STREAMING_PNAME, true, false);
                }
            });
        }

        this.seenHigh = 0;
    }

    /**
     * Increases the number of packets of this layer that we've seen, and
     * potentially marks this layer as started and fires an event.
     */
    public synchronized void touch()
    {
        this.seenHigh++;

        if (this.isStreaming)
        {
            return;
        }

        // Do not activate the hq stream if the bitrate estimation is not
        // above 300kbps.

        this.isStreaming = true;

        if (logger.isDebugEnabled())
        {
            Map<String, Object> map = new HashMap<String, Object>(1);
            map.put("self", this);
            StringCompiler sc = new StringCompiler(map);

            logDebug(sc.c("order-{self.order} layer " +
                    "({self.primarySSRC}) resumed.").toString());
        }

        executorService.execute(new Runnable()
        {
            public void run()
            {
                firePropertyChange(IS_STREAMING_PNAME, false, true);
            }
        });
    }

    /**
     *
     * @param pkt the packet to check
     * @return true if the pkt is a keyframe, false otherwise
     */
    public boolean isKeyFrame(RawPacket pkt)
    {
        // TODO check if we have something like that already coded.
        return true;
    }

    /**
     * Utility method that asks for a keyframe for a specific simulcast layer.
     * This is typically done when switching layers.
     */
    public void askForKeyframe()
    {
        SimulcastEngine peerSM = simulcastReceiver.getSimulcastEngine();
        if (peerSM == null)
        {
            logWarn("Requested a key frame but the peer simulcast " +
                    "manager is null!");
            return;
        }

        peerSM.getVideoChannel().askForKeyframes(
                new int[]{(int) getPrimarySSRC()});
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg = simulcastReceiver.getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }
}
