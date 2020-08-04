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

package org.jitsi.nlj.transform.node.outgoing

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.should
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.test_utils.matchers.ByteArrayBuffer.haveSameContentAs
import org.jitsi.nlj.test_utils.matchers.haveSameContentAs
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.srtp.SrtpErrorStatus

internal class SrtpEncryptTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val srtpTransformers = SrtpUtil.initializeTransformer(
            SrtpSample.srtpProfileInformation,
            SrtpSample.keyingMaterial.array(),
            SrtpSample.tlsRole,
            StdoutLogger()
        )

        context("encrypting an RTCP packet") {
            context("created from a buffer") {
                val packetInfo = PacketInfo(SrtpSample.outgoingUnencryptedRtcpPacket.clone())
                srtpTransformers.srtcpEncryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK

                val encryptedPacket = packetInfo.packet
                should("encrypt the data correctly") {
                    encryptedPacket shouldNotBe null
                    encryptedPacket should haveSameContentAs(SrtpSample.expectedEncryptedRtcpPacket)
                }
            }
            context("created from values") {
                val originalPacket = RtcpFbNackPacketBuilder(
                    mediaSourceSsrc = 123,
                    missingSeqNums = (10..20 step 2).toSortedSet()
                ).build()
                val packetInfo = PacketInfo(originalPacket.clone())
                srtpTransformers.srtcpEncryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK

                val encryptedPacket = packetInfo.packet
                should("result in all header fields being correct") {
                    println("original packet:\n${originalPacket.buffer.toHex()}")
                    println("packet after:\n${encryptedPacket.buffer.toHex()}")
                    RtcpHeader.getPacketType(
                        encryptedPacket.buffer, encryptedPacket.offset
                    ) shouldBe TransportLayerRtcpFbPacket.PT
                }
            }
        }

        context("encrypting an RTP packet") {
            val packetInfo = PacketInfo(SrtpSample.outgoingUnencryptedRtpPacket.clone())
            srtpTransformers.srtpEncryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK

            val encryptedPacket = packetInfo.packet
            should("encrypt the data correctly") {
                encryptedPacket should haveSameContentAs(SrtpSample.expectedEncryptedRtpPacket)
            }
        }
    }
}
