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

package org.jitsi.nlj.rtcp

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpExtensionHandler
import org.jitsi.nlj.util.RtpPayloadTypesChangedHandler
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.utils.ms
import org.jitsi.utils.secs

class KeyframeRequesterTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val streamInformationStore = object : ReadOnlyStreamInformationStore {
        override val rtpExtensions: List<RtpExtension> = mutableListOf()
        override val rtpPayloadTypes: Map<Byte, PayloadType> = mutableMapOf()
        override var supportsFir: Boolean = true
        override var supportsPli: Boolean = true
        override val supportsRemb: Boolean = true
        override val supportsTcc: Boolean = true
        override fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
            // no-op
        }
        override fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler) {
            // no-op
        }

        override val primaryMediaSsrcs: Set<Long> = setOf(123L, 456L, 789L)
        override val primaryVideoSsrcs: Set<Long> = setOf(123L, 456L)
        override val receiveSsrcs: Set<Long> = setOf(123L, 456L, 789L, 321L, 654L)

        override fun getLocalPrimarySsrc(secondarySsrc: Long): Long? = null

        override fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? = null

        override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("dummy")
    }
    private val logger = StdoutLogger()
    private val clock: FakeClock = FakeClock()

    private val keyframeRequester = KeyframeRequester(streamInformationStore, logger, clock)
    private val sentKeyframeRequests = mutableListOf<PacketInfo>()

    init {
        keyframeRequester.onOutput { sentKeyframeRequests.add(it) }

        context("requesting a keyframe") {
            context("without a specific SSRC") {
                keyframeRequester.requestKeyframe()
                should("result in a sent PLI request with the first video SSRC") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                    packet as RtcpFbPliPacket
                    packet.mediaSourceSsrc shouldBe 123L
                }
            }
            context("when PLI is supported") {
                keyframeRequester.requestKeyframe(123L)
                should("result in a sent PLI request") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                    packet as RtcpFbPliPacket
                    packet.mediaSourceSsrc shouldBe 123L
                }
                context("and then requesting again") {
                    sentKeyframeRequests.clear()
                    context("within the wait interval") {
                        clock.elapse(10.ms)
                        context("on the same SSRC") {
                            keyframeRequester.requestKeyframe(123L)
                            should("not send anything") {
                                sentKeyframeRequests.shouldBeEmpty()
                            }
                        }
                        context("on a different SSRC") {
                            keyframeRequester.requestKeyframe(456L)
                            should("result in a sent PLI request") {
                                sentKeyframeRequests shouldHaveSize 1
                                val packet = sentKeyframeRequests.last().packet
                                packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                                packet as RtcpFbPliPacket
                                packet.mediaSourceSsrc shouldBe 456L
                            }
                        }
                    }
                    context("after the wait interval has expired") {
                        clock.elapse(1.secs)
                        keyframeRequester.requestKeyframe(123L)
                        should("result in a sent PLI request") {
                            sentKeyframeRequests shouldHaveSize 1
                            val packet = sentKeyframeRequests.last().packet
                            packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                            packet as RtcpFbPliPacket
                            packet.mediaSourceSsrc shouldBe 123L
                        }
                    }
                }
            }
            context("when PLI isn't supported") {
                streamInformationStore.supportsPli = false
                keyframeRequester.requestKeyframe(123L)
                should("result in a sent FIR request") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbFirPacket>()
                    packet as RtcpFbFirPacket
                    packet.mediaSenderSsrc shouldBe 123L
                }
            }
            context("when neither PLI nor FIR is supported") {
                streamInformationStore.supportsFir = false
                streamInformationStore.supportsPli = false
                keyframeRequester.requestKeyframe(123L)
                should("not send anything") {
                    sentKeyframeRequests.shouldBeEmpty()
                }
            }
        }
    }
}
