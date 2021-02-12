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

package org.jitsi.videobridge.rest.root.debug;

/**
 * Enumerates different debug features which can be enabled and disabled via
 * the debug REST API.
 */
public enum DebugFeatures
{
    PAYLOAD_VERIFICATION("payload-verification"),
    NODE_STATS("node-stats"),
    POOL_STATS("pool-stats"),
    POOL_BOOKKEEPING("pool-bookkeeping"),
    QUEUE_STATS("queue-stats"),
    TRANSIT_STATS("transit-stats"),
    TASK_POOL_STATS("task-pool-stats"),
    NODE_TRACING("node-tracing"),
    XMPP_DELAY_STATS("xmpp-delay-stats");

    private final String value;

    DebugFeatures(String value)
    {
        this.value = value;
    }

    public String getValue()
    {
        return this.value;
    }

    /**
     * A custom 'fromString' implementation which allows creating an instance of
     * this enum from its value.  This assumes that the String values are derived using
     * the following transformation of the 'keys':
     * 1) lower case
     * 2) underscores are replaced with hyphens
     *
     * @param value the String value of the enum
     * @return an instance of the enum, if one can be derived by reversing the transformation
     * detailed above
     */
    public static DebugFeatures fromString(String value)
    {
        String normalized = value.toUpperCase().replace("-", "_");
        return DebugFeatures.valueOf(normalized);
    }
}
