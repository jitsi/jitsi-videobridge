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

import org.jitsi.config.legacyProperty
import org.jitsi.config.newProperty
import org.jitsi.utils.config.dsl.multiProperty
import java.time.Duration

class EndpointConnectionStatusConfig {
    companion object {

        /**
         * How long it can take an endpoint to send first data before it will
         * be marked as inactive.
         */
        private val firstTransferTimeout = multiProperty<Duration> {
            legacyProperty {
                name("org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT")
                readOnce()
                retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
            }
            newProperty {
                name("videobridge.ep-connection-status.first-transfer-timeout")
                readOnce()
            }
        }

        @JvmStatic
        fun getFirstTransferTimeout(): Duration = firstTransferTimeout.value

        /**
         * How long an endpoint can be inactive before it wil be considered
         * disconnected.
         */
        private val maxInactivityLimit = multiProperty<Duration> {
            legacyProperty {
                name("org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT")
                readOnce()
                retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
            }
            newProperty {
                name("videobridge.ep-connection-status.max-inactivity-limit")
                readOnce()
            }
        }

        @JvmStatic
        fun getMaxInactivityLimit(): Duration = maxInactivityLimit.value
    }
}