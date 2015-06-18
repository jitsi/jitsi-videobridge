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

import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;

/**
 * @author George Politis
 */
public class SimulcastLayer
    extends PropertyChangeNotifier
        implements Comparable<SimulcastLayer>
{
    private static final int MAX_SEEN_BASE = 25;

    /**
     * The length in milliseconds of the interval for which the average incoming
     * bitrate for this video channel will be computed and made available
     * through {@link #getBitrate}.
     */
    private static final int INCOMING_BITRATE_INTERVAL_MS = 5000;

    /**
     * The instance which will be computing the incoming bitrate for this
     * <tt>SimulcastLayer</tt>.
     */
    private final RateStatistics rateStatistics
            = new RateStatistics(INCOMING_BITRATE_INTERVAL_MS, 8000F);

    public SimulcastManager getSimulcastManager()
    {
        return simulcastManager;
    }

    private final SimulcastManager simulcastManager;

    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastLayer</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastLayer.class);

    public static final String IS_STREAMING_PROPERTY =
            SimulcastLayer.class.getName() + ".isStreaming";

    private int seenHigh = -1;

    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    private final long primarySSRC;
    private final Set<Long> associatedSSRCs = new HashSet<Long>();

    public int getOrder()
    {
        return order;
    }

    private final int order;

    public SimulcastLayer(SimulcastManager manager, long primarySSRC, int order)
    {
        this.simulcastManager = manager;
        this.primarySSRC = primarySSRC;
        this.order = order;
    }

    public boolean accept(long ssrc)
    {
        // FIXME(gp) longs or an ints.. everywhere

        return (ssrc == primarySSRC
                || (associatedSSRCs != null
                    && associatedSSRCs.contains(Long.valueOf(ssrc))));
    }

    public void associateSSRCs(Set<Long> ssrcs)
    {
        associatedSSRCs.addAll(ssrcs);
    }

    @Override
    public int compareTo(SimulcastLayer o)
    {
        return order - o.order;
    }

    public List<Long> getAssociatedSSRCs()
    {
        return new ArrayList<Long>(associatedSSRCs);
    }

    public boolean isStreaming()
    {
        // NOTE(gp) we assume that the base layer is always streaming.
        return isStreaming ? isStreaming : order == 0;
    }

    private boolean isStreaming = false;

    private int seenBase = 0;

    public synchronized void maybeTimeout()
    {
        if (++seenBase % MAX_SEEN_BASE == 0)
        {
            // Every base layer packet we have observed 10 low quality packets.
            //
            // If for every MAX_SEEN_BASE base quality packets we have not seen
            // at least one high quality packet, then the high quality layer
            // must have been dropped (this means approximately MAX_SEEN_BASE*10
            // packets loss).

            if (this.isStreaming &&  this.seenHigh == 0)
            {
                this.isStreaming = false;

                if (logger.isDebugEnabled())
                {
                    Map<String, Object> map = new HashMap<String, Object>(2);
                    map.put("parent", getSimulcastManager().getVideoChannel()
                            .getEndpoint());
                    map.put("self", this);
                    StringCompiler sc = new StringCompiler(map);

                    logger.debug(sc.c("{parent.id} stopped streaming its " +
                            "order-{self.order} layer ({self.primarySSRC})."));
                }

                // FIXME(gp) use an event dispatcher or a thread pool.
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        firePropertyChange(IS_STREAMING_PROPERTY, true, false);
                    }
                }).start();
            }

            this.seenHigh = 0;
        }
    }

    public synchronized void touch()
    {
        this.seenHigh++;

        if (!this.isStreaming)
        {
            // Do not activate the hq stream if the bitrate estimation is not
            // above 300kbps.

            this.isStreaming = true;

            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(2);
                map.put("parent", getSimulcastManager().getVideoChannel()
                        .getEndpoint());
                map.put("self", this);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("{parent.id} started streaming its " +
                        "order-{self.order} layer ({self.primarySSRC})."));
            }

            // FIXME(gp) use an event dispatcher or a thread pool.
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    firePropertyChange(IS_STREAMING_PROPERTY, false, true);
                }
            }).start();
        }

    }

    /**
     * The length in milliseconds of the interval for which the average incoming
     * bitrate for this video channel will be computed and made available
     * through {@link #getBitrate}.
     */
    public long getBitrate()
    {
        return rateStatistics.getRate(System.currentTimeMillis());
    }

    public void acceptedDataInputStreamDatagramPacket(DatagramPacket p)
    {
        if (p != null)
        {
            rateStatistics.update(p.getLength(), System.currentTimeMillis());
        }
    }
}
