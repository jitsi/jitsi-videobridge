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
    }

    /**
     * Returns a boolean indicating whether the caller must drop, or accept, the
     * packet passed in as a parameter.
     *
     * @param pkt the packet to drop or accept.
     * @return true if the packet is to be accepted, false otherwise.
     */
    public boolean accept(RawPacket pkt)
    {
        // Find the associated <tt>SimulcastReceiver</tt> and make sure it
        // receives simulcast, otherwise return the input packet as is.
        int ssrc = pkt.getSSRC();
        SimulcastReceiver simulcastReceiver = getSimulcastReceiver(ssrc);

        if (simulcastReceiver == null || !simulcastReceiver.isSimulcastSignaled())
        {
            // Just forward the packet, we don't receive any simulcast from
            // the peer endpoint.
            return true;
        }

        SimulcastSender simulcastSender
            = getOrCreateSimulcastSender(simulcastReceiver);

        return (simulcastSender != null && simulcastSender.accept(pkt))
            ? true : false;
    }

    /**
     * Determines which simulcast simulcast stream from the srcVideoChannel is
     * currently being received by this video channel.
     *
     * @param simulcastReceiver
     * @return
     */
    private synchronized SimulcastSender getOrCreateSimulcastSender(
        SimulcastReceiver simulcastReceiver)
    {
        if (simulcastReceiver == null || !simulcastReceiver.isSimulcastSignaled())
        {
            return null;
        }

        SimulcastSender simulcastSender;
        if (!this.senders.containsKey(simulcastReceiver))
        {
            // Create a new sender.
            simulcastSender = new SimulcastSender(this, simulcastReceiver);

            // TODO remove stuff from the map (not strictly necessary as they'll
            // get garbage collected).
            this.senders.put(simulcastReceiver, simulcastSender);
        }
        else
        {
            // Get the receiver that handles this peer simulcast
            // manager
            simulcastSender = this.senders.get(simulcastReceiver);
        }

        return simulcastSender;
    }

    /**
     * @param ssrc
     * @return
     */
    private SimulcastReceiver getSimulcastReceiver(int ssrc)
    {
        Channel channel = simulcastEngine.getVideoChannel()
            .getContent().findChannel(ssrc & 0xffffffffl);

        if (!(channel instanceof VideoChannel))
        {
            // This line should be unreachable, in other words, this is a nice
            // place to add a Tetris launcher.
            return null;
        }

        VideoChannel videoChannel = (VideoChannel) channel;

        SimulcastReceiver simulcastReceiver = videoChannel.getTransformEngine()
            .getSimulcastEngine().getSimulcastReceiver();

        return simulcastReceiver;
    }
}
