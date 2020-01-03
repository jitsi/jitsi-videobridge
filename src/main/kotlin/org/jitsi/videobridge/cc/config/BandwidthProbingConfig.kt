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

package org.jitsi.videobridge.cc.config

import java.time.Duration
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty

class BandwidthProbingConfig {
    class Config {
        companion object {
            /**
             * How often we check to send probing data
             */
            class PaddingPeriodProperty : FallbackProperty<Long>(
                legacyConfigAttributes {
                    readOnce()
                    name("org.jitsi.videobridge.PADDING_PERIOD_MS")
                },
                newConfigAttributes {
                    readOnce()
                    name("videobridge.cc.padding-period")
                    retrievedAs<Duration>() convertedBy { it.toMillis() }
                }
            )
            private val paddingPeriodProp = PaddingPeriodProperty()

            @JvmStatic
            fun paddingPeriodMs() = paddingPeriodProp.value

            class DisableRtxProbingProperty : SimpleProperty<Boolean>(
                legacyConfigAttributes {
                    readOnce()
                    name("org.jitsi.videobridge.DISABLE_RTX_PROBING")
                    deprecated("RTX probing is always used when RTX is supported.")
                }
            )
        }
    }
}
