/*
 * Copyright @ 2020 - present 8x8, Inc.
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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.videobridge.util.VideoType.CAMERA
import org.jitsi.videobridge.util.VideoType.NONE

class SpeechActivityTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val a = mockEndpoint("a")
    private val b = mockEndpoint("b")
    private val c = mockEndpoint("c")
    private val d = mockEndpoint("d")
    private val conferenceSpeechActivity = ConferenceSpeechActivity(object : ConferenceSpeechActivity.Listener {
        override fun lastNEndpointsChanged() {}
        override fun recentSpeakersChanged() {}
    })

    init {
        context("Should maintain correct order when everyone has video") {
            conferenceSpeechActivity.endpointsChanged(listOf(a, b, c))
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, b, c)

            conferenceSpeechActivity.activeSpeakerChanged(b.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe b

            conferenceSpeechActivity.activeSpeakerChanged(c.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe c

            conferenceSpeechActivity.activeSpeakerChanged(a.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe a

            conferenceSpeechActivity.orderedEndpoints shouldContainInOrder listOf(a, c, b)

            conferenceSpeechActivity.endpointsChanged(listOf(b, c))
            conferenceSpeechActivity.orderedEndpoints shouldContainInOrder listOf(c, b)

            conferenceSpeechActivity.activeSpeakerChanged(c.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe c

            conferenceSpeechActivity.activeSpeakerChanged(b.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe b

            conferenceSpeechActivity.endpointsChanged(listOf(d, b, c))
            conferenceSpeechActivity.dominantEndpoint shouldBe b

            conferenceSpeechActivity.activeSpeakerChanged(d.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe d
            conferenceSpeechActivity.orderedEndpoints shouldContainInOrder listOf(d, b, c)
        }

        context("Should maintain correct order when endpoints disable/enable video") {
            conferenceSpeechActivity.endpointsChanged(listOf(a, b, c, d))
            conferenceSpeechActivity.activeSpeakerChanged(d.id)
            conferenceSpeechActivity.activeSpeakerChanged(c.id)
            conferenceSpeechActivity.activeSpeakerChanged(b.id)
            conferenceSpeechActivity.activeSpeakerChanged(a.id)

            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, b, c, d)

            every { a.videoType } returns NONE
            conferenceSpeechActivity.updateLastNEndpoints()
            conferenceSpeechActivity.dominantEndpoint shouldBe a
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(b, c, d, a)

            every { a.videoType } returns CAMERA
            conferenceSpeechActivity.updateLastNEndpoints()
            conferenceSpeechActivity.dominantEndpoint shouldBe a
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, b, c, d)

            every { b.videoType } returns NONE
            conferenceSpeechActivity.updateLastNEndpoints()
            conferenceSpeechActivity.dominantEndpoint shouldBe a
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, c, d, b)

            conferenceSpeechActivity.activeSpeakerChanged(b.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe b
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, c, d, b)

            every { b.videoType } returns CAMERA
            conferenceSpeechActivity.updateLastNEndpoints()
            conferenceSpeechActivity.dominantEndpoint shouldBe b
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(b, a, c, d)

            every { a.videoType } returns NONE
            every { b.videoType } returns NONE
            every { c.videoType } returns NONE
            every { d.videoType } returns NONE
            conferenceSpeechActivity.updateLastNEndpoints()
            conferenceSpeechActivity.activeSpeakerChanged(a.id)
            conferenceSpeechActivity.dominantEndpoint shouldBe a
            conferenceSpeechActivity.orderedEndpoints shouldContainExactly listOf(a, b, c, d)
        }
    }

    companion object {
        fun mockEndpoint(endpointId: String) = mockk<AbstractEndpoint>().apply {
            every { id } returns endpointId
            every { videoType } returns CAMERA
        }
    }
}
