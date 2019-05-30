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

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.test_utils.matchers.ByteArrayBuffer.haveSameContentAs
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.nlj.test_utils.matchers.haveSameContentAs
import org.jitsi.service.libjitsi.LibJitsi

internal class SrtpEncryptTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        // We need to start libjitsi so that the openssl lib gets loaded.
        LibJitsi.start()

        val srtpTransformers = SrtpUtil.initializeTransformer(
            SrtpSample.srtpProfileInformation,
            SrtpSample.keyingMaterial.array(),
            SrtpSample.tlsRole)

        "encrypting an RTCP packet" {
            "created from a buffer" {
                val packetInfo = PacketInfo(SrtpSample.outgoingUnencryptedRtcpPacket.clone())
                srtpTransformers.srtcpEncryptTransformer.transform(packetInfo) shouldBe true

                val encryptedPacket = packetInfo.packet
                should("encrypt the data correctly") {
                    encryptedPacket shouldNotBe null
                    encryptedPacket should haveSameContentAs(SrtpSample.expectedEncryptedRtcpPacket)
                }
            }
            "created from values" {
                val originalPacket = RtcpFbNackPacketBuilder(
                    mediaSourceSsrc = 123,
                    missingSeqNums = (10..20 step 2).toSortedSet()
                ).build()
                val packetInfo = PacketInfo(originalPacket.clone())
                srtpTransformers.srtcpEncryptTransformer.transform(packetInfo) shouldBe true

                val encryptedPacket = packetInfo.packet
                should("result in all header fields being correct") {
                    println("original packet:\n${originalPacket.buffer.toHex()}")
                    println("packet after:\n${encryptedPacket.buffer.toHex()}")
                    RtcpHeader.getPacketType(encryptedPacket.buffer, encryptedPacket.offset) shouldBe TransportLayerRtcpFbPacket.PT
                }
            }
        }

        "encrypting an RTP packet" {
            val packetInfo = PacketInfo(SrtpSample.outgoingUnencryptedRtpPacket.clone())
            srtpTransformers.srtpEncryptTransformer.transform(packetInfo) shouldBe true

            val encryptedPacket = packetInfo.packet
            should("encrypt the data correctly") {
                encryptedPacket.buffer should haveSameContentAs(SrtpSample.expectedEncryptedRtpData)
            }
        }
    }
}