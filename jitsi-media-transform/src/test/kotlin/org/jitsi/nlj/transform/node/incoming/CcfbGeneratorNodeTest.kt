/*
 * Copyright @ 2026 - present 8x8, Inc.
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.LossListener
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.UnreceivedPacketInfo
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.abs
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.div
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Instant

class CcfbGeneratorNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val scheduler = FakeScheduledExecutorService()
    private val clock = scheduler.clock.apply { setTime(Instant.ofEpochSecond(1000)) }
    private val sent = mutableListOf<RtcpPacket>()
    private val streamInformationStore = StreamInformationStoreImpl()

    // RFC 8888 feedback is enabled by having at least one payload type which has "ack ccfb" signaled as a rtcp-fb.
    private val ccfbPayloadType = Vp8PayloadType(100, emptyMap(), setOf("ack ccfb"))
    private val tccPayloadType = Vp8PayloadType(100, emptyMap(), setOf("transport-cc"))

    private val lossListener = object : LossListener {
        var numReceived = 0
        var numLost = 0

        override fun packetReceived(previouslyReportedLost: Boolean) {
            numReceived++
            if (previouslyReportedLost) {
                numLost--
            }
        }

        override fun packetLost(numLost: Int) {
            this.numLost += numLost
        }
    }

    private val node = CcfbGeneratorNode({ sent.add(it) }, streamInformationStore, scheduler, StdoutLogger(), clock)
        .apply { addLossListener(lossListener) }

    private fun receive(seq: Int, ssrc: Long = 1234, marked: Boolean = false, ecn: EcnMarking = EcnMarking.kNotEct) {
        val packet = RtpPacket(ByteArray(1500), 0, 1500).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = marked
            payloadType = 100
            sequenceNumber = seq
            timestamp = 456L
            this.ssrc = ssrc
        }
        node.processPacket(
            PacketInfo(packet).apply {
                receivedTime = clock.instant()
                receivedEcn = ecn
            }
        )
    }

    private fun feedback(index: Int = 0) = sent[index].shouldBeInstanceOf<RtcpFbCcfbPacket>()

    init {
        context("when RFC 8888 feedback is not signaled") {
            streamInformationStore.addRtpPayloadType(tccPayloadType)
            repeat(100) {
                receive(seq = it, marked = it % 10 == 9)
                clock.elapse(10.ms)
                scheduler.run()
            }
            should("send no feedback and schedule nothing") {
                sent shouldHaveSize 0
                scheduler.numPendingJobs() shouldBe 0
                lossListener.numReceived shouldBe 0
            }
        }

        context("when a frame of packets ending with a marked packet is received") {
            streamInformationStore.addRtpPayloadType(ccfbPayloadType)
            val arrivalTimes = mutableListOf<Instant>()
            listOf(EcnMarking.kEct1, EcnMarking.kCe, EcnMarking.kEct1).forEachIndexed { i, ecn ->
                arrivalTimes.add(clock.instant())
                receive(seq = 100 + i, marked = i == 2, ecn = ecn)
                if (i < 2) {
                    clock.elapse(2.ms)
                }
            }

            should("send feedback as soon as the marked packet arrives") {
                sent shouldHaveSize 1
            }
            should("send nothing more when the timer set for the first packet runs") {
                clock.elapse(1.secs)
                scheduler.run()
                sent shouldHaveSize 1
                scheduler.numPendingJobs() shouldBe 0
            }
            should("report each packet's sequence number, arrival time and ECN marking") {
                val packets = feedback().packets
                packets.map { it.ssrc } shouldBe listOf(1234L, 1234L, 1234L)
                packets.map { it.sequenceNumber } shouldBe listOf(100, 101, 102)
                packets.map { it.shouldBeInstanceOf<ReceivedPacketInfo>().ecn } shouldBe
                    listOf(EcnMarking.kEct1, EcnMarking.kCe, EcnMarking.kEct1)
                packets.forEachIndexed { i, packet ->
                    val expectedOffset = java.time.Duration.between(arrivalTimes[i], clock.instant())
                    val offset = packet.shouldBeInstanceOf<ReceivedPacketInfo>().arrivalTimeOffset
                    // The offset has been through the packet's 1/1024 second resolution.
                    abs(offset - expectedOffset) shouldBeLessThan 1.secs / 1024
                }
            }
            should("report the packets to the loss listener as received") {
                lossListener.numReceived shouldBe 3
                lossListener.numLost shouldBe 0
            }
        }

        context("when packets without a marker are received") {
            streamInformationStore.addRtpPayloadType(ccfbPayloadType)
            receive(seq = 1)
            clock.elapse(10.ms)
            scheduler.run()
            receive(seq = 2)

            should("not send feedback at once, but schedule it") {
                sent shouldHaveSize 0
                scheduler.numPendingJobs() shouldBe 1
            }
            should("send feedback 25 ms after the first packet, even if no more packets arrive") {
                clock.elapse(14.ms)
                scheduler.run()
                sent shouldHaveSize 0

                clock.elapse(1.ms)
                scheduler.run()
                sent shouldHaveSize 1
                feedback().packets.map { it.sequenceNumber } shouldBe listOf(1, 2)
                // Nothing remains to be reported, so nothing remains scheduled.
                scheduler.numPendingJobs() shouldBe 0
            }
            should("not send feedback after being stopped") {
                node.stop()
                scheduler.numPendingJobs() shouldBe 0
                clock.elapse(1.secs)
                scheduler.run()
                sent shouldHaveSize 0
            }
        }

        context("when packets are missing") {
            streamInformationStore.addRtpPayloadType(ccfbPayloadType)
            receive(seq = 10)
            receive(seq = 13, marked = true)

            should("report them as not received, and as lost to the loss listener") {
                sent shouldHaveSize 1
                val packets = feedback().packets
                packets.map { it.sequenceNumber } shouldBe listOf(10, 11, 12, 13)
                packets.map { it is UnreceivedPacketInfo } shouldBe listOf(false, true, true, false)
                lossListener.numReceived shouldBe 2
                lossListener.numLost shouldBe 2
            }
            should("report one which arrives later as received, and no longer lost") {
                clock.elapse(30.ms)
                receive(seq = 12)
                receive(seq = 14, marked = true)

                sent shouldHaveSize 2
                val packets = feedback(1).packets
                packets.map { it.sequenceNumber } shouldBe listOf(12, 13, 14)
                packets.map { it is ReceivedPacketInfo } shouldBe listOf(true, true, true)
                lossListener.numReceived shouldBe 4
                lossListener.numLost shouldBe 1
            }
        }

        context("when packets of several streams are received") {
            streamInformationStore.addRtpPayloadType(ccfbPayloadType)
            receive(seq = 1, ssrc = 111)
            receive(seq = 500, ssrc = 222)
            receive(seq = 2, ssrc = 111, marked = true)

            should("report on all of them in one feedback packet") {
                sent shouldHaveSize 1
                feedback().packets.map { it.ssrc to it.sequenceNumber } shouldBe
                    listOf(111L to 1, 111L to 2, 222L to 500)
            }
        }
    }
}
