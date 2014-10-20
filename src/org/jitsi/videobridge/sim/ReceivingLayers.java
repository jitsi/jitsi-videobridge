/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.lang.ref.*;

/**
* Created by gp on 20/10/14.
*/
class ReceivingLayers
{
    public ReceivingLayers(Endpoint source, Endpoint target)
    {
        this.source = new WeakReference<Endpoint>(source);
        this.target = new WeakReference<Endpoint>(target);
    }

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(ReceivingLayers.class);

    /**
     *
     */
    private final WeakReference<Endpoint> source;

    /**
     *
     */
    private final WeakReference<Endpoint> target;

    /**
     *
     */
    private SimulcastLayer current;

    /**
     *
     */
    private SimulcastLayer previous;

    /**
     *
     */
    private int currentCount;

    /**
     *
     */
    private static final int MAX_CURRENT_COUNT = 100;

    /**
     *
     * @return
     */
    public SimulcastLayer getCurrent()
    {
        return current;
    }

    /**
     *
     * @param current
     */
    public synchronized void setCurrent(SimulcastLayer current)
    {
        if (this.current != current)
        {
            this.current = current;

            if (logger.isInfoEnabled()
                    && source.get() != null && target.get() != null)
            {
                logger.info(new StringBuilder()
                        .append(target.get().getID())
                        .append(" now receives SSRC ")
                        .append(current.getPrimarySSRC())
                        .append(" of order ")
                        .append(current.getOrder())
                        .append(" from ")
                        .append(source.get().getID())
                        .append("."));
            }

            this.maybeRotateLayers();
        }
    }

    /**
     *
     */
    private synchronized void maybeRotateLayers()
    {
        // Rotate.
        if (this.current != null && this.previous == null)
        {
            if (logger.isInfoEnabled()
                    && source.get() != null && target.get() != null)
            {
                logger.info(new StringBuilder()
                        .append(target.get().getID())
                        .append(" rotates the layers it ")
                        .append("receives from ")
                        .append(source.get().getID())
                        .append("."));
            }

            this.previous = this.current;
            this.currentCount = 0;
        }
    }

    /**
     *
     * @param ssrc
     * @return
     */
    public synchronized boolean accept(long ssrc)
    {
        boolean accept = false;

        if (current != null)
        {
            accept = current.accept(ssrc);

            // Expire previous.
            if (accept && this.previous != null)
            {
                timeoutPrevious();
            }
        }

        if (!accept && previous != null)
        {
            accept = previous.accept(ssrc);
        }

        return accept;
    }

    /**
     *
     */
    private synchronized void timeoutPrevious()
    {
        currentCount++;
        if (currentCount > MAX_CURRENT_COUNT
                // If previous == current, we don't to timeout the current!
                && this.previous != this.current)
        {
            if (logger.isInfoEnabled()
                    && source.get() != null && target.get() != null)
            {
                logger.info(new StringBuilder()
                        .append(target.get().getID())
                        .append(" stopped receiving SSRC ")
                        .append(this.previous.getPrimarySSRC())
                        .append(" of order ")
                        .append(this.previous.getOrder())
                        .append(" that it was previously receiving from ")
                        .append(source.get().getID())
                        .append("."));
            }

            this.previous = null;
        }
    }
}
