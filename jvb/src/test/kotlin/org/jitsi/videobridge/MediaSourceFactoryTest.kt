/*
 * Copyright @ 2021 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.setNewConfig
import org.jitsi.videobridge.xmpp.MediaSourceFactory
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension

fun createSource(ssrc: Long): SourcePacketExtension {
    val spe = SourcePacketExtension()

    spe.ssrc = ssrc

    return spe
}

// TODO port MediaSourceFactoryTest to kotlin and unify with this class
class MediaSourceFactoryTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    override fun afterSpec(spec: Spec) = super.afterSpec(spec).also {
        setNewConfig("", true)
    }

    init {
        context("MediaSourceFactory") {
            context("when multi-stream support is enabled") {
                setNewConfig(configWithMultiStreamEnabled, true)

                context("should throw an exception if there's no source name in the packet extension") {
                    val videoSource: SourcePacketExtension = createSource(1)

                    val exception = shouldThrow<IllegalArgumentException> {
                        MediaSourceFactory.createMediaSources(listOf(videoSource), emptyList())
                    }
                    exception.message shouldBe "The 'name' is missing in the source description"
                }
            }

            // TODO fix the test before merge
            context("when multi-stream support is disabled") {
                // TODO for some reason setting multi stream to disable is not working
                setNewConfig(configWithMultiStreamDisabled, true)

                context("should NOT throw an exception if there's no source name in the packet extension") {
                    val videoSource: SourcePacketExtension = createSource(1)

                    MediaSourceFactory.createMediaSources(listOf(videoSource), emptyList())
                }
            }
        }
    }
}
