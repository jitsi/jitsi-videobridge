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
package org.jitsi.nlj

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec

class MediaSourcesTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    init {
        val mediaSources = MediaSources()

        val sourceA = createSource(1000)
        val sourceA2 = createSource(1000)
        val sourceB = createSource(2000, 2001)
        val sourceB2 = createSource(2000, 2001)
        val sourceC = createSource(3000, 3001)

        var changed = mediaSources.setMediaSources(arrayOf(sourceA, sourceB))
        context("Setting initially  must signal a change.") {
            changed shouldBe true

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
            newSources[1] shouldBe sourceB
        }

        context("Setting the same sources must not signal a change.") {
            changed = mediaSources.setMediaSources(arrayOf(sourceA, sourceB))
            changed shouldBe false

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
            newSources[1] shouldBe sourceB
        }

        context("Setting matching sources must not signal a change, or change the saved sources") {
            changed = mediaSources.setMediaSources(arrayOf(sourceA2, sourceB2))
            changed shouldBe false

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
            newSources[1] shouldBe sourceB
        }

        context("Adding a new source must signal a change, but not change the previous sources") {
            changed = mediaSources.setMediaSources(arrayOf(sourceA2, sourceB2, sourceC))
            changed shouldBe true

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
            newSources[1] shouldBe sourceB
            newSources[2] shouldBe sourceC
        }

        context("Removing a source must signal a change, but not change the previous source") {
            changed = mediaSources.setMediaSources(arrayOf(sourceA))
            changed shouldBe true

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
        }

        context("Adding and removing must signal a change, but not change the previous source") {
            changed = mediaSources.setMediaSources(arrayOf(sourceA2, sourceC))
            changed shouldBe true

            val newSources = mediaSources.getMediaSources()
            newSources[0] shouldBe sourceA
            newSources[1] shouldBe sourceC
        }
    }

    companion object {
        fun createSource(vararg ssrcs: Long): MediaSourceDesc {
            val encodings = Array(ssrcs.size) { i -> RtpEncodingDesc(ssrcs[i]) }
            val source = MediaSourceDesc(encodings)

            return source
        }
    }
}
