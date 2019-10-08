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

package org.jitsi.videobridge.octo.config;

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.util.config.*;

import static org.jitsi.videobridge.stats.config.TypesafeConfigUtils.*;

public class OctoConfig
{
    /**
     * A singleton property representing the octo's region
     */
    public static class RegionProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.REGION";
        protected static final String propName = "videobridge.octo.region";

        protected RegionProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> config.getString(propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static RegionProperty region = new RegionProperty();

    /**
     * The name of the configuration property which controls the address on
     * which the Octo relay should bind.
     */
    public static class BindAddress extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.octo.BIND_ADDRESS";
        protected static final String propName = "videobridge.octo.bind-address";

        protected BindAddress()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> getStringOrNull(config, propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static BindAddress bindAddress = new BindAddress();

    /**
     * The name of the configuration property which controls the public address
     * which will be used as part of relayId.
     */
    public static class PublicAddress extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.octo.PUBLIC_ADDRESS";
        protected static final String propName = "videobridge.octo.public-address";

        protected PublicAddress()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> getStringOrNull(config, propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static PublicAddress publicAddress = new PublicAddress();

    /**
     * The name of the property which controls the port number which the Octo
     * relay should use.
     */
    public static class Port extends AbstractConfigProperty<UnprivilegedPort>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.octo.BIND_PORT";
        protected static final String propName = "videobridge.octo.port";

        protected Port()
        {
            super(new JvbPropertyConfig<UnprivilegedPort>()
                .fromLegacyConfig(config -> new UnprivilegedPort(config.getInt(legacyPropName)))
                .fromNewConfig(config -> new UnprivilegedPort(config.getInt(propName)))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static Port port = new Port();
}
