/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.videobridge.cc.allocation.createSourceDesc

class AdaptiveSourceProjectionTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val clock = FakeClock()
    private val source = createSourceDesc(1, 2, 3, "source", "owner")
    private val requestedSsrcs = mutableListOf<Long>()
    private val projection = AdaptiveSourceProjection(
        DiagnosticContext(),
        source,
        { ssrc -> requestedSsrcs.add(ssrc) },
        LoggerImpl(AdaptiveSourceProjectionTest::class.java.name)
    )

    private fun markLive(vararg eids: Int) =
        eids.forEach { source.rtpEncodings[it].liveness.onPacketReceived(clock.millis()) }

    init {
        context("choosing the SSRC to request a keyframe from") {
            projection.setTargetIndex(RtpLayerDesc.getIndex(2, 0, 2))
            context("when no encoding is being sent") {
                should("fall back to the primary SSRC") {
                    projection.getKeyframeRequestSsrc() shouldBe 1L
                }
            }
            context("when the target encoding is being sent") {
                markLive(0, 1, 2)
                should("request from the target encoding") {
                    projection.getKeyframeRequestSsrc() shouldBe 3L
                }
            }
            context("when the target encoding is not being sent") {
                markLive(0, 1)
                should("request from the highest live encoding below it") {
                    projection.getKeyframeRequestSsrc() shouldBe 2L
                }
                context("and then goes live") {
                    clock.elapse(2.secs)
                    markLive(2)
                    should("request from the target encoding") {
                        projection.getKeyframeRequestSsrc() shouldBe 3L
                    }
                }
            }
            context("when only encodings above the target are being sent") {
                projection.setTargetIndex(RtpLayerDesc.getIndex(0, 0, 2))
                markLive(1, 2)
                should("fall back to the primary SSRC") {
                    projection.getKeyframeRequestSsrc() shouldBe 1L
                }
            }
            context("when the source has been replaced by a new object") {
                markLive(0, 1, 2)
                val replacement = createSourceDesc(1, 2, 3, "source", "owner")
                projection.setSource(replacement)
                should("judge liveness by the new object") {
                    projection.getKeyframeRequestSsrc() shouldBe 1L
                    replacement.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    projection.getKeyframeRequestSsrc() shouldBe 3L
                }
            }
            context("when the whole source has gone silent") {
                markLive(0, 1, 2)
                clock.elapse(2.secs)
                should("request from the target encoding, the last state known standing") {
                    projection.getKeyframeRequestSsrc() shouldBe 3L
                }
            }
            context("when the target encoding stopped while a lower one kept being sent") {
                markLive(0, 1, 2)
                clock.elapse(2.secs)
                markLive(0, 1)
                should("request from the highest live encoding below it") {
                    projection.getKeyframeRequestSsrc() shouldBe 2L
                }
            }
        }
    }
}
