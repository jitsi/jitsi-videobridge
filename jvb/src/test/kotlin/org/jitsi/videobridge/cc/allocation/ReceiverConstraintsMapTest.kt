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

package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class ReceiverConstraintsMapTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val constraints = ReceiverConstraintsMap()

    context("receiver constraints map") {
        should("track the max height correctly") {
            constraints.maxHeight shouldBe 0
            constraints.put("a", vc(1))
            constraints.maxHeight shouldBe 1
            constraints.put("b", vc(2))
            constraints.maxHeight shouldBe 2
            constraints.put("c", vc(3))
            constraints.maxHeight shouldBe 3

            constraints.remove("c")
            constraints.maxHeight shouldBe 2

            constraints.put("a", vc(4))
            constraints.maxHeight shouldBe 4

            constraints.remove("a")
            constraints.maxHeight shouldBe 2

            constraints.remove("b")
            constraints.maxHeight shouldBe 0
        }
    }
})

private fun vc(maxHeight: Int) = VideoConstraints(maxHeight, 30.0)
