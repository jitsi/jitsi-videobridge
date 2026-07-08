/*
 * Copyright @ 2024 - Present, 8x8 Inc
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

package org.jitsi.nlj.transform.node.outgoing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.SdesHeaderExtension

class MidStamperTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val midExtId = 10
    private val ssrcWithMid = 1234L
    private val streamInformationStore = StreamInformationStoreImpl()

    private fun midStamper(mid: String) = MidStamper(streamInformationStore) { ssrc ->
        if (ssrc == ssrcWithMid) mid else null
    }

    private fun packetInfo(ssrc: Long) = PacketInfo(
        RtpPacket(ByteArray(1500), 0, 1500).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = 100
            sequenceNumber = 123
            timestamp = 456L
            this.ssrc = ssrc
        }
    )

    init {
        context("when the mid extension is negotiated") {
            streamInformationStore.addRtpExtensionMapping(RtpExtension(midExtId.toByte(), RtpExtensionType.MID))

            // Mids of assorted lengths: single character, even and odd lengths (odd exercises the extension
            // padding path), and the 16-byte maximum of a one-byte header extension.
            listOf("0", "v3", "audio-mid-7", "0123456789abcdef").forEach { mid ->
                should("stamp the mid \"$mid\" for an SSRC that has a mapping") {
                    val midStamper = midStamper(mid)
                    midStamper.onOutput {
                        val ext = it.packetAs<RtpPacket>().getHeaderExtension(midExtId)
                        ext.shouldNotBeNull()
                        SdesHeaderExtension.getTextValue(ext) shouldBe mid
                    }
                    midStamper.processPacket(packetInfo(ssrcWithMid))
                }
            }

            should("not stamp an SSRC that has no mapping") {
                val midStamper = midStamper("v3")
                midStamper.onOutput {
                    it.packetAs<RtpPacket>().getHeaderExtension(midExtId) shouldBe null
                }
                midStamper.processPacket(packetInfo(9999L))
            }
        }

        context("when the mid extension is not negotiated") {
            should("not stamp anything") {
                val midStamper = midStamper("v3")
                midStamper.onOutput {
                    it.packetAs<RtpPacket>().getHeaderExtension(midExtId) shouldBe null
                }
                midStamper.processPacket(packetInfo(ssrcWithMid))
            }
        }
    }
}
