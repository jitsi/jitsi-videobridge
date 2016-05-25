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

import org.jitsi.impl.neomedia.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * The purpose of the <tt>SimulcastSenderManager</tt> is to encapsulate the
 * management of the <tt>SimulcastSender</tt>s (we keep a
 * <tt>SimulcastSender</tt> per <tt>SimulcastReceiver</tt>) and to simplify the
 * <tt>SimulcastEngine</tt>.
 *
 * @author George Politis
 * @author Boris Grozev
 */
public class SimulcastSenderManager
{
    /**
     * The order when there's no override simulcast stream.
     */
    public static final int SIMULCAST_LAYER_ORDER_NO_OVERRIDE = -1;

    /**
     * The <tt>SimulcastEngine</tt> that owns this instance.
     */
    private final SimulcastEngine simulcastEngine;

    /**
     * Associates <tt>SimulcastReceiver</tt>s of other
     * <tt>SimulcastEngine</tt>s with <tt>SimulcastSender</tt>s owned by this
     * <tt>SimulcastEngine</tt>. We keep them in a WeakHashMap so that we don't
     * block garbage collection of the <tt>SimulcastReceiver</tt> (that we
     * don't own).
     * Note that SimulcastSender-s need to be closed, but this is only important
     * while their corresponding SimulcastReceiver is in use. That is, if the
     * receiver is no longer in use, closing the SimulcastSender is not
     * important, so we can afford to lose reference to some SimulcastSender
     * instances.
     */
    private final Map<SimulcastReceiver, SimulcastSender> senders
        = new WeakHashMap<>();

    /**
     * Holds the override simulcast stream order for all the senders that this
     * sender manager manages.
     */
    private int overrideOrder = SIMULCAST_LAYER_ORDER_NO_OVERRIDE;

    /**
     * Ctor.
     *
     * @param simulcastEngine
     */
    public SimulcastSenderManager(SimulcastEngine simulcastEngine)
    {
        this.simulcastEngine = simulcastEngine;
    }

    /**
     * Gets the <tt>SimulcastEngine</tt> that owns this instance.
     *
     * @return the <tt>SimulcastEngine</tt> that owns this instance.
     */
    public SimulcastEngine getSimulcastEngine()
    {
        return simulcastEngine;
    }

    /**
     * Gets the override simulcast stream order.
     *
     * @return the override simulcast stream order.
     */
    public int getOverrideOrder()
    {
        return this.overrideOrder;
    }

    /**
     * Sets the override simulcast stream order.
     *
     * @param overrideOrder the new override simulcast stream order.
     */
    public void setOverrideOrder(int overrideOrder)
    {
       this.overrideOrder = overrideOrder;
        synchronized (this)
        {
            for (SimulcastSender sender : senders.values())
            {
                sender.overrideOrderChanged();
            }
        }
    }

    /**
     * Determines whether the caller must drop or accept a specific
     * {@code RawPacket}.
     *
     * @param pkt the packet to drop or accept.
     * @return {@code true} to accept {@code pkt}; {@code false}, otherwise.
     */
    public boolean accept(RawPacket pkt)
    {
        // Find the associated SimulcastReceiver and make sure it receives
        // simulcast; otherwise, return the input packet as is.
        Map.Entry<SimulcastReceiver, SimulcastSender> entry
            = getEntry(pkt.getSSRCAsLong());

        if (entry == null || !entry.getKey().isSimulcastSignaled())
        {
            // Just forward the packet, we don't receive any simulcast from
            // the peer endpoint.
            return true;
        }

        return entry.getValue().accept(pkt);
    }

    /**
     * Creates a {@link SimulcastSender} instance corresponding to the given
     * {@link SimulcastReceiver}. If a corresponding instance already exists
     * in this {@link SimulcastSenderManager}'s map, returns the existing
     * instance.
     * @param simulcastReceiver the {@link SimulcastReceiver} for which to
     * create a {@link SimulcastSender}.
     * @return the {@link SimulcastSender} instance corresponding to
     * {@code simulcastReceiver}.
     */
    public synchronized SimulcastSender createSimulcastSender(
        SimulcastReceiver simulcastReceiver)
    {
        if (simulcastReceiver == null)
        {
            return null;
        }

        SimulcastSender simulcastSender = senders.get(simulcastReceiver);
        if (simulcastSender == null) // Create a new sender.
        {
            VideoChannel videoChannel = simulcastEngine.getVideoChannel();
            int targetOrder = videoChannel.getReceiveSimulcastLayer();

            // Create a new sender.
            simulcastSender = new SimulcastSender(
                this,
                simulcastReceiver,
                targetOrder);

            Endpoint sendingEndpoint = simulcastReceiver
                .getVideoChannel()
                .getEndpoint();
            Endpoint receivingEndpoint = videoChannel.getEndpoint();

            if (receivingEndpoint != null && sendingEndpoint != null)
            {
                Set<Endpoint> selectedEndpoints
                    = receivingEndpoint.getSelectedEndpoints(); // never null.

                // Initialize the selected endpoints.
                simulcastSender.selectedEndpointsChanged(
                    new HashSet<Endpoint>(), selectedEndpoints);
            }

            // TODO remove stuff from the map (not strictly necessary as they'll
            // get garbage collected).
            senders.put(simulcastReceiver, simulcastSender);
        }

        return simulcastSender;
    }

    private Map.Entry<SimulcastReceiver,SimulcastSender> getEntry(long ssrc)
    {
        for (Map.Entry<SimulcastReceiver, SimulcastSender> entry
            : senders.entrySet())
        {
            if (entry.getKey().matches(ssrc))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * @return the highest "target order" of the senders of this {@link
     * SimulcastSenderManager}, whose highest stream is currently streaming.
     */
    public synchronized int getHighestStreamingTargetOrder()
    {
        int max = -1;

        for (Map.Entry<SimulcastReceiver, SimulcastSender> entry
                : senders.entrySet())
        {
            int senderTargetOrder = entry.getValue().getTargetOrder();
            if (senderTargetOrder >= max)
            {
                SimulcastStream ss
                    = entry.getKey().getSimulcastStream(senderTargetOrder);
                if (ss != null && ss.isStreaming())
                    max = senderTargetOrder;
            }
        }

        return max;
    }

    /**
     * Closes this {@link SimulcastSenderManager}.
     */
    public void close()
    {
        for (SimulcastSender sender : senders.values())
        {
            sender.close();
        }
    }
}
