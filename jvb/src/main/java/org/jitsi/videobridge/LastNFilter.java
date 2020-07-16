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

import java.util.*;
import java.util.stream.*;

/**
 * This class is somewhat a placeholder for now--I'm not sure if this is how
 * we'll end up implementing this.  The idea is to have this class be used by
 * the endpoint and act as the 'first line of defense' when the ep is deciding
 * whether or not it 'wants' a packet (i.e. deciding if a packet that came from
 * some source EP should be forwarded to this ep).  This decision will take
 * place at multiple levels, but lastn is one of them.  This class will store
 * the last-n logic that was previously held in videochannel.
 */
public class LastNFilter
{
    private final String myEndpointId;

    // Right now both 'null' and '-1' values will 'disable' lastN
    private Integer lastNValue = -1;
    private List<String> endpointsSortedByActivity;

    /**
     * Initializes a new {@link LastNFilter} instance.
     */
    public LastNFilter(String myEndpointId)
    {
        this.myEndpointId = myEndpointId;
    }

    /**
     * Sets the LastN value.
     */
    public void setLastNValue(Integer lastNValue)
    {
        this.lastNValue = lastNValue;
    }

    /**
     * Gets the LastN value.
     */
    public Integer getLastNValue()
    {
        return lastNValue;
    }

    /**
     * Updates the list of conference endpoints (ordered by speech activity).
     */
    public void setEndpointsSortedByActivity(
            List<String> endpointsSortedByActivity)
    {
        this.endpointsSortedByActivity = endpointsSortedByActivity
                .stream()
                .filter(epId -> !epId.equals(myEndpointId))
                .collect(Collectors.toList());
    }

    /**
     * Checks whether this {@link LastNFilter} should accept packets from a
     * specific endpoint.
     * @param packetSourceEndpointId the ID of the endpoint.
     */
    public boolean wants(String packetSourceEndpointId)
    {
        return (lastNValue == null ||
                lastNValue < 0 ||
                (endpointsSortedByActivity.indexOf(packetSourceEndpointId) < lastNValue));
    }
}
