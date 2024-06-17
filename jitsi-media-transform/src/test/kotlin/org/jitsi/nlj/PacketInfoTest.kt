/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.nlj

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class PacketInfoTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("EventTimeline test") {
            val timeline = EventTimeline().apply {
                addEvent("A")
                addEvent("B")
            }
            val clone = timeline.clone()
            timeline.size shouldBe 2
            clone.size shouldBe 2

            timeline.addEvent("original")
            timeline.size shouldBe 3
            clone.size shouldBe 2

            clone.addEvent("clone")
            clone.addEvent("clone2")
            timeline.size shouldBe 3
            clone.size shouldBe 4
        }
    }
}
