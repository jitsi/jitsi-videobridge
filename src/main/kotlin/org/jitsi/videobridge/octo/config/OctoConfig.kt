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

package org.jitsi.videobridge.octo.config

import com.typesafe.config.ConfigObject
import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty
import org.jitsi.utils.config.exception.ConfigValueParsingException

class OctoConfig {
    class Config {
        companion object {

            class QueueSizeProperty : SimpleProperty<Int>(
                    newConfigAttributes {
                        name("videobridge.octo.recv-queue-size")
                        readOnce()
                    }
            )

            private val queueSizeProperty = QueueSizeProperty()

            @JvmStatic
            fun queueSize() = queueSizeProperty.value

            class EnabledProperty : FallbackProperty<Boolean>(
                // The legacy config file doesn't have an 'enabled' property,
                // instead it was based on the values of the parameters.  Here,
                // we simulate a legacy 'enabled' value based on the results
                // of validating the other properties in the legacy config
                // file.
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.octo")
                    readOnce()
                    retrievedAs<ConfigObject>() convertedBy {
                        val cfg = it.toConfig()
                        if (cfg.hasPath("BIND_ADDRESS") && cfg.hasPath("BIND_PORT")) {
                            val bindAddress = cfg.getString("BIND_ADDRESS")
                            val bindPort = cfg.getInt("BIND_PORT")
                            bindAddress != null && (bindPort.isUnpriviligedPort())
                        } else {
                            false
                        }
                    }
                },
                newConfigAttributes {
                    name("videobridge.octo.enabled")
                    readOnce()
                }
            )

            private val enabledProp = EnabledProperty()

            @JvmStatic
            fun enabled() = enabledProp.value

            class RegionProperty : LegacyFallbackConfigProperty<String>(
                String::class,
                "org.jitsi.videobridge.REGION",
                "videobridge.octo.region",
                readOnce = true
            )

            private val regionProp = RegionProperty()

            @JvmStatic
            fun region(): String? {
                return try {
                    regionProp.value
                } catch (t: Throwable) {
                    null
                }
            }

            class BindAddressProperty : LegacyFallbackConfigProperty<String>(
                String::class,
                "org.jitsi.videobridge.octo.BIND_ADDRESS",
                "videobridge.octo.bind-address",
                readOnce = true
            )

            private val bindAddressProp = BindAddressProperty()

            @JvmStatic
            fun bindAddress(): String = bindAddressProp.value

            class BindPortProperty : FallbackProperty<Int>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.octo.BIND_PORT")
                    readOnce()
                    retrievedAs<Int>() convertedBy {
                        if (!it.isUnpriviligedPort()) {
                            throw ConfigValueParsingException("Octo bind port " +
                                    "must be in the unprivileged port space")
                        }
                        it
                    }
                },
                newConfigAttributes {
                    name("videobridge.octo.bind-port")
                    readOnce()
                }
            )

            private val bindPortProp = BindPortProperty()

            @JvmStatic
            fun bindPort(): Int = bindPortProp.value

            class PublicAddressProperty : LegacyFallbackConfigProperty<String>(
                String::class,
                "org.jitsi.videobridge.octo.PUBLIC_ADDRESS",
                "videobridge.octo.public-address",
                readOnce = true
            )

            private val publicAddressProp = PublicAddressProperty()

            /**
             * If publicAddress doesn't have a value, default to the
             * value of bindAddress.
             * Note: we can't use a substitution in reference.conf
             * because that won't take into account reading a value
             * from the legacy config file
             */
            @JvmStatic
            fun publicAddress(): String {
                return try {
                    publicAddressProp.value
                } catch (t: Throwable) {
                    bindAddressProp.value
                }
            }
        }
    }
}

private fun Int.isUnpriviligedPort(): Boolean = this in 1024..65535
