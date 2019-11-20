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

package org.jitsi.videobridge.health.config

import org.jitsi.utils.config.dsl.multiProperty
import org.jitsi.videobridge.config.JvbConfigk
import org.jitsi.videobridge.config.legacyProperty
import org.jitsi.videobridge.config.newProperty
import org.jitsi.videobridge.config.simple
import java.time.Duration

class HealthConfig {
    companion object {
        private val interval = multiProperty<Long> {
            property {
                name("org.jitsi.videobridge.health.INTERVAL")
                readOnce()
                fromConfig(JvbConfigk.legacyConfig)
            }
            property {
                name("videobridge.health.interval")
                readOnce()
                fromConfig(JvbConfigk.newConfig)
                retrievedAs<Duration>() convertedBy { it.toMillis() }
            }
        }

        @JvmStatic
        fun getInterval(): Long = interval.value

        private val timeout = multiProperty<Long> {
            legacyProperty {
                name("org.jitsi.videobridge.health.TIMEOUT")
                readOnce()
            }
            newProperty {
                name("videobridge.health.timeout")
                readOnce()
                retrievedAs<Duration>() convertedBy { it.toMillis() }
            }
        }

        @JvmStatic
        fun getTimeout(): Long = timeout.value

        private val stickyFailures = simple<Boolean>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.health.STICKY_FAILURES",
            newName = "videobridge.health.sticky-failures"
        )

        @JvmStatic
        fun stickyFailures() = stickyFailures.value
    }
}