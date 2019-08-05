/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import org.ice4j.ice.harvest.*;

/**
 * @deprecated The functionality in this class has been moved to ice4j. This is
 * kept only for backward compatibility.
 */
@Deprecated
public class HarvesterConfiguration
{
    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the local
     * address that we should be mapping.
     * @deprecated Use
     * {@link MappingCandidateHarvesters#NAT_HARVESTER_LOCAL_ADDRESS_PNAME}
     */
    @Deprecated
    public static final String NAT_HARVESTER_LOCAL_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS";

    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the public
     * address that we should be using in addition to our local one.
     * @deprecated Use
     * {@link MappingCandidateHarvesters#NAT_HARVESTER_PUBLIC_ADDRESS_PNAME}
     */
    @Deprecated
    public static final String NAT_HARVESTER_PUBLIC_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be explicitly disabled.
     * @deprecated Use
     * {@link MappingCandidateHarvesters#DISABLE_AWS_HARVESTER_PNAME}
     */
    @Deprecated
    public static final String DISABLE_AWS_HARVESTER
        = "org.jitsi.videobridge.DISABLE_AWS_HARVESTER";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be forced without first trying to auto detect it.
     * @deprecated Use
     * {@link MappingCandidateHarvesters#FORCE_AWS_HARVESTER_PNAME}
     */
    @Deprecated
    public static final String FORCE_AWS_HARVESTER
        = "org.jitsi.videobridge.FORCE_AWS_HARVESTER";

    /**
     * String containing one or more stun server addresses in the form:
     * server_address:port, separated by commas.
     * Setting this property enables searching for our public address using
     * the specified stun servers.
     * @deprecated Use
     * {@link MappingCandidateHarvesters#STUN_MAPPING_HARVESTER_ADDRESSES_PNAME}
     */
    @Deprecated
    public static final String STUN_MAPPING_HARVESTER_ADDRESSES
        = "org.jitsi.videobridge.STUN_MAPPING_HARVESTER_ADDRESSES";
}
