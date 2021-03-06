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

package org.jitsi.videobridge.stats;

import org.jitsi.nlj.util.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

public class PacketTransitStats
{
    public static OrderedJsonObject getStatsJson()
    {
        OrderedJsonObject stats = new OrderedJsonObject();

        stats.put("e2e_packet_delay", EndpointK.getPacketDelayStats());
        stats.put(EndpointK.overallAverageBridgeJitter.name, EndpointK.overallAverageBridgeJitter.get());

        return stats;
    }
}
