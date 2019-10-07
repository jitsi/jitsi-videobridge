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

import org.jitsi.utils.collections.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.util.config.*;

public class WebSocketConfig
{
    /**
     * Whether or not the websocket service is enabled
     */
    public static class EnabledProperty extends ReadOnceProperty<Boolean>
    {
        protected static final String legacyPropKey = "org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE";
        protected static final String propKey = "videobridge.websockets.enabled";

        protected EnabledProperty()
        {
            super(JList.of(
                // The legacy config value was 'DISABLE' and the new one is
                // 'ENABLED', so if we pull the value from the legacy config,
                // we need to invert it
                () -> !JvbConfig.getLegacyConfig().getBoolean(legacyPropKey),
                () -> JvbConfig.getConfig().getBoolean(propKey)
            ));
        }
    }

    public static EnabledProperty enabled = new EnabledProperty();

    /**
     * The property which controls the server ID used in URLs
     * advertised for COLIBRI WebSockets.
     */
    public static class ServerIdProperty extends ReadOnceProperty<String>
    {
        protected static final String legacyPropKey = "org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID";
        protected static final String propKey = "videobridge.websockets.server-id";

        protected ServerIdProperty()
        {
            super(JList.of(
                () -> JvbConfig.getLegacyConfig().getString(legacyPropKey),
                () -> JvbConfig.getConfig().getString(propKey)
            ));
        }
    }

    static ServerIdProperty serverId = new ServerIdProperty();

    /**
     * The property which controls the domain name used in URLs
     * advertised for COLIBRI WebSockets.
     */
    public static class DomainProperty extends ReadOnceProperty<String>
    {
        protected static final String legacyPropKey = "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN";
        protected static final String propKey = "videobridge.websockets.domain";

        protected DomainProperty()
        {
            super(JList.of(
                () -> JvbConfig.getLegacyConfig().getString(legacyPropKey),
                () -> JvbConfig.getConfig().getString(propKey)
            ));
        }
    }

    static DomainProperty domain = new DomainProperty();

    /**
     * The property which controls whether URLs advertised for
     * COLIBRI WebSockets should use the "ws" (if false) or "wss" (if true)
     * schema.
     */
    public static class TlsProperty extends ReadOnceProperty<Boolean>
    {
        protected static final String legacyPropKey = "org.jitsi.videobridge.rest.COLIBRI_WS_TLS";
        protected static final String propKey = "videobridge.websockets.tls";

        protected TlsProperty()
        {
            super(JList.of(
                () -> JvbConfig.getLegacyConfig().getBoolean(legacyPropKey),
                () -> JvbConfig.getConfig().getBoolean(propKey)
            ));
        }
    }

    static TlsProperty tls = new TlsProperty();
}
