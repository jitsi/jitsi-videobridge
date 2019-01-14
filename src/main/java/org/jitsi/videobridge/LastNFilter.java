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

import java.util.*;

/**
 * This class is somewhat a placeholder for now--I'm not sure if this is how we'll end up implementing this.  The idea
 * is to have this class be used by the endpoint and act as the 'first line of defense' when the ep is deciding
 * whether or not it 'wants' a packet (i.e. deciding if a packet that came from some source EP should be forwarded
 * to this ep).  This decision will take place at multiple levels, but lastn is one of them.  This class will store
 * the last-n logic that was previously held in videochannel.
 */
public class LastNFilter
{
    private int lastNValue = 0;
    private List<String> endpointsSortedByActivity;

    public void setLastNValue(int lastNValue)
    {
        this.lastNValue = lastNValue;
    }

    //TODO: this should be passed as an immutable list (Collections.unmodifiableList(original)).
    // is there any way we can enforce that?
    public void setEndpointsSortedByActivity(List<String> endpointsSortedByActivity)
    {
        this.endpointsSortedByActivity = endpointsSortedByActivity;
    }

    public boolean wants(PacketInfo packet)
    {
        //TODO: here we'll return whether or not we're interested in this packet based on which endpoint it belongs to
        // and whether or not that endpoint is within the lastn range.  I think we'll want to tag the packetinfo/packet
        // with the source endpoint id?
        return true;
    }
}
