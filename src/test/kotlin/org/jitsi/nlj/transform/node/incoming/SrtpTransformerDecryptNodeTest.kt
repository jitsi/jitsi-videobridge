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
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil

internal class SrtpTransformerDecryptNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val srtpTransformer = SrtpUtil.initializeTransformer(
        SrtpSample.srtpProfileInformation,
        SrtpSample.keyingMaterial.array(),
        SrtpSample.tlsRole,
        false
    )

    init {
        "decrypting a packet" {
            val decryptedPacket = srtpTransformer.reverseTransform(SrtpSample.incomingEncryptedRtpPacket)

            should("decrypt the data correctly") {
                SrtpSample.expectedDecryptedRtpData.compareTo(decryptedPacket.getBuffer()) shouldBe 0
            }
        }
    }
}