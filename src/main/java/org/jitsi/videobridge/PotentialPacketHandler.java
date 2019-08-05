/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge;

import org.jitsi.nlj.*;

public interface PotentialPacketHandler
{
    /**
     * Checks whether or not this PotentialPacketHandler is interested
     * in the RTP/RTCP packet 'packet' from 'source'
     * @param packet the RTP/RTCP packet
     * @return true if this handler wants the given packet, false otherwise
     */
    boolean wants(PacketInfo packet);

    /**
     * Send the given RTP/RTCP 'packet' (which came from 'source')
     * @param packet the RTP/RTCP packet
     */
    void send(PacketInfo packet);
}
