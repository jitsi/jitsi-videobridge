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
package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc

class BandwidthAllocationTest : ShouldSpec() {
    /** A source as signaled, which is a new object each time it is. */
    private fun source() = MediaSourceDesc(
        arrayOf(RtpEncodingDesc(123L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0)))),
        "owner",
        "name"
    )

    private fun allocation(source: MediaSourceDesc) =
        BandwidthAllocation(setOf(SingleAllocation("owner", source, source.rtpLayers[0])))

    init {
        context("Comparing allocations") {
            val source = source()
            should("find an allocation of the same layer of the same source object the same") {
                allocation(source).isTheSameAs(allocation(source)) shouldBe true
            }
            should("find an allocation of the same layer of a re-signaled source different") {
                allocation(source).isTheSameAs(allocation(source())) shouldBe false
            }
        }
    }
}
