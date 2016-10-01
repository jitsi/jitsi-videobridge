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
package org.jitsi.videobridge.simulcast.sendmodes;

import org.jitsi.impl.neomedia.*;
import org.jitsi.videobridge.simulcast.*;

/**
 * Is configured to accept a target simulcast stream and tries its best to
 * accept (only) that simulcast simulcast stream.
 *
 * @author George Politis
 */
public abstract class SendMode
{
    /**
     * The <tt>SimulcastSender</tt> that owns this instance.
     */
    private final SimulcastSender simulcastSender;

    /**
     * Ctor.
     *
     * @param simulcastSender
     */
    public SendMode(SimulcastSender simulcastSender)
    {
        this.simulcastSender = simulcastSender;
    }

    /**
     * Gets the <tt>SimulcastSender</tt> that owns this instance.
     *
     * @return the <tt>SimulcastSender</tt> that owns ths instance.
     */
    public SimulcastSender getSimulcastSender()
    {
        return this.simulcastSender;
    }

    /**
     *
     * @param targetOrder
     */
    public void receive(int targetOrder)
    {
        SimulcastStream closestMatch = this.simulcastSender
            .getSimulcastReceiver().getSimulcastStream(
                targetOrder, simulcastSender.getSimulcastSenderManager()
                    .getSimulcastEngine().getVideoChannel().getStream());

        receive(closestMatch);
    }
    /**
     * Gets a boolean indicating whether the packet has to be accepted or not.
     *
     * @param pkt the packet that is to be accepted or not.
     * @return true if the packet is to be accepted, false otherwise.
     */
    public abstract boolean accept(RawPacket pkt);

    /**
     * Configures this mode to receive the low quality stream.
     */
    public abstract void receive(SimulcastStream simStream);
}
