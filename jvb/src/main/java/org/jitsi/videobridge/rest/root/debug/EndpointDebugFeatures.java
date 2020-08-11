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

public enum EndpointDebugFeatures
{
    PCAP_DUMP("pcap-dump");


    private final String value;

    EndpointDebugFeatures(String value)
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
    public static EndpointDebugFeatures fromString(String value)
    {
        String normalized = value.toUpperCase().replace("-", "_");
        return EndpointDebugFeatures.valueOf(normalized);
    }
}
