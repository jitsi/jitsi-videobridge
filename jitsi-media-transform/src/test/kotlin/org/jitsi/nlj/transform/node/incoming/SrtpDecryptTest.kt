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

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.test_utils.matchers.ByteArrayBuffer.haveSameContentAs
import org.jitsi.srtp.SrtpErrorStatus

internal class SrtpDecryptTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val srtpTransformers = SrtpUtil.initializeTransformer(
            SrtpSample.srtpProfileInformation,
            SrtpSample.keyingMaterial.array(),
            SrtpSample.tlsRole,
            StdoutLogger()
        )

        "decrypting an RTCP packet" {
            val packetInfo = PacketInfo(SrtpSample.incomingEncryptedRtcpPacket.clone())
            srtpTransformers.srtcpDecryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK
            val decryptedPacket = packetInfo.packet

            should("decrypt the data correctly") {
                decryptedPacket shouldNotBe null
                decryptedPacket should haveSameContentAs(SrtpSample.expectedDecryptedRtcpPacket)
            }
        }

        "decrypting an RTP packet" {
            val packetInfo = PacketInfo(SrtpSample.incomingEncryptedRtpPacket.clone())
            srtpTransformers.srtpDecryptTransformer.transform(packetInfo) shouldBe SrtpErrorStatus.OK

            val decryptedPacket = packetInfo.packet
            should("decrypt the data correctly") {
                decryptedPacket shouldNotBe null
                decryptedPacket should haveSameContentAs(SrtpSample.expectedDecryptedRtpPacket)
            }
        }
    }
}
