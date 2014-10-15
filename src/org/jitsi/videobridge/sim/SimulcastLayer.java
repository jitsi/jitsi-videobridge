/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

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

    public static final String IS_STREAMING_PROPERTY = "isStreaming";

    private int counter = 0;

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
        return this.counter > 0;
    }

    public synchronized void starve()
    {
        int oldValue = this.counter;
        this.counter--;

        if (oldValue > 0 && this.counter < 1)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(new StringBuilder()
                        .append("Starved ")
                        .append(getPrimarySSRC()).toString());
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
    }

    private static final int FEED_COUNT = 10;

    public synchronized void touch()
    {
        int oldValue = this.counter;
        this.counter = FEED_COUNT;

        if (oldValue < 1)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(new StringBuilder()
                        .append("Fed ")
                        .append(getPrimarySSRC()).toString());
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
