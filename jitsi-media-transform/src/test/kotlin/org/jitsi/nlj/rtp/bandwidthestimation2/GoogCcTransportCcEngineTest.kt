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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.pacing.PacerControl
import org.jitsi.nlj.rtp.scream.ScreamNetworkController
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.scaledBy
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.TimeUtils
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration

/** Jitsi local: tests of how GoogCcTransportCcEngine chooses its network controller and marks packets ECT(1). */
class GoogCcTransportCcEngineTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val logger = createLogger()
    private val scheduler = FakeScheduledExecutorService()
    private val clock = scheduler.clock

    private val targetRates = mutableListOf<Bandwidth>()

    /** The number of times the engine has asked for a probe to be sent. */
    private var numProbesRequested = 0

    private fun createEngine(screamMode: ScreamMode, pacer: PacerControl? = null) = GoogCcTransportCcEngine(
        DiagnosticContext(),
        logger,
        scheduler,
        { _, _ ->
            numProbesRequested++
            0
        },
        clock,
        screamMode
    ).also {
        it.setPacer(pacer)
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

    /** A pacer that records what the engine tells it. */
    private class FakePacer : PacerControl {
        val pacerConfigs = mutableListOf<PacerConfig>()
        val congestedStates = mutableListOf<Boolean>()
        val probeClusters = mutableListOf<ProbeClusterConfig>()

        override fun setPacerConfig(config: PacerConfig) {
            pacerConfigs.add(config)
        }

        override fun setCongested(congested: Boolean) {
            congestedStates.add(congested)
        }

        override fun createProbeClusters(configs: List<ProbeClusterConfig>) {
            probeClusters.addAll(configs)
        }

        var queueSize = DataSize.ZERO
        var expectedQueueTime: Duration = Duration.ZERO

        override fun queueSizeData(): DataSize = queueSize

        override fun expectedQueueTime(): Duration = expectedQueueTime
    }

    init {
        BufferPool.getBuffer = { size -> ByteArray(size + 10) }

        context("Without a pacer") {
            val engine = createEngine(ScreamMode.GoogCc)
            should("send the probes of Google CC itself") {
                for (i in 1..20) {
                    engine.sendPacket()
                    scheduler.runUntil(clock.instant() + 5.ms)
                }
                numProbesRequested shouldBeGreaterThan 0
            }
        }

        context("With a pacer and Google CC") {
            val pacer = FakePacer()
            val engine = createEngine(ScreamMode.GoogCc, pacer)
            should("set the pacing rate of the pacer when it starts") {
                pacer.pacerConfigs.isNotEmpty() shouldBe true
                pacer.pacerConfigs.last().dataRate() shouldBeGreaterThan Bandwidth.ZERO
            }
            should("give the probe clusters to the pacer and send no probes itself") {
                for (i in 1..20) {
                    engine.sendPacket()
                    scheduler.runUntil(clock.instant() + 5.ms)
                }
                pacer.probeClusters.isNotEmpty() shouldBe true
                numProbesRequested shouldBe 0
            }
            should("not report congestion before there is feedback, since Google CC has no congestion window then") {
                for (i in 1..200) {
                    engine.sendPacket()
                }
                pacer.congestedStates shouldBe emptyList()
            }
            should("report a rate of zero while the queue of the pacer is too long") {
                val targetRate = targetRates.last()
                targetRate shouldBeGreaterThan Bandwidth.ZERO

                pacer.expectedQueueTime = 3.secs
                scheduler.runUntil(clock.instant() + 30.ms)
                targetRates.last() shouldBe Bandwidth.ZERO

                pacer.expectedQueueTime = 100.ms
                scheduler.runUntil(clock.instant() + 30.ms)
                targetRates.last() shouldBe targetRate
            }
        }

        context("With a pacer and SCReAM") {
            val pacer = FakePacer()
            val engine = createEngine(ScreamMode.ScreamWithRfc8888, pacer)
            engine.setRfc8888FeedbackNegotiated(true)
            should("set the pacing rate of the pacer for the new controller") {
                engine.controllerStats().shouldBeInstanceOf<ScreamNetworkController.StatisticsSnapshot>()
                pacer.pacerConfigs.size shouldBeGreaterThan 1
            }
            should("tell the pacer when the data in flight reaches the congestion window and when it falls below") {
                // Send without feedback until the data in flight reaches the window of SCReAM.
                val firstSeqNum = nextSeqNum
                var sent = 0
                while (pacer.congestedStates.isEmpty() && sent < 1000) {
                    engine.sendPacket()
                    sent++
                }
                pacer.congestedStates shouldBe listOf(true)

                // Feedback for the packets takes them out of the data in flight.
                clock.elapse(20.ms)
                for (seqNum in firstSeqNum until nextSeqNum) {
                    engine.receiveFeedback(seqNum, EcnMarking.kEct1)
                }
                pacer.congestedStates shouldBe listOf(true, false)
            }
            should("report a lower rate when the controller reports a reduce ratio for the queue of the pacer") {
                val targetRate = targetRates.last()

                // SCReAM asks for the most reduction when the queue takes more than 500 ms to send.
                pacer.queueSize = (10 * 1000 * 1000).bytes
                scheduler.runUntil(clock.instant() + 30.ms)
                // The most that is taken off the target rate is half.
                targetRates.last() shouldBe (targetRate.bps - targetRate.bps / 2).bps

                pacer.queueSize = DataSize.ZERO
                scheduler.runUntil(clock.instant() + 30.ms)
                targetRates.last() shouldBe targetRate
            }
        }

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

            should("restart SCReAM from its target rate when the network route changes") {
                engine.setRfc8888FeedbackNegotiated(true)
                val seqNum = nextSeqNum
                engine.sendPacket()
                clock.elapse(20.ms)
                engine.receiveFeedback(seqNum, EcnMarking.kEct1)
                val targetRate = targetRates.last()
                val numTargetRates = targetRates.size

                engine.onNetworkRouteChanged()
                engine.controllerStats().shouldBeInstanceOf<ScreamNetworkController.StatisticsSnapshot>()

                // SCReAM starts over from the rate it had, and reports a rate again with the next feedback. With
                // one packet in a 20 ms round trip its window is small, so the rate is somewhat lower.
                val seqNum2 = nextSeqNum
                engine.sendPacket()
                clock.elapse(20.ms)
                engine.receiveFeedback(seqNum2, EcnMarking.kEct1)
                targetRates.size shouldBe numTargetRates + 1
                targetRates.last() shouldBeGreaterThanOrEqualTo targetRate.scaledBy(0.8)
                targetRates.last() shouldBeLessThanOrEqualTo targetRate
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
