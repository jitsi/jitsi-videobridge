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

package org.jitsi.videobridge.stats.config;

import org.jitsi.utils.collections.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;

import static org.jitsi.videobridge.stats.config.TypesafeConfigUtils.*;

public class CallStatsIoTransportConfig
{
    /**
     * The callstats AppID.
     */
    public static class AppIdProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.appId";
        protected static final String propName = "videobridge.callstats-io.app-id";

        protected AppIdProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> config.getInt(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static AppIdProperty appId = new AppIdProperty();

    /**
     * Shared Secret for authentication on Callstats.io
     */
    public static class AppSecretProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.appSecret";
        protected static final String propName = "videobridge.callstats-io.app-secret";

        protected AppSecretProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> getStringOrNull(config, propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static AppSecretProperty appSecret = new AppSecretProperty();

    /**
     * ID of the key that was used to generate token.
     */
    public static class KeyIdProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.keyId";
        protected static final String propName = "videobridge.callstats-io.key-id";

        protected KeyIdProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> getStringOrNull(config, propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static KeyIdProperty keyId = new KeyIdProperty();

    /**
     * The path to private key file.
     */
    public static class KeyPathProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.keyPath";
        protected static final String propName = "videobridge.callstats-io.key-path";

        protected KeyPathProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> getStringOrNull(config, propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static KeyPathProperty keyPath = new KeyPathProperty();

    /**
     * The bridge id to report to callstats.io.
     */
    public static class BridgeIdProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.bridgeId";
        protected static final String propName = "videobridge.callstats-io.bridge-id";

        protected BridgeIdProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> config.getString(propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static BridgeIdProperty bridgeId = new BridgeIdProperty();

    /**
     * The bridge conference prefix to report to callstats.io.
     */
    public static class ConferenceIdPrefixProperty extends AbstractConfigProperty<String>
    {
        protected static final String legacyPropName = "io.callstats.sdk.CallStats.conferenceIDPrefix";
        protected static final String propName = "videobridge.callstats-io.conference-id-prefix";

        protected ConferenceIdPrefixProperty()
        {
            super(new JvbPropertyConfig<String>()
                .fromLegacyConfig(config -> config.getString(legacyPropName))
                .fromNewConfig(config -> config.getString(propName))
                .readOnce()
                .returnNullIfNotFound()
            );
        }
    }

    public static ConferenceIdPrefixProperty conferenceIdPrefix = new ConferenceIdPrefixProperty();
}
