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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.util.ExtmapAllowMixedChangedHandler
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpExtensionHandler
import org.jitsi.nlj.util.RtpPayloadTypesChangedHandler
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock

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

        override val extmapAllowMixed: Boolean = false
        override fun onExtmapAllowMixedChanged(handler: ExtmapAllowMixedChangedHandler) {
            // no-op
        }

        override val primaryMediaSsrcs: Set<Long> = setOf(123L, 456L, 789L)
        override val receiveSsrcs: Set<Long> = setOf(123L, 456L, 789L, 321L, 654L)

        override fun getLocalPrimarySsrc(secondarySsrc: Long): Long? = null

        override fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? = null

        override fun debugState(mode: DebugStateMode): ObjectNode = JsonNodeFactory.instance.objectNode()
    }
    private val logger = StdoutLogger()
    private val clock: FakeClock = FakeClock()

    private val keyframeRequester = KeyframeRequester(streamInformationStore, logger, clock)
    private val sentKeyframeRequests = mutableListOf<PacketInfo>()

    init {
        keyframeRequester.onOutput { sentKeyframeRequests.add(it) }

        context("requesting a keyframe") {
            context("without a specific SSRC") {
                keyframeRequester.requestKeyframe("ep1")
                should("result in a sent PLI request with the first video SSRC") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                    packet.mediaSourceSsrc shouldBe 123L
                }
            }
            context("when PLI is supported") {
                keyframeRequester.requestKeyframe("ep1", 123L)
                should("result in a sent PLI request") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                    packet.mediaSourceSsrc shouldBe 123L
                }
                context("and then requesting again") {
                    sentKeyframeRequests.clear()
                    context("within the wait interval") {
                        clock.elapse(10.ms)
                        context("on the same SSRC") {
                            keyframeRequester.requestKeyframe("ep1", 123L)
                            should("not send anything") {
                                sentKeyframeRequests.shouldBeEmpty()
                            }
                        }
                        context("for a different SSRC") {
                            keyframeRequester.requestKeyframe("ep1", 456L)
                            should("result in a sent PLI request") {
                                sentKeyframeRequests shouldHaveSize 1
                                val packet = sentKeyframeRequests.last().packet
                                packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                                packet.mediaSourceSsrc shouldBe 456L
                            }
                        }
                    }
                    context("after the wait and source-wide intervals have expired") {
                        clock.elapse(3.secs)
                        keyframeRequester.requestKeyframe("ep1", 123L)
                        should("result in a sent PLI request") {
                            sentKeyframeRequests shouldHaveSize 1
                            val packet = sentKeyframeRequests.last().packet
                            packet.shouldBeInstanceOf<RtcpFbPliPacket>()
                            packet.mediaSourceSsrc shouldBe 123L
                        }
                    }
                }
            }
            context("when PLI isn't supported") {
                streamInformationStore.supportsPli = false
                keyframeRequester.requestKeyframe("ep1", 123L)
                should("result in a sent FIR request") {
                    sentKeyframeRequests shouldHaveSize 1
                    val packet = sentKeyframeRequests.last().packet
                    packet.shouldBeInstanceOf<RtcpFbFirPacket>()
                    packet.mediaSenderSsrc shouldBe 123L
                }
            }
            context("when neither PLI nor FIR is supported") {
                streamInformationStore.supportsFir = false
                streamInformationStore.supportsPli = false
                keyframeRequester.requestKeyframe("ep1", 123L)
                should("not send anything") {
                    sentKeyframeRequests.shouldBeEmpty()
                }
            }
        }

        context("requesting a keyframe with no requester id") {
            context("repeatedly") {
                repeat(4) { keyframeRequester.requestKeyframe(null, 123L) }
                should("still be limited by the source-wide limit") {
                    sentKeyframeRequests shouldHaveSize 1
                }
            }
            context("from more than one requester for the same source") {
                keyframeRequester.requestKeyframe("ep1", 123L)
                keyframeRequester.requestKeyframe(null, 123L)
                should("share the source-wide limit with attributed requests") {
                    sentKeyframeRequests shouldHaveSize 1
                }
            }
            context("after the source-wide interval has expired") {
                keyframeRequester.requestKeyframe(null, 123L)
                clock.elapse(3.secs)
                keyframeRequester.requestKeyframe(null, 123L)
                should("be allowed again") {
                    sentKeyframeRequests shouldHaveSize 2
                }
            }
        }

        context("requests dropped by the source-wide limit") {
            // ep1 opens the source-wide limit's 2s min-interval. ep2 arrives just after and, like a receiver waiting
            // for a keyframe, keeps re-requesting as often as its own 200ms min-interval allows, using up its 3
            // requests per 10s long before the source-wide limit reopens.
            keyframeRequester.requestKeyframe("ep1", 123L)
            repeat(3) {
                keyframeRequester.requestKeyframe("ep2", 123L)
                clock.elapse(200.ms)
            }
            should("not have been sent") {
                sentKeyframeRequests shouldHaveSize 1
            }
            context("and the source-wide limit reopens") {
                clock.elapse(2.secs)
                keyframeRequester.requestKeyframe("ep2", 123L)
                should("not count against the requester's per-receiver limit") {
                    sentKeyframeRequests shouldHaveSize 2
                }
            }
        }

        context("requests dropped by the per-receiver limit") {
            keyframeRequester.requestKeyframe("ep1", 123L)
            // Within ep1's 200ms per-receiver min-interval, so dropped there, before the source-wide limit.
            clock.elapse(100.ms)
            keyframeRequester.requestKeyframe("ep1", 123L)
            should("not have been sent") {
                sentKeyframeRequests shouldHaveSize 1
            }
            context("when another receiver requests once the source-wide interval has expired") {
                clock.elapse(3.secs)
                keyframeRequester.requestKeyframe("ep2", 123L)
                should("not have counted against the source-wide limit") {
                    sentKeyframeRequests shouldHaveSize 2
                }
            }
        }
    }
}
