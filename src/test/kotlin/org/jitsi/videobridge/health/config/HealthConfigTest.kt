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

import org.jitsi.config.BooleanMockConfigValueGenerator
import org.jitsi.config.DurationToLongMockConfigValueGenerator
import org.jitsi.config.LongMockConfigValueGenerator
import org.jitsi.config.runBasicTests
import org.jitsi.videobridge.JitsiConfigTest

class HealthConfigTest : JitsiConfigTest() {

    init {
        "health interval" {
            runBasicTests(
                legacyConfigName = "org.jitsi.videobridge.health.INTERVAL",
                legacyValueGenerator = LongMockConfigValueGenerator,
                newConfigName = "videobridge.health.interval",
                newConfigValueGenerator = DurationToLongMockConfigValueGenerator,
                propCreator = { HealthConfig.Config.Companion.HealthIntervalProperty() }
            )
        }
        "health timeout" {
            runBasicTests(
                legacyConfigName = "org.jitsi.videobridge.health.TIMEOUT",
                legacyValueGenerator = LongMockConfigValueGenerator,
                newConfigName = "videobridge.health.timeout",
                newConfigValueGenerator = DurationToLongMockConfigValueGenerator,
                propCreator = { HealthConfig.Config.Companion.TimeoutProperty() }
            )
        }
        "sticky failures" {
            runBasicTests(
                legacyConfigName = "org.jitsi.videobridge.health.STICKY_FAILURES",
                legacyValueGenerator = BooleanMockConfigValueGenerator(),
                newConfigName = "videobridge.health.sticky-failures",
                newConfigValueGenerator = BooleanMockConfigValueGenerator(),
                propCreator = { HealthConfig.Config.Companion.StickyFailuresProperty() }
            )
        }
    }
}
