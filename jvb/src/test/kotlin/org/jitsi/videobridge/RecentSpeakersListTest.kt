/*
 * Copyright @ 2021 - Present, 8x8 Inc
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class RecentSpeakersListTest : ShouldSpec() {
    init {
        context("Adding") {
            RecentSpeakersList<String>(3).apply {
                promote("a") shouldBe true
                recentSpeakers shouldBe listOf("a")

                promote("a") shouldBe false
                recentSpeakers shouldBe listOf("a")

                promote("b") shouldBe true
                recentSpeakers shouldBe listOf("b", "a")

                promote("a") shouldBe true
                recentSpeakers shouldBe listOf("a", "b")

                promote("c") shouldBe true
                recentSpeakers shouldBe listOf("c", "a", "b")

                promote("b") shouldBe true
                recentSpeakers shouldBe listOf("b", "c", "a")

                promote("d") shouldBe true
                recentSpeakers shouldBe listOf("d", "b", "c")

                promote("d") shouldBe false
                recentSpeakers shouldBe listOf("d", "b", "c")
            }
        }
        context("Removing") {
            RecentSpeakersList<String>(3).apply {
                promote("a") shouldBe true
                promote("b") shouldBe true
                promote("c") shouldBe true
                promote("d") shouldBe true
                promote("e") shouldBe true
                recentSpeakers shouldBe listOf("e", "d", "c")

                removeAllExcept(listOf("a", "b", "c", "d", "e", "and another one")) shouldBe false
                recentSpeakers shouldBe listOf("e", "d", "c")

                removeAllExcept(listOf("a", "b", "c", "d")) shouldBe true
                recentSpeakers shouldBe listOf("d", "c", "b")

                promote("e") shouldBe true
                recentSpeakers shouldBe listOf("e", "d", "c")

                removeAllExcept(listOf("a", "b")) shouldBe true
                recentSpeakers shouldBe listOf("b", "a")
            }
        }
    }
}
