/*
 * Copyright @ 2019 - present 8x8, Inc.
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
// This file uses WebRTC's naming style for constants
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.scream.ScreamNetworkController
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.TimeUtils
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import java.time.Duration

/** Jitsi local: tests of how GoogCcTransportCcEngine chooses its network controller and marks packets ECT(1). */
class GoogCcTransportCcEngineTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val logger = createLogger()
    private val scheduler = FakeScheduledExecutorService()
    private val clock = scheduler.clock

    private val targetRates = mutableListOf<Bandwidth>()

    private fun createEngine(screamMode: ScreamMode) = GoogCcTransportCcEngine(
        DiagnosticContext(),
        logger,
        scheduler,
        { _, _ -> 0 },
        clock,
        screamMode
    ).also {
        it.addBandwidthListener(object : TransportCcEngine.BandwidthListener {
            override fun bandwidthEstimationChanged(newValue: Bandwidth) {
                targetRates.add(newValue)
            }
        })
        it.start()
    }

    private fun GoogCcTransportCcEngine.controllerStats() =
        (getStatistics() as GoogCcTransportCcEngine.StatisticsSnapshot).networkControllerState

    private var nextSeqNum = 1

    /** Sends a packet through the engine, and returns it. */
    private fun GoogCcTransportCcEngine.sendPacket(): PacketInfo {
        val length = 1000
        val buf = ByteArray(length + 10)
        val rtpPacket = RtpPacket(buffer = buf, offset = 0, length = length).apply {
            version = 2
            ssrc = kSsrc
            sequenceNumber = nextSeqNum
        }
        val packetInfo = PacketInfo(rtpPacket)
        mediaPacketTagged(packetInfo, nextSeqNum.toLong())
        mediaPacketSent(packetInfo, nextSeqNum.toLong())
        ++nextSeqNum
        return packetInfo
    }

    /** Has the engine receive RFC 8888 feedback which reports the packet with [seqNum] received with [ecn]. */
    private fun GoogCcTransportCcEngine.receiveFeedback(seqNum: Int, ecn: EcnMarking) {
        val feedback = RtcpFbCcfbPacketBuilder(
            reportTimestampCompactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(clock.instant().toEpochMilli())),
            packets = listOf(
                ReceivedPacketInfo(ssrc = kSsrc, sequenceNumber = seqNum, arrivalTimeOffset = Duration.ZERO, ecn = ecn)
            )
        ).build()
        rtcpPacketReceived(feedback, clock.instant())
    }

    init {
        BufferPool.getBuffer = { size -> ByteArray(size + 10) }

        context("With SCReAM never used") {
            val engine = createEngine(ScreamMode.GoogCc)
            should("use Google CC") {
                engine.controllerStats().shouldBeInstanceOf<GoogCcNetworkController.StatisticsSnapshot>()
            }
            should("use Google CC with RFC 8888 feedback too") {
                engine.setRfc8888FeedbackNegotiated(true)
                engine.controllerStats().shouldBeInstanceOf<GoogCcNetworkController.StatisticsSnapshot>()
            }
            should("not flag packets to be sent as ECT(1), since Google CC does not adapt to ECN") {
                engine.setRfc8888FeedbackNegotiated(true)
                engine.setTransportSupportsEct1(true)
                engine.sendPacket().sendAsEct1 shouldBe false
            }
        }

        context("With SCReAM used with RFC 8888 feedback") {
            val engine = createEngine(ScreamMode.ScreamWithRfc8888)
            should("use Google CC until RFC 8888 feedback is negotiated") {
                engine.controllerStats().shouldBeInstanceOf<GoogCcNetworkController.StatisticsSnapshot>()
            }
            should("use SCReAM once RFC 8888 feedback is negotiated") {
                engine.setRfc8888FeedbackNegotiated(true)
                engine.controllerStats().shouldBeInstanceOf<ScreamNetworkController.StatisticsSnapshot>()
            }
            should("go back to Google CC if RFC 8888 feedback is no longer negotiated") {
                engine.setRfc8888FeedbackNegotiated(true)
                engine.setRfc8888FeedbackNegotiated(false)
                engine.controllerStats().shouldBeInstanceOf<GoogCcNetworkController.StatisticsSnapshot>()
            }
            should("start the new controller from the last target rate") {
                targetRates.last() shouldBe BandwidthEstimatorConfig.initBw
                val before = targetRates.size
                engine.setRfc8888FeedbackNegotiated(true)
                // The new controller reports its first target rate when it is told the network is available.
                targetRates.size shouldBe before + 1
                targetRates.last() shouldBe BandwidthEstimatorConfig.initBw
            }
            should("keep the controller when the same format is negotiated again") {
                engine.setRfc8888FeedbackNegotiated(true)
                val before = targetRates.size
                engine.setRfc8888FeedbackNegotiated(true)
                targetRates.size shouldBe before
            }

            context("when the transport can send ECT(1)") {
                engine.setTransportSupportsEct1(true)
                should("not flag packets before RFC 8888 feedback is negotiated") {
                    engine.sendPacket().sendAsEct1 shouldBe false
                }
                should("flag packets once RFC 8888 feedback is negotiated") {
                    engine.setRfc8888FeedbackNegotiated(true)
                    engine.sendPacket().sendAsEct1 shouldBe true
                }
                should("keep flagging packets while feedback shows that the marking arrives") {
                    engine.setRfc8888FeedbackNegotiated(true)
                    val seqNum = nextSeqNum
                    engine.sendPacket().sendAsEct1 shouldBe true
                    clock.elapse(20.ms)
                    engine.receiveFeedback(seqNum, EcnMarking.kEct1)
                    engine.sendPacket().sendAsEct1 shouldBe true
                }
                should("stop flagging packets when feedback shows that the path removes the marking") {
                    engine.setRfc8888FeedbackNegotiated(true)
                    val seqNum = nextSeqNum
                    engine.sendPacket().sendAsEct1 shouldBe true
                    clock.elapse(20.ms)
                    engine.receiveFeedback(seqNum, EcnMarking.kNotEct)
                    engine.sendPacket().sendAsEct1 shouldBe false

                    // A new route may keep the marking.
                    engine.onNetworkRouteChanged()
                    engine.sendPacket().sendAsEct1 shouldBe true
                }
            }

            context("when the transport cannot send ECT(1)") {
                should("not flag packets") {
                    engine.setRfc8888FeedbackNegotiated(true)
                    engine.sendPacket().sendAsEct1 shouldBe false
                }
            }
        }

        context("With SCReAM always used") {
            val engine = createEngine(ScreamMode.ScreamWithTwccOrRfc8888)
            should("use SCReAM from the start") {
                engine.controllerStats().shouldBeInstanceOf<ScreamNetworkController.StatisticsSnapshot>()
            }
            should("not flag packets to be sent as ECT(1) without RFC 8888 feedback") {
                engine.setTransportSupportsEct1(true)
                engine.sendPacket().sendAsEct1 shouldBe false
            }
        }
    }

    companion object {
        private const val kSsrc = 0x12345678L
    }
}
