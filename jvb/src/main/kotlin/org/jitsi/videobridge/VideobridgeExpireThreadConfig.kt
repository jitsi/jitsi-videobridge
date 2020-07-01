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

package org.jitsi.videobridge

import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty
import java.time.Duration

class VideobridgeExpireThreadConfig {
    class Config {
        companion object {
            class InactivityTimeoutProperty : SimpleProperty<Duration>(
                newConfigAttributes {
                    name("videobridge.entity-expiration.timeout")
                    readOnce()
                }
            )

            private val inactivityTimeoutProp = InactivityTimeoutProperty()

            @JvmStatic
            fun inactivityTimeout() = inactivityTimeoutProp.value

            class ExpireThreadIntervalProperty : FallbackProperty<Duration>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC")
                    readOnce()
                    retrievedAs<Long>() convertedBy { Duration.ofSeconds(it) }
                },
                newConfigAttributes {
                    name("videobridge.entity-expiration.check-interval")
                    readOnce()
                }
            )

            private val expireThreadIntervalProp = ExpireThreadIntervalProperty()

            @JvmStatic
            fun interval(): Duration = expireThreadIntervalProp.value
        }
    }
}
