/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;

import java.util.*;

/**
 * @author George Politis
 */
public class SimulcastLayer
    extends PropertyChangeNotifier
        implements Comparable<SimulcastLayer>
{
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

    public boolean contains(long ssrc)
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
        return isStreaming;
    }

    private boolean isStreaming = false;

    private int seenBase = 0;

    public synchronized void timeout()
    {
        if (++seenBase % 10 == 0)
        {
            // Every base layer packet we have observed 10 low quality packets.
            //
            // If for every 10 base quality packets we have not seen at least
            // one high quality packet, then the high quality layer must have
            // been dropped (this means approximately 100 packets loss).

            if (this.isStreaming &&  this.seenHigh == 0)
            {
                this.isStreaming = false;

                if (logger.isDebugEnabled())
                {
                    logger.debug(new StringBuilder()
                            .append(getSimulcastManager()
                                    .getVideoChannel()
                                    .getEndpoint()
                                    .getID())
                            .append(" stopped streaming ")
                            .append(getPrimarySSRC())
                            .append("."));
                }

                // FIXME(gp) use an event dispatcher.
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
                logger.debug(new StringBuilder()
                        .append(getSimulcastManager()
                                .getVideoChannel()
                                .getEndpoint()
                                .getID())
                        .append(" started streaming ")
                        .append(getPrimarySSRC())
                        .append(" again."));
            }

            // FIXME(gp) use an event dispatcher.
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
}
