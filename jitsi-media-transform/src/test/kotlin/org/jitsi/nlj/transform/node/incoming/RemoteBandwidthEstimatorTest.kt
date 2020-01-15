/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.seconds
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.test_utils.RtpPacketGenerator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.nlj.util.mbps
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension
import java.time.Duration

class RemoteBandwidthEstimatorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = FakeClock()
    private val astExtensionId = 3
    // REMB is enabled by having at least one payload type which has "goog-remb" signaled as a rtcp-fb, and TCC is
    // disabled.
    private val vp8PayloadType = Vp8PayloadType(100, emptyMap(), setOf("goog-remb"))
    private val vp9PayloadTypeWithTcc = Vp9PayloadType(101, emptyMap(), setOf("transport-cc"))
    private val ssrc = 1234L
    private val streamInformationStore = StreamInformationStoreImpl().apply {
        addRtpExtensionMapping(RtpExtension(astExtensionId.toByte(), RtpExtensionType.ABS_SEND_TIME))
        addRtpPayloadType(vp8PayloadType)
    }

    private val remoteBandwidthEstimator = RemoteBandwidthEstimator(streamInformationStore, StdoutLogger(), clock = clock)

    init {
        "when REMB is not signaled" {
            streamInformationStore.clearRtpPayloadTypes()
            streamInformationStore.addRtpPayloadType(vp9PayloadTypeWithTcc)
            sendPackets()
            "no feedback should be produced" {
                remoteBandwidthEstimator.createRemb() shouldBe null
            }
        }
        "when both REMB and TCC are signaled" {
            streamInformationStore.addRtpPayloadType(vp9PayloadTypeWithTcc)
            sendPackets()
            "no feedback should be produced" {
                remoteBandwidthEstimator.createRemb() shouldBe null
            }
        }
        "when REMB is signaled" {
            sendPackets(0.5.mbps)
            val rembPacket = remoteBandwidthEstimator.createRemb()
            "a feedback packet should be produced" {
                rembPacket shouldNotBe null
                "with valid bitrate" {
                    rembPacket!!.bitrate shouldBeGreaterThan 0
                }
                "with the correct SSRCs" {
                    rembPacket!!.ssrcs shouldBe listOf(ssrc)
                }
            }

            // We generate packets with no jitter, so we expect the estimate to he higher than the receive bitrate.
            val targetBitrate = 1.5.mbps
            sendPackets(targetBitrate)
            "when receiving a higher bitrate, the estimate should grow" {
                val rembPacket = remoteBandwidthEstimator.createRemb()
                rembPacket!!.bitrate shouldBeGreaterThan targetBitrate.bps.toLong()
            }
        }
    }

    private fun sendPackets(targetBitrate: Bandwidth = 1.mbps, duration: Duration = 15.seconds) {
        val rtpPacketGenerator = RtpPacketGenerator(targetBitrate, duration = duration, clock = clock)
        rtpPacketGenerator.generatePackets(ssrc = ssrc) {
            val ext = it.packetAs<RtpPacket>().addHeaderExtension(astExtensionId, AbsSendTimeHeaderExtension.DATA_SIZE_BYTES)
            AbsSendTimeHeaderExtension.setTime(ext, it.receivedTime * 1_000_000)
            remoteBandwidthEstimator.processPacket(it)
        }
    }
}
