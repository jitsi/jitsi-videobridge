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

import org.jitsi.config.LegacyFallbackConfigProperty

class OctoConfig {
    class Config {
        companion object {
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
            fun bindAddress(): String? {
                return try {
                    bindAddressProp.value
                } catch (t: Throwable) {
                    null
                }
            }

            class BindPortProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                "org.jitsi.videobridge.octo.BIND_PORT",
                "videobridge.octo.bind-port",
                readOnce = true
            )

            private val bindPortProp = BindPortProperty()

            @JvmStatic
            fun bindPort(): Int {
                return try {
                    bindPortProp.value
                } catch (t: Throwable) {
                    -1
                }
            }

            class PublicAddressProperty : LegacyFallbackConfigProperty<String>(
                String::class,
                "org.jitsi.videobridge.octo.PUBLIC_ADDRESS",
                "videobridge.octo.public-address",
                readOnce = true
            )

            private val publicAddressProp = PublicAddressProperty()

            @JvmStatic
            fun publicAddress(): String? {
                return try {
                    publicAddressProp.value
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }
}
