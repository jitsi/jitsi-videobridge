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
import org.jitsi.videobridge.config.ConditionalProperty

class OctoConfig {
    class Config {
        companion object {

            class EnabledProperty : FallbackProperty<Boolean>(
                // The legacy config file doesn't have an 'enabled' property,
                // instead it was based on the values of the paremeters.  Here,
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
                            // TODO(brian): UnprivilegedPort helper class
                            bindAddress != null && (bindPort in 1024..65535)
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

            class RegionProperty : ConditionalProperty<String>(
                ::enabled,
                Region(),
                "Octo region is only parsed when Octo is enabled"
            )

            private class Region : LegacyFallbackConfigProperty<String>(
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

            class BindAddressProperty : ConditionalProperty<String>(
                ::enabled,
                BindAddress(),
                "Octo bind address is only parsed when Octo is enabled"
            )

            private class BindAddress : LegacyFallbackConfigProperty<String>(
                String::class,
                "org.jitsi.videobridge.octo.BIND_ADDRESS",
                "videobridge.octo.bind-address",
                readOnce = true
            )

            private val bindAddressProp = BindAddressProperty()

            @JvmStatic
            fun bindAddress(): String = bindAddressProp.value

            class BindPortProperty : ConditionalProperty<Int>(
                ::enabled,
                BindPort(),
                "Octo bind port is only parsed when Octo is enabled"
            )

            private class BindPort : LegacyFallbackConfigProperty<Int>(
                Int::class,
                "org.jitsi.videobridge.octo.BIND_PORT",
                "videobridge.octo.bind-port",
                readOnce = true
            )

            private val bindPortProp = BindPortProperty()

            @JvmStatic
            fun bindPort(): Int = bindPortProp.value

            class PublicAddressProperty : ConditionalProperty<String>(
                ::enabled,
                PublicAddress(),
                "Octo public address is only parsed when Octo is enabled"
            )

            class PublicAddress : LegacyFallbackConfigProperty<String>(
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
