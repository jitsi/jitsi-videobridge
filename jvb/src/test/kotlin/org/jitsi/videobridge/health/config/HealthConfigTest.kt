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

import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.MapConfigSource
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.metaconfig.StdOutLogger
import java.time.Duration

class HealthConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val legacyConfig = MapConfigSource("legacy")
    private val newConfig = MapConfigSource("new")

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        NewJitsiConfig.legacyConfig = legacyConfig
        NewJitsiConfig.newConfig = newConfig
        MetaconfigSettings.logger = StdOutLogger
    }

    override fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        NewJitsiConfig.legacyConfig = NewJitsiConfig.SipCommunicatorPropsConfigSource
        NewJitsiConfig.newConfig = NewJitsiConfig.TypesafeConfig
    }

    init {
        "health interval" {
            "when legacy config and new config define a value" {
                legacyConfig["org.jitsi.videobridge.health.INTERVAL"] = 1000L
                newConfig["videobridge.health.interval"] = Duration.ofSeconds(5)
                should("use the value from legacy config") {
                    HealthConfig().interval shouldBe Duration.ofSeconds(1)
                }
            }
            "when only new config defines a value" {
                newConfig["videobridge.health.interval"] = Duration.ofSeconds(5)
                should("use the value from the new config") {
                    HealthConfig().interval shouldBe Duration.ofSeconds(5)
                }
            }
        }
    }
}
