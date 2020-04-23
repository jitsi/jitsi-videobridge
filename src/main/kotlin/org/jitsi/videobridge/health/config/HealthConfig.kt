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

import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty
import java.time.Duration

class HealthConfig {
    class Config {
        companion object {
            class HealthIntervalProperty : FallbackProperty<Duration>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.health.INTERVAL")
                    readOnce()
                    retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
                },
                newConfigAttributes {
                    name("videobridge.health.interval")
                    readOnce()
                }
            )

            private val intervalProperty = HealthIntervalProperty()

            @JvmStatic
            fun getInterval(): Duration = intervalProperty.value

            class TimeoutProperty : FallbackProperty<Duration>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.health.TIMEOUT")
                    readOnce()
                    retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
                },
                newConfigAttributes {
                    name("videobridge.health.timeout")
                    readOnce()
                }
            )

            private val timeoutProperty = TimeoutProperty()

            @JvmStatic
            fun getTimeout(): Duration = timeoutProperty.value

            class MaxCheckDurationProperty : SimpleProperty<Duration>(
                newConfigAttributes {
                    name("videobridge.health.max-check-duration")
                    readOnce()
                }
            )

            private val maxCheckDurationProperty = MaxCheckDurationProperty()

            @JvmStatic
            fun getMaxCheckDuration(): Duration = maxCheckDurationProperty.value

            class StickyFailuresProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.health.STICKY_FAILURES",
                newName = "videobridge.health.sticky-failures"
            )

            private val stickyFailures = StickyFailuresProperty()

            @JvmStatic
            fun stickyFailures() = stickyFailures.value
        }
    }
}
