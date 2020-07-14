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

import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class SpeechActivityTest : ShouldSpec() {
    init {
        "Should maintain correct endpoint order when endpoints and speakers change" {
            val conferenceSpeechActivity = ConferenceSpeechActivity(object : ConferenceSpeechActivity.Listener {
                override fun speechActivityEndpointsChanged() {}
                override fun dominantSpeakerChanged() {}
            })

            conferenceSpeechActivity.endpointsChanged(listOf("a", "b", "c"))
            conferenceSpeechActivity.endpoints shouldContainExactly listOf("a", "b", "c")

            conferenceSpeechActivity.activeSpeakerChanged("b")
            conferenceSpeechActivity.dominantEndpoint shouldBe "b"

            conferenceSpeechActivity.activeSpeakerChanged("c")
            conferenceSpeechActivity.dominantEndpoint shouldBe "c"

            conferenceSpeechActivity.activeSpeakerChanged("a")
            conferenceSpeechActivity.dominantEndpoint shouldBe "a"

            conferenceSpeechActivity.endpoints shouldContainInOrder listOf("a", "c", "b")

            conferenceSpeechActivity.endpointsChanged(listOf("b", "c"))
            conferenceSpeechActivity.endpoints shouldContainInOrder listOf("c", "b")

            conferenceSpeechActivity.activeSpeakerChanged("c")
            conferenceSpeechActivity.dominantEndpoint shouldBe "c"

            conferenceSpeechActivity.activeSpeakerChanged("b")
            conferenceSpeechActivity.dominantEndpoint shouldBe "b"

            conferenceSpeechActivity.endpointsChanged(listOf("d", "b", "c"))
            conferenceSpeechActivity.dominantEndpoint shouldBe "b"

            conferenceSpeechActivity.activeSpeakerChanged("d")
            conferenceSpeechActivity.dominantEndpoint shouldBe "d"
            conferenceSpeechActivity.endpoints shouldContainInOrder listOf("d", "b", "c")
        }
    }
}
