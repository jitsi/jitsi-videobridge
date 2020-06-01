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

package org.jitsi.videobridge.api.types

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class SupportedApiVersionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val supportedApiVersions = SupportedApiVersions(ApiVersion.V1)
        context("serializing SupportedApiVersions") {
            should("return the correct string") {
                supportedApiVersions.toPresenceString() shouldBe "v1"
            }
        }
        context("deserializing a presence-string into a SupportedApiVersions") {
            should("parse the string correctly") {
                SupportedApiVersions.fromPresenceString("v1") shouldBe SupportedApiVersions(ApiVersion.V1)
            }
        }
        context("supports") {
            should("return the right result") {
                supportedApiVersions.supports(ApiVersion.V1) shouldBe true
                SupportedApiVersions().supports(ApiVersion.V1) shouldBe false
            }
        }
        context("maxSupported") {
            context("when there is intersection") {
                val s2 = SupportedApiVersions(ApiVersion.V1)
                should("return the correct version") {
                    supportedApiVersions.maxSupported(s2) shouldBe ApiVersion.V1
                }
            }
            context("where there is no intersection") {
                val s2 = SupportedApiVersions()
                should("return null") {
                    supportedApiVersions.maxSupported(s2) shouldBe null
                }
            }
        }
    }
}
