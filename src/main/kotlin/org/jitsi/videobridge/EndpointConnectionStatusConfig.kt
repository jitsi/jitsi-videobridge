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

import org.jitsi.config.JitsiConfig
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.helpers.attributes
import java.time.Duration

class EndpointConnectionStatusConfig {
    class Config {
        companion object {
            /**
             * How long it can take an endpoint to send first data before it will
             * be marked as inactive.
             */
            class FirstTransferTimeoutProperty : FallbackProperty<Duration>(
                attributes {
                    name("org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT")
                    readOnce()
                    fromConfig(JitsiConfig.legacyConfig)
                    retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
                },
                attributes {
                    name("videobridge.ep-connection-status.first-transfer-timeout")
                    readOnce()
                    fromConfig(JitsiConfig.newConfig)
                }
            )
            private val firstTransferTimeout = FirstTransferTimeoutProperty()

            @JvmStatic
            fun getFirstTransferTimeout(): Duration = firstTransferTimeout.value

            /**
             * How long an endpoint can be inactive before it wil be considered
             * disconnected.
             */
            class MaxInactivityLimitProperty : FallbackProperty<Duration>(
                attributes {
                    name("org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT")
                    readOnce()
                    fromConfig(JitsiConfig.legacyConfig)
                    retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
                },
                attributes {
                    name("videobridge.ep-connection-status.max-inactivity-limit")
                    readOnce()
                    fromConfig(JitsiConfig.newConfig)
                }
            )
            private val maxInactivityLimit = MaxInactivityLimitProperty()

            @JvmStatic
            fun getMaxInactivityLimit(): Duration = maxInactivityLimit.value
        }
    }
}