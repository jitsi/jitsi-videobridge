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
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.instanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtp.RtpPacket

class RetransmissionSenderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val originalPayloadType = 100
    private val rtxPayloadType = 96
    private val originalSsrc = 1234L
    private val rtxSsrc = 5678L
    private val streamInformationStore = StreamInformationStoreImpl()
    // NOTE(brian): unfortunately i ran into issues trying to use mock frameworks to mock
    // a packet, notably i ran into issues when trying to mock the byte[] property in
    // the parent java class, mocking frameworks seem to struggle with this
    private val dummyPacket = RtpPacket(ByteArray(1500), 0, 1500).apply {
        version = 2
        hasPadding = false
        hasExtensions = false
        isMarked = false
        payloadType = originalPayloadType
        sequenceNumber = 123
        timestamp = 456L
        ssrc = originalSsrc
    }
    private val dummyPacketInfo = PacketInfo(dummyPacket)
    private val retransmissionSender = RetransmissionSender(streamInformationStore, StdoutLogger())

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)

        // Setup: add the rtx payload type and the rtx ssrc association
        streamInformationStore.addRtpPayloadType(
            RtxPayloadType(rtxPayloadType.toByte(), mapOf("apt" to originalPayloadType.toString()))
        )
        streamInformationStore.addSsrcAssociation(
            RemoteSsrcAssociation(originalSsrc, rtxSsrc, SsrcAssociationType.RTX)
        )
    }

    init {
        context("retransmitting a packet") {
            context("which has an associated rtx stream") {
                should("rewrite the payload type and ssrc correctly") {
                    retransmissionSender.onOutput {
                        it.packet shouldBe instanceOf(RtpPacket::class)
                        it.packetAs<RtpPacket>().let { rtpPacket ->
                            rtpPacket.ssrc shouldBe rtxSsrc
                            rtpPacket.payloadType shouldBe rtxPayloadType
                        }
                    }
                    retransmissionSender.processPacket(dummyPacketInfo)
                }
            }
            context("which does not have an associated rtx stream") {
                val noRtxPacket = dummyPacket.clone().apply {
                    payloadType = 99
                    ssrc = 9876L
                }
                retransmissionSender.onOutput {
                    it.packetAs<RtpPacket>().let { rtpPacket ->
                        rtpPacket.ssrc shouldBe 9876L
                        rtpPacket.payloadType shouldBe 99
                    }
                }
                retransmissionSender.processPacket(PacketInfo(noRtxPacket))
            }
        }
    }
}
