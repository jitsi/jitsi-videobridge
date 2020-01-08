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
package org.jitsi.videobridge.transport

import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty

class HarvestersConfig {
    class Config {
        companion object {
            /**
             * The name of the property which enables ICE/TCP.
             */
            class TcpEnabledProperty : FallbackProperty<Boolean>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.DISABLE_TCP_HARVESTER")
                    readOnce()
                    // The old property is named 'disable', while the new one
                    // is 'enable', so invert the old value
                    transformedBy { !it }
                },
                newConfigAttributes {
                    name("videobridge.ice.tcp.enabled")
                    readOnce()
                }
            )
            private val tcpEnabledProp = TcpEnabledProperty()

            @JvmStatic
            fun tcpEnabled() = tcpEnabledProp.value

            /**
             * The property that configures whether ICE/TCP should use "ssltcp" or not.
             */
            class IceSslTcpProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP",
                newName = "videobridge.ice.tcp.ssltcp"
            )
            private val iceSslTcpProperty = IceSslTcpProperty()

            @JvmStatic
            fun iceSslTcp() = iceSslTcpProperty.value

            /**
             * The property that configures the ICE port.
             */
            class IcePortProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT",
                newName = "videobridge.ice.port"
            )
            private val icePortProperty = IcePortProperty()

            @JvmStatic
            fun icePort() = icePortProperty.value
        }
    }
}
