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
package org.jitsi.videobridge.ice

import org.ice4j.ice.KeepAliveStrategy
import org.ice4j.ice.NominationStrategy
import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty
import java.util.Objects

class IceConfig {
    class Config {
        companion object {
            /**
             * The property which enables ICE/TCP.
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
             * The property which configures the ICE/TCP port.
             */
            class TcpPortProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.TCP_HARVESTER_PORT",
                newName = "videobridge.ice.tcp.port"
            )
            private val tcpPortProperty = TcpPortProperty()

            @JvmStatic
            fun tcpPort() = tcpPortProperty.value

            /**
             * The property which configures an additional port to advertise.
             */
            class TcpMappedPortProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT",
                newName = "videobridge.ice.tcp.mapped-port"
            )
            private val tcpMappedPortProperty = TcpMappedPortProperty()

            /**
             * Returns the additional port to advertise, or [null] if none is configured.
             */
            @JvmStatic
            fun tcpMappedPort(): Int? = try {
                tcpMappedPortProperty.value
            } catch (e: Throwable) {
                null
            }

            /**
             * The property that configures whether ICE/TCP should use "ssltcp" or not.
             */
            class SslTcpProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP",
                newName = "videobridge.ice.tcp.ssltcp"
            )
            private val sslTcpProperty = SslTcpProperty()

            @JvmStatic
            fun iceSslTcp() = sslTcpProperty.value

            /**
             * The property that configures the ICE UDP port.
             */
            class PortProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT",
                newName = "videobridge.ice.udp.port"
            )
            private val portProperty = PortProperty()

            @JvmStatic
            fun port() = portProperty.value

            /**
             * The property that configures the prefix to STUN username fragments we generate.
             */
            class UfragPrefixProperty : LegacyFallbackConfigProperty<String>(
                String::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.ICE_UFRAG_PREFIX",
                newName = "videobridge.ice.ufrag-prefix"
            )
            private val ufragPrefixProperty = UfragPrefixProperty()

            @JvmStatic
            fun ufragPrefix(): String? = try {
                ufragPrefixProperty.value
            } catch (e: Throwable) {
                null
            }

            /**
             * The property that configures the prefix to STUN username fragments we generate.
             */
            class KeepAliveStrategyProperty : FallbackProperty<KeepAliveStrategy>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.KEEP_ALIVE_STRATEGY")
                    readOnce()
                    retrievedAs<String>() convertedBy { Objects.requireNonNull(KeepAliveStrategy.fromString(it)) }
                },
                newConfigAttributes {
                    name("videobridge.ice.keep-alive-strategy")
                    readOnce()
                    retrievedAs<String>() convertedBy { Objects.requireNonNull(KeepAliveStrategy.fromString(it)) }
                }
            )
            private val keepAliveStrategyProperty = KeepAliveStrategyProperty()

            @JvmStatic
            fun keepAliveStrategy() = keepAliveStrategyProperty.value

            /**
            * The property that configures whether the ice4j "component socket" mode is used.
            */
            class ComponentSocketProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.USE_COMPONENT_SOCKET",
                newName = "videobridge.ice.use-component-socket"
            )
            private val componentSocketProperty = ComponentSocketProperty()

            @JvmStatic
            fun useComponentSocket() = componentSocketProperty.value

            class ResolveRemoteCandidatesProperty : SimpleProperty<Boolean>(
                newConfigAttributes {
                    name("videobridge.ice.resolve-remote-candidates")
                    readOnce()
            })
            private val resolveRemoteCandidatesProperty = ResolveRemoteCandidatesProperty()

            @JvmStatic
            fun resolveRemoteCandidates() = resolveRemoteCandidatesProperty.value

            /**
             * The property that configures the ice4j nomination strategy policy.
             */
            class NominationStrategyProperty : SimpleProperty<NominationStrategy>(
                newConfigAttributes {
                    name("videobridge.ice.nomination-strategy")
                    readOnce()
                    retrievedAs<String>() convertedBy { Objects.requireNonNull(NominationStrategy.fromString(it)) }
                })
            private val nominationStrategyProperty = NominationStrategyProperty()

            @JvmStatic
            fun nominationStrategy() = nominationStrategyProperty.value
        }
    }
}
