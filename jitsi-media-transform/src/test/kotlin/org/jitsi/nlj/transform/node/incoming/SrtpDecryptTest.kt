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

package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.test_utils.matchers.ByteArrayBuffer.haveSameContentAs
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.srtp.SrtpErrorStatus

internal class SrtpDecryptTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val srtpTransformers = SrtpUtil.initializeTransformer(
            SrtpSample.srtpProfileInformation,
            SrtpSample.keyingMaterial.array(),
            SrtpSample.tlsRole,
            // TODO: add tests for cryptex case
            cryptex = false,
            StdoutLogger()
        )

        context("decrypting an RTCP packet") {
            val packetInfo = PacketInfo(SrtpSample.incomingEncryptedRtcpPacket.clone())
            srtpTransformers.srtcpDecryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK
            val decryptedPacket = packetInfo.packet

            should("decrypt the data correctly") {
                decryptedPacket shouldNotBe null
                decryptedPacket should haveSameContentAs(SrtpSample.expectedDecryptedRtcpPacket)
            }
        }

        context("decrypting an RTP packet") {
            val packetInfo = PacketInfo(SrtpSample.incomingEncryptedRtpPacket.clone())
            srtpTransformers.srtpDecryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK

            val decryptedPacket = packetInfo.packet
            should("decrypt the data correctly") {
                decryptedPacket shouldNotBe null
                decryptedPacket should haveSameContentAs(SrtpSample.expectedDecryptedRtpPacket)
            }
        }

        // Note: we don't test that the context map is bounded by its LRU cap (AbstractSrtpTransformer.MAX_SSRCS),
        // because contexts are only cached once they authenticate a packet, so driving the map to eviction would
        // require generating authenticating packets for that many distinct SSRCs, which the single-key sample can't do.
        context("receiving RTP packets that fail to authenticate") {
            val decryptTransformer = srtpTransformers.srtpDecryptTransformer

            should("not cache a context for an SSRC that never authenticates") {
                repeat(5) {
                    val packetInfo = PacketInfo(corruptAuthTag(SrtpSample.incomingEncryptedRtpPacket.clone()))
                    decryptTransformer.transform(packetInfo) shouldNotBe SrtpErrorStatus.OK
                }
                decryptTransformer.numCachedContexts shouldBe 0
            }

            should("cache a context once a packet authenticates") {
                // A failed packet leaves no state behind.
                val failed = PacketInfo(corruptAuthTag(SrtpSample.incomingEncryptedRtpPacket.clone()))
                decryptTransformer.transform(failed) shouldNotBe SrtpErrorStatus.OK
                decryptTransformer.numCachedContexts shouldBe 0

                // The first successful decryption for the SSRC caches the context.
                val succeeded = PacketInfo(SrtpSample.incomingEncryptedRtpPacket.clone())
                decryptTransformer.transform(succeeded) shouldBe SrtpErrorStatus.OK
                decryptTransformer.numCachedContexts shouldBe 1
            }
        }

        context("receiving SRTCP packets that fail to authenticate") {
            val decryptTransformer = srtpTransformers.srtcpDecryptTransformer

            should("not cache a context for an SSRC that never authenticates") {
                repeat(5) {
                    val packet = SrtpSample.incomingEncryptedRtcpPacket.clone()
                    packet.buffer[packet.offset + packet.length - 1]++
                    decryptTransformer.transform(PacketInfo(packet)) shouldNotBe SrtpErrorStatus.OK
                }
                decryptTransformer.numCachedContexts shouldBe 0
            }

            should("cache a context once a packet authenticates") {
                decryptTransformer.transform(PacketInfo(SrtpSample.incomingEncryptedRtcpPacket.clone())) shouldBe
                    SrtpErrorStatus.OK
                decryptTransformer.numCachedContexts shouldBe 1
            }
        }
    }

    /**
     * Corrupts the authentication tag (the last byte) of an encrypted RTP packet so that it will fail to authenticate.
     */
    private fun corruptAuthTag(packet: RtpPacket): RtpPacket = packet.apply {
        buffer[offset + length - 1]++
    }
}
