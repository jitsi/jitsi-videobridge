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

package org.jitsi.videobridge.version

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class JvbVersionServiceTest : ShouldSpec({
    context("parsing a version") {
        context("from a valid version string") {
            should("parse correctly") {
                with(JvbVersionService().parseVersionString("2.1-296-g817c1a45")) {
                    this.applicationName shouldBe "JVB"
                    this.versionMajor shouldBe 2
                    versionMinor shouldBe 1
                    nightlyBuildID shouldBe "296-g817c1a45"
                }
            }
        }
        context("from null") {
            should("use the defaults") {
                with(JvbVersionService().parseVersionString(null)) {
                    this.applicationName shouldBe "JVB"
                    this.versionMajor shouldBe 2
                    versionMinor shouldBe 1
                    nightlyBuildID shouldBe null
                }
            }
        }
    }
})
