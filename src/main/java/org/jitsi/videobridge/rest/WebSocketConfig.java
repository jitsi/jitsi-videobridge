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

package org.jitsi.videobridge.rest;

import com.typesafe.config.*;
import org.jitsi.videobridge.util.config.*;

public class WebSocketConfig
{
    /**
     * Whether or not the websocket service is enabled
     */
    public static class EnabledProperty
    {
        protected static final String legacyPropKey = "org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE";
        protected static final Boolean legacyDefault = false;
        protected static final String propKey = "videobridge.websockets.enabled";
        static ConfigProperty<Boolean> createInstance()
        {
            return new ConfigPropertyBuilder<Boolean>()
                .fromConfigs(
                    new DefaultConfigValueRetrieverBuilder<>("videobridge.websockets.enabled"),
                    new DefaultLegacyConfigValueRetrieverBuilder<Boolean>("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE")
                        // The legacy config value was 'DISABLE' and the new one is 'ENABLED', so if we pull the value
                        // from the legacy config, we need to invert it
                        .withTransformation((value) -> !value)
                )
                .usingGetter(Config::getBoolean)
                .withDefault(legacyDefault)
                .readOnce()
                .build();
        }
    }

    static ConfigProperty<Boolean> enabled = EnabledProperty.createInstance();

    /**
     * The property which controls the server ID used in URLs
     * advertised for COLIBRI WebSockets.
     */
    static ConfigProperty<String> serverId = new ConfigPropertyBuilder<String>()
            .fromConfigs(
                new DefaultConfigValueRetrieverBuilder<>("videobridge.websockets.server-id"),
                new DefaultLegacyConfigValueRetrieverBuilder<>("org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID")
            )
            .usingGetter(Config::getString)
            .withDefault("default-id")
            .readOnce()
            .build();

    /**
     * The property which controls the domain name used in URLs
     * advertised for COLIBRI WebSockets.
     */
    static ConfigProperty<String> domain = new ConfigPropertyBuilder<String>()
            .fromConfigs(
                new DefaultConfigValueRetrieverBuilder<>("videobridge.websockets.domain"),
                new DefaultLegacyConfigValueRetrieverBuilder<>("org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN")
            )
            .usingGetter(Config::getString)
            .withDefault(null)
            .readOnce()
            .build();

    /**
     * The property which controls whether URLs advertised for
     * COLIBRI WebSockets should use the "ws" (if false) or "wss" (if true)
     * schema.
     */
    static ConfigProperty<Boolean> tls = new ConfigPropertyBuilder<Boolean>()
            .fromConfigs(
                    new DefaultConfigValueRetrieverBuilder<>("videobridge.websockets.tls"),
                    new DefaultLegacyConfigValueRetrieverBuilder<>("org.jitsi.videobridge.rest.COLIBRI_WS_TLS")
            )
            .usingGetter(Config::getBoolean)
            .withDefault(null)
            .readOnce()
            .build();
}
