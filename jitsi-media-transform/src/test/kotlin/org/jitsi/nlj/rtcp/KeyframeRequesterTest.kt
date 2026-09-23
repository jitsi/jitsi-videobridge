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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.nlj.util.ExtmapAllowMixedChangedHandler
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpExtensionHandler
import org.jitsi.nlj.util.RtpPayloadTypesChangedHandler
import org.jitsi.nlj.util.bits
import org.jitsi.nlj.util.bps
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
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
                context("and a receiver's FIR is forwarded") {
                    sentKeyframeRequests.clear()
                    clock.elapse(3.secs)
                    keyframeRequester.handleEvent(SetLocalSsrcEvent(MediaType.VIDEO, 999L))
                    sendFir(keyframeRequester, "ep2", 456L)
                    should("keep the FIR's target and send it from the local SSRC") {
                        sentKeyframeRequests shouldHaveSize 1
                        val packet = sentKeyframeRequests.last().packet
                        packet.shouldBeInstanceOf<RtcpFbFirPacket>()
                        packet.mediaSenderSsrc shouldBe 456L
                        packet.senderSsrc shouldBe 999L
                    }
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

        context("with a keyframe mode detector") {
            // 123 and 456 are two encodings of one source, whose sender's mode the detector knows.
            val source = MediaSourceDesc(
                arrayOf(
                    RtpEncodingDesc(123L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0))),
                    RtpEncodingDesc(456L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(1, 0, -1, 720, 30.0)))
                ),
                "owner",
                "name"
            )
            source.rtpEncodings[1].addSecondarySsrc(4560L, SsrcAssociationType.RTX)
            val detector = KeyframeModeDetector(logger).also { it.setMediaSources(arrayOf(source)) }
            keyframeRequester.setKeyframeModeDetector(detector)
            source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }

            context("whose mode is not known") {
                keyframeRequester.requestKeyframe("ep1", 456L)
                clock.elapse(500.ms)
                keyframeRequester.requestKeyframe("ep2", 123L)
                should("limit requests for the source's encodings together") {
                    sentKeyframeRequests shouldHaveSize 1
                }
                context("after a request recorded against one encoding only, as in the per-encoding mode") {
                    // A limit for 123 alone which has just accepted a request, as a per-encoding sender's would have.
                    withNewConfig("jmt.keyframe.sender-mode=per-encoding") {
                        val perEncoding = KeyframeModeDetector(logger).also { it.setMediaSources(arrayOf(source)) }
                        keyframeRequester.setKeyframeModeDetector(perEncoding)
                        clock.elapse(3.secs)
                        keyframeRequester.requestKeyframe("ep3", 123L)
                        sentKeyframeRequests shouldHaveSize 2
                    }
                    keyframeRequester.setKeyframeModeDetector(detector)
                    clock.elapse(500.ms)
                    keyframeRequester.requestKeyframe("ep4", 456L)
                    should("count that request against a request for another encoding once the modes are shared") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                }
                context("after a receiver's three requests for one encoding, limited per encoding") {
                    withNewConfig("jmt.keyframe.sender-mode=per-encoding") {
                        val perEncoding = KeyframeModeDetector(logger).also { it.setMediaSources(arrayOf(source)) }
                        keyframeRequester.setKeyframeModeDetector(perEncoding)
                        repeat(3) {
                            clock.elapse(2100.ms)
                            keyframeRequester.requestKeyframe("ep3", 123L)
                        }
                        sentKeyframeRequests shouldHaveSize 4
                    }
                    keyframeRequester.setKeyframeModeDetector(detector)
                    clock.elapse(2100.ms)
                    keyframeRequester.requestKeyframe("ep3", 456L)
                    should("hold that receiver to its limit for the source, whichever encoding it names") {
                        sentKeyframeRequests shouldHaveSize 4
                    }
                }
                should("tell the detector of the request sent") {
                    detector.debugState()["source_123"]["pending"].asBoolean() shouldBe true
                }
                context("when a receiver's PLI is forwarded") {
                    clock.elapse(3.secs)
                    // The earlier request's observation is past its deadline; a packet closes it, as on the media path.
                    detector.onPacketObserved(clock.millis())
                    detector.debugState()["source_123"]["pending"].asBoolean() shouldBe false
                    source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    sendPli(keyframeRequester, "ep3", 456L)
                    should("forward it") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                    should("tell the detector of it too") {
                        detector.debugState()["source_123"]["pending"].asBoolean() shouldBe true
                    }
                }
                context("when a receiver's FIR naming an encoding's RTX SSRC is answered with a PLI") {
                    clock.elapse(3.secs)
                    source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    sendFir(keyframeRequester, "ep3", 4560L)
                    should("request a keyframe of the encoding by its primary SSRC") {
                        sentKeyframeRequests shouldHaveSize 2
                        (sentKeyframeRequests.last().packet as RtcpFbPliPacket).mediaSourceSsrc shouldBe 456L
                    }
                }
                context("when a receiver's FIR naming an encoding's RTX SSRC is forwarded") {
                    streamInformationStore.supportsPli = false
                    clock.elapse(3.secs)
                    source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    sendFir(keyframeRequester, "ep3", 4560L)
                    streamInformationStore.supportsPli = true
                    should("forward it naming the encoding's primary SSRC in its FCI") {
                        sentKeyframeRequests shouldHaveSize 2
                        (sentKeyframeRequests.last().packet as RtcpFbFirPacket).mediaSenderSsrc shouldBe 456L
                    }
                }
                context("when a receiver's PLI naming an encoding's RTX SSRC is forwarded") {
                    clock.elapse(3.secs)
                    source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    sendPli(keyframeRequester, "ep3", 4560L)
                    should("forward it naming the encoding's primary SSRC") {
                        sentKeyframeRequests shouldHaveSize 2
                        (sentKeyframeRequests.last().packet as RtcpFbPliPacket).mediaSourceSsrc shouldBe 456L
                    }
                }
                context("when requesting without an SSRC") {
                    sentKeyframeRequests.clear()
                    clock.elapse(3.secs)
                    keyframeRequester.requestKeyframe(null)
                    should("request the primary SSRC only") {
                        sentKeyframeRequests.map { (it.packet as RtcpFbPliPacket).mediaSourceSsrc } shouldBe
                            listOf(123L)
                    }
                }
            }
            context("whose mode is per-encoding") {
                withNewConfig("jmt.keyframe.sender-mode=per-encoding") {
                    val perEncoding = KeyframeModeDetector(logger).also { it.setMediaSources(arrayOf(source)) }
                    keyframeRequester.setKeyframeModeDetector(perEncoding)
                    keyframeRequester.requestKeyframe("ep1", 456L)
                    clock.elapse(500.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("limit requests for the source's encodings separately") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                    context("when requesting without an SSRC") {
                        sentKeyframeRequests.clear()
                        clock.elapse(3.secs)
                        source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                        keyframeRequester.requestKeyframe(null)
                        should("request every encoding being sent") {
                            sentKeyframeRequests.map { (it.packet as RtcpFbPliPacket).mediaSourceSsrc } shouldBe
                                listOf(123L, 456L)
                        }
                    }
                    context("when requesting for a source by its primary SSRC") {
                        sentKeyframeRequests.clear()
                        clock.elapse(3.secs)
                        source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                        keyframeRequester.requestKeyframeForSource(null, 123L)
                        should("request every encoding being sent") {
                            sentKeyframeRequests.map { (it.packet as RtcpFbPliPacket).mediaSourceSsrc } shouldBe
                                listOf(123L, 456L)
                        }
                        should("not have the detector observe the set") {
                            perEncoding.debugState()["open_observations"].asInt() shouldBe 0
                        }
                    }
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

        context("with keyframe budget limiting enabled") {
            withNewConfig("jmt.keyframe.budget.enabled=true") {
                // The budget is computed when a request is sent and applies to the next request for the source, so
                // each case sets its cost before the first request.
                context("with a 60 KB keyframe on a 1.2 Mbps source") {
                    // 480000 / (0.15 * 1200000) = 2.67s between requests, longer than the 2s source-wide floor.
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 1_200_000.bps) }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    should("expose the derived interval and the cost in the node stats") {
                        val stats = keyframeRequester.getNodeStats().toJson()
                        stats["keyframe_budget_enabled"].asBoolean() shouldBe true
                        val limit = stats["source_wide_limit_123"]
                        limit["budget_interval_ms"].asLong() shouldBe 2666L
                        limit["implied_interval_ms"].asLong() shouldBe 2666L
                        limit["keyframe_cost"]["keyframe_bits"].asLong() shouldBe 480_000L
                        limit["keyframe_cost"]["source_bitrate_bps"].asLong() shouldBe 1_200_000L
                        // 480000 bits per 2.666s is 180 kbps, 15% of 1.2 Mbps.
                        limit["keyframe_fraction_at_interval"].asDouble() shouldBe (0.15 plusOrMinus 0.001)
                    }
                    context("before the derived interval has expired") {
                        clock.elapse(2500.ms)
                        keyframeRequester.requestKeyframe("ep2", 123L)
                        should("not send a second request") {
                            sentKeyframeRequests shouldHaveSize 1
                        }
                    }
                    context("after the derived interval has expired") {
                        clock.elapse(3.secs)
                        keyframeRequester.requestKeyframe("ep2", 123L)
                        should("send a second request") {
                            sentKeyframeRequests shouldHaveSize 2
                        }
                    }
                }
                context("with a detector which learns that the sender generates keyframes per encoding") {
                    val source = MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(123L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0))),
                            RtpEncodingDesc(456L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(1, 0, -1, 720, 30.0)))
                        ),
                        "owner",
                        "name"
                    )
                    val detector = KeyframeModeDetector(logger).also { it.setMediaSources(arrayOf(source)) }
                    keyframeRequester.setKeyframeModeDetector(detector)
                    // A media packet on every encoding: the detector is told first, as on the media path.
                    fun sending() {
                        detector.onPacketObserved(clock.millis())
                        source.rtpEncodings.forEach { it.liveness.onPacketReceived(clock.millis()) }
                    }
                    // A cost which calls for 2.67s between requests, computed for the whole source while the mode
                    // is unknown.
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 1_200_000.bps) }
                    // Three requests, each answered on the requested encoding alone while the other keeps sending,
                    // which the media path closes after the response window: the sender generates keyframes per
                    // encoding.
                    repeat(3) { i ->
                        val ssrc = if (i % 2 == 0) 123L else 456L
                        sending()
                        keyframeRequester.requestKeyframe("ep$i", ssrc)
                        clock.elapse(100.ms)
                        detector.onKeyframeObserved(ssrc, clock.millis())
                        clock.elapse(400.ms)
                        sending()
                        clock.elapse((KeyframeModeDetector.RESPONSE_WINDOW_MS - 500 + 1).ms)
                        sending()
                        if (i < 2) clock.elapse((3000 - KeyframeModeDetector.RESPONSE_WINDOW_MS - 1).ms)
                    }
                    sentKeyframeRequests shouldHaveSize 3
                    detector.getMode(123L) shouldBe SenderKeyframeMode.PER_ENCODING
                    // 2.1s after the third request: past the 2s floor, inside the 2.67s computed for the whole
                    // source.
                    clock.elapse((2100 - KeyframeModeDetector.RESPONSE_WINDOW_MS - 1).ms)
                    sending()
                    keyframeRequester.requestKeyframe("ep2", 456L)
                    should("not apply the interval computed for the whole source to the encoding alone") {
                        sentKeyframeRequests shouldHaveSize 4
                    }
                }
                context("when the source bitrate is high enough to absorb the keyframe") {
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 20_000_000.bps) }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    clock.elapse(2100.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("fall back to the configured source-wide floor") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                }
                context("when the source is sending very little") {
                    // 480000 / (0.15 * 100000) = 32s, which is capped at max-interval (4s).
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 100_000.bps) }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    clock.elapse(3900.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("not send a second request before max-interval") {
                        sentKeyframeRequests shouldHaveSize 1
                    }
                    clock.elapse(200.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("send a second request after max-interval") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                }
                context("the cost supplier") {
                    var calls = 0
                    keyframeRequester.setKeyframeCostSupplier {
                        calls++
                        KeyframeCost(480_000L.bits, 1_200_000.bps)
                    }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    // Dropped, once inside the floor and once inside the derived interval.
                    clock.elapse(1.secs)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    clock.elapse(1500.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    // Sent.
                    clock.elapse(1.secs)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("only be consulted when a request is sent") {
                        sentKeyframeRequests shouldHaveSize 2
                        calls shouldBe 2
                    }
                    should("account for what the budget did in the node stats") {
                        val stats = keyframeRequester.getNodeStats().toJson()
                        // The first request had no limit computed yet, so it went at the floor; the second was
                        // governed by the 2.67s budget interval, 666ms longer than the 2s floor.
                        stats["num_requests_sent_at_floor"].asInt() shouldBe 1
                        stats["num_requests_sent_budget_lengthened"].asInt() shouldBe 1
                        stats["total_budget_extension_ms"].asLong() shouldBe 666L
                        // Of the two drops, only the one at 2.5s would have been accepted at the floor.
                        stats["num_requests_dropped_source_wide_limit"].asInt() shouldBe 2
                        stats["num_requests_dropped_by_budget"].asInt() shouldBe 1
                        // That receiver then waited from 2.5s until the request sent at 3.5s.
                        stats["num_budget_waits"].asInt() shouldBe 1
                        stats["total_budget_wait_ms"].asLong() shouldBe 1000L
                    }
                }
                context("with the budget not binding") {
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 20_000_000.bps) }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    clock.elapse(1.secs)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    clock.elapse(1500.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("attribute nothing to the budget in the node stats") {
                        sentKeyframeRequests shouldHaveSize 2
                        val stats = keyframeRequester.getNodeStats().toJson()
                        stats["num_requests_sent_at_floor"].asInt() shouldBe 2
                        stats["num_requests_sent_budget_lengthened"].asInt() shouldBe 0
                        stats["total_budget_extension_ms"].asLong() shouldBe 0L
                        stats["num_requests_dropped_source_wide_limit"].asInt() shouldBe 1
                        stats["num_requests_dropped_by_budget"].asInt() shouldBe 0
                        stats["num_budget_waits"].asInt() shouldBe 0
                    }
                }
                context("when no cost has been measured for the source") {
                    keyframeRequester.setKeyframeCostSupplier { null }
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    clock.elapse(2100.ms)
                    keyframeRequester.requestKeyframe("ep2", 123L)
                    should("use the configured source-wide floor") {
                        sentKeyframeRequests shouldHaveSize 2
                    }
                }
                context("distinguishing bridge-generated requests from forwarded PLIs in the budget stats") {
                    // 2.67s derived interval, as above.
                    keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 1_200_000.bps) }
                    // A bridge-generated (API) request opens the source-wide limit.
                    keyframeRequester.requestKeyframe("ep1", 123L)
                    context("when a forwarded PLI is dropped by the budget") {
                        clock.elapse(2100.ms)
                        sendPli(keyframeRequester, "ep2", 123L)
                        should("count against the base counters but not the api ones") {
                            val stats = keyframeRequester.getNodeStats().toJson()
                            stats["num_requests_dropped_by_budget"].asInt() shouldBe 1
                            stats["num_requests_dropped_by_budget_api"].asInt() shouldBe 0
                        }
                        context("and a bridge-generated request for the same source is also dropped") {
                            clock.elapse(200.ms)
                            keyframeRequester.requestKeyframe("ep3", 123L)
                            should("count against both the base and the api dropped counters") {
                                val stats = keyframeRequester.getNodeStats().toJson()
                                stats["num_requests_dropped_by_budget"].asInt() shouldBe 2
                                stats["num_requests_dropped_by_budget_api"].asInt() shouldBe 1
                            }
                            context("once the derived interval elapses and a request is finally sent") {
                                // 2100 + 200 + 400 = 2700ms since the first request, past the 2666ms interval.
                                clock.elapse(400.ms)
                                keyframeRequester.requestKeyframe("ep4", 123L)
                                should("resolve every waiting requester, attributing each by its own origin") {
                                    val stats = keyframeRequester.getNodeStats().toJson()
                                    // ep2 (forwarded) and ep3 (api) were both waiting; one sent request, from ep4,
                                    // resolves both of their waits, not just the one which happened to trigger it.
                                    stats["num_budget_waits"].asInt() shouldBe 2
                                    stats["num_budget_waits_api"].asInt() shouldBe 1
                                }
                            }
                        }
                    }
                }
            }
        }

        context("with keyframe budget limiting disabled") {
            keyframeRequester.setKeyframeCostSupplier { KeyframeCost(480_000L.bits, 1_200_000.bps) }
            keyframeRequester.requestKeyframe("ep1", 123L)
            clock.elapse(2100.ms)
            keyframeRequester.requestKeyframe("ep2", 123L)
            should("use the configured source-wide floor and ignore the cost") {
                sentKeyframeRequests shouldHaveSize 2
            }
        }
    }
}

/** Sends a FIR for [mediaSsrc] from [endpointId] through [keyframeRequester], as if forwarded from a receiver. */
private fun sendFir(keyframeRequester: KeyframeRequester, endpointId: String, mediaSsrc: Long) {
    val packetInfo = PacketInfo(RtcpFbFirPacketBuilder(mediaSenderSsrc = mediaSsrc, firCommandSeqNum = 7).build())
    packetInfo.endpointId = endpointId
    keyframeRequester.processPacket(packetInfo)
}

/** Sends a PLI for [mediaSsrc] from [endpointId] through [keyframeRequester], as if forwarded from a receiver. */
private fun sendPli(keyframeRequester: KeyframeRequester, endpointId: String, mediaSsrc: Long) {
    val packetInfo = PacketInfo(RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build())
    packetInfo.endpointId = endpointId
    keyframeRequester.processPacket(packetInfo)
}
