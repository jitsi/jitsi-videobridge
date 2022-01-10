/*
 * Copyright @ 2021 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest

class MultiStreamConfigTest : ConfigTest() {
    init {
        context("multi-stream-config") {
            context("when enabled") {
                withNewConfig(configWithMultiStreamEnabled) {
                    MultiStreamConfig().enabled shouldBe true
                }
            }
            context("when disabled") {
                withNewConfig(configWithMultiStreamDisabled) {
                    MultiStreamConfig().enabled shouldBe false
                }
            }
        }
    }
}

val configWithMultiStreamEnabled = """
    videobridge.multi-stream.enabled = true
""".trimIndent()

val configWithMultiStreamDisabled = """
    videobridge.multi-stream.enabled = false
""".trimIndent()
