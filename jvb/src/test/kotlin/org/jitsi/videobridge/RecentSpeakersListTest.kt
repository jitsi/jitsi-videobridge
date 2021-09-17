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
