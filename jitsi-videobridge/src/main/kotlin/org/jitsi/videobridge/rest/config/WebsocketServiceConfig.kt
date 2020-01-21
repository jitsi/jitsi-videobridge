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

package org.jitsi.videobridge.rest.config

import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.videobridge.config.ConditionalProperty
import org.jitsi.videobridge.config.ConditionalPropertyConditionNotMetException
import org.jitsi.videobridge.config.ResettableSingleton

class WebsocketServiceConfig {
    class Config {
        companion object {
            /**
             * The name of the property which enables the
             * [org.jitsi.videobridge.rest.ColibriWebSocketService]
             */
            class EnabledProperty : FallbackProperty<Boolean>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE")
                    readOnce()
                    // The old property is named 'disable', while the new one
                    // is 'enable', so invert the old value
                    transformedBy { !it }
                },
                newConfigAttributes {
                    name("videobridge.websockets.enabled")
                    readOnce()
                }
            )
            private val enabledProp = ResettableSingleton { EnabledProperty() }

            @JvmStatic
            fun enabled() = enabledProp.get().value

            /**
             * The property which controls the domain name used in URLs
             * advertised for COLIBRI WebSockets.
             */
            class DomainProperty : ConditionalProperty<String>(
                ::enabled,
                object : LegacyFallbackConfigProperty<String>(
                    String::class,
                    readOnce = true,
                    legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN",
                    newName = "videobridge.websockets.domain"
                ) {},
                "Websocket domain property is only parsed when websockets are enabled"
            )

            private val domainProp = DomainProperty()

            /**
             * Note, should only be accessed after verifying [enabled] is true
             */
            @JvmStatic
            fun domain() = domainProp.value

            /**
             * The property which controls whether URLs advertised for
             * COLIBRI WebSockets should use the "ws" (if false) or "wss" (if true)
             * schema.
             */
            class TlsProperty : ConditionalProperty<Boolean>(
                ::enabled,
                object : LegacyFallbackConfigProperty<Boolean>(
                    Boolean::class,
                    readOnce = true,
                    legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_TLS",
                    newName = "videobridge.websockets.tls"
                ) {},
                "Websocket TLS property is only parsed when websockets are enabled"
            )
            private val tlsProp = TlsProperty()

            /**
             * Note, should only be accessed after verifying [enabled] is true.
             * Also: we support the field not being defined.  See its usage.
             */
            @JvmStatic
            fun useTls(): Boolean? {
                return try {
                    tlsProp.value
                } catch (t: Throwable) {
                    when (t) {
                        is ConditionalPropertyConditionNotMetException -> throw t
                        else -> null
                    }
                }
            }

            /**
             * The name of the property which controls the server ID used in URLs
             * advertised for COLIBRI WebSockets.
             */
            class ServerIdProperty : ConditionalProperty<String>(
                ::enabled,
                object : LegacyFallbackConfigProperty<String>(
                    String::class,
                    readOnce = true,
                    legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID",
                    newName = "videobridge.websockets.server-id"
                ) {},
                "Websocket server ID property is only parsed when websockets are enabled"
            )
            private val serverIdProp = ServerIdProperty()

            /**
             * Note, should only be accessed after verifying [enabled] is true
             */
            @JvmStatic
            fun serverId() = serverIdProp.value
        }
    }
}
