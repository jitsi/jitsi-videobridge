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

package org.jitsi.videobridge.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.extensions.system.OverrideMode
import io.kotest.extensions.system.withSystemProperty
import io.kotest.matchers.shouldBe

class JavaVersionKtTest : ShouldSpec({
    context("getJavaVersion") {
        println("java version: ${System.getProperty("java.version")}")
        should("parse version strings correctly") {
            withSystemProperty("java.version", "1.8.0_192", mode = OverrideMode.SetOrOverride) {
                getJavaVersion() shouldBe 8
            }
            withSystemProperty("java.version", "11.0.7", mode = OverrideMode.SetOrOverride) {
                getJavaVersion() shouldBe 11
            }
            withSystemProperty("java.version", "nine", mode = OverrideMode.SetOrOverride) {
                shouldThrow<NumberFormatException> {
                    getJavaVersion()
                }
            }
        }
    }
})
