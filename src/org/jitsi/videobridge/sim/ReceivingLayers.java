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
    private WeakReference<SimulcastLayer> current;

    /**
     *
     */
    private WeakReference<SimulcastLayer> previous;

    /**
     *
     */
    private int seenCurrent;

    /**
     *
     */
    private static final int MAX_CURRENT_SEEN = 250; // < 5 seconds approx.

    /**
     *
     * @return
     */
    public SimulcastLayer getCurrent()
    {
        SimulcastLayer layer = null;

        if (current != null)
        {
            layer = current.get();
        }

        return layer;
    }

    /**
     *
     * @return
     */
    private SimulcastLayer getPrevious()
    {
        SimulcastLayer layer = null;

        if (previous != null)
        {
            layer = previous.get();
        }

        return layer;
    }

    /**
     *
     * @param newCurrent
     */
    public synchronized void setCurrent(SimulcastLayer newCurrent)
    {
        SimulcastLayer oldCurrent = getCurrent();

        // If the layer we receive has changed, then continue streaming the
        // previous layer for a short period of time while the client
        // receives adjusts its video.
        if (oldCurrent != newCurrent)
        {
            // Update the previously receiving layer reference.
            this.previous = this.current;

            // Update the currently received layer reference.
            this.current = new WeakReference<SimulcastLayer>(newCurrent);

            // Since the currently received layer has changed, reset the
            // seenCurrent counter.
            this.seenCurrent = 0;

            // Log/dump the current state of things.
            if (logger.isInfoEnabled()
                    && source.get() != null && target.get() != null)
            {
                if (this.current == null || this.current.get() == null)
                {
                    logger.info(new StringBuilder()
                            .append(target.get().getID())
                            .append(" now does not receive a current layer ")
                            .append("from ")
                            .append(source.get().getID())
                            .append("."));

                }
                else
                {
                    logger.info(new StringBuilder()
                            .append(target.get().getID())
                            .append(" now receives current layer")
                            .append(" with SSRC ")
                            .append(this.current.get().getPrimarySSRC())
                            .append(" of order ")
                            .append(this.current.get().getOrder())
                            .append(" from ")
                            .append(source.get().getID())
                            .append("."));
                }

                if (this.previous == null || this.previous.get() == null)
                {
                    logger.info(new StringBuilder()
                            .append(target.get().getID())
                            .append(" now does not receive a previous layer ")
                            .append("from ")
                            .append(source.get().getID())
                            .append("."));
                }
                else
                {
                    logger.info(new StringBuilder()
                            .append(target.get().getID())
                            .append(" now receives previous layer")
                            .append(" with SSRC ")
                            .append(this.previous.get().getPrimarySSRC())
                            .append(" of order ")
                            .append(this.previous.get().getOrder())
                            .append(" from ")
                            .append(source.get().getID())
                            .append("."));
                }
            }
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

        SimulcastLayer current = getCurrent();
        if (current != null)
        {
            accept = current.accept(ssrc);
        }

        // The previous layer *has* to timeout at some point in the future
        // (requirement).
        //
        // If we don't expect any packets (current == null), then attempt to
        // timeout the previous layer.
        //
        // If we expect packets (current != null) and the just received packet
        // packet is from the current layer, then attempt to timeout the
        // previous layer.

        if (current == null || accept)
        {
            maybeTimeoutPrevious();
        }

        SimulcastLayer previous = getPrevious();
        if (!accept && previous != null)
        {
            accept = previous.accept(ssrc);
        }

        return accept;
    }

    /**
     *
     */
    private synchronized void maybeTimeoutPrevious()
    {
        SimulcastLayer previous = getPrevious();

        // If there is a previous layer to timeout, and we have received
        // "enough" packets from the current layer, expire the previous layer.
        if (previous != null)
        {
            seenCurrent++;
            if (seenCurrent > MAX_CURRENT_SEEN)
            {
                if (logger.isInfoEnabled()
                        && source.get() != null && target.get() != null)
                {
                    logger.info(new StringBuilder()
                            .append(target.get().getID())
                            .append(" stopped receiving previous layer from ")
                            .append(source.get().getID())
                            .append(" with SSRC ")
                            .append(previous.getPrimarySSRC())
                            .append(" of order ")
                            .append(previous.getOrder())
                            .append("."));
                }

                this.previous = null;
            }
        }
    }
}
