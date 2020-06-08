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

package org.jitsi.videobridge.signaling.api

import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.SimpleProperty

class SignalingApiConfig {
    companion object {
        class EnabledProperty : SimpleProperty<Boolean>(
            newConfigAttributes {
                readOnce()
                name("videobridge.apis.jvb-api.enabled")
            }
        )

        private val enabledProperty = EnabledProperty()

        fun enabled(): Boolean = enabledProperty.value

        class BindPortProperty : SimpleProperty<Int>(
            newConfigAttributes {
                readOnce()
                name("videobridge.apis.jvb-api.bind-port")
            }
        )

        private val bindPortProperty = BindPortProperty()

        fun bindPort(): Int = bindPortProperty.value

        class BindAddressProperty : SimpleProperty<String>(
            newConfigAttributes {
                readOnce()
                name("videobridge.apis.jvb-api.bind-address")
            }
        )

        private val bindAddressProperty = BindAddressProperty()

        fun bindAddress(): String = bindAddressProperty.value

        class PublicAddressProperty : SimpleProperty<String>(
            newConfigAttributes {
                readOnce()
                name("videobridge.apis.jvb-api.public-address")
            }
        )

        private val publicAddressProperty = PublicAddressProperty()

        fun publicAddress(): String = publicAddressProperty.value
    }
}
