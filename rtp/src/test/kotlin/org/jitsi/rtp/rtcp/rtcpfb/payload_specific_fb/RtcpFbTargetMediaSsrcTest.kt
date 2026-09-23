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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

internal class RtcpFbTargetMediaSsrcTest : ShouldSpec() {
    init {
        context("A PLI") {
            val pli = RtcpFbPliPacketBuilder(mediaSourceSsrc = 123L).build()
            should("have the header's media source SSRC as its target") {
                pli.targetMediaSsrc shouldBe 123L
            }
            should("set the header's media source SSRC when its target is set") {
                pli.targetMediaSsrc = 456L
                pli.mediaSourceSsrc shouldBe 456L
                pli.targetMediaSsrc shouldBe 456L
            }
        }
        context("A FIR") {
            val fir = RtcpFbFirPacketBuilder(mediaSenderSsrc = 123L, firCommandSeqNum = 1).build()
            should("have the FCI's media sender SSRC as its target, not the header field") {
                fir.targetMediaSsrc shouldBe 123L
                fir.mediaSourceSsrc shouldBe 0L
            }
            should("set the FCI's media sender SSRC when its target is set") {
                fir.targetMediaSsrc = 456L
                fir.mediaSenderSsrc shouldBe 456L
                fir.mediaSourceSsrc shouldBe 0L
                fir.targetMediaSsrc shouldBe 456L
            }
        }
    }
}
