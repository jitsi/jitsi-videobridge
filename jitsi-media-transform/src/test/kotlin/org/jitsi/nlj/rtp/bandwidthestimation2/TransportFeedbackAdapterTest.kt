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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.UnreceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.NEVER
import org.jitsi.utils.TimeUtils
import org.jitsi.utils.isFinite
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.roundTo
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.times
import org.jitsi.utils.toDoubleMillis
import java.time.Duration
import java.time.Instant

/** Unit tests for TransportFeedbackAdapter,
 * based loosely on WebRTC modules/congestion_controller/rtp/transport_feedback_adapter_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 * modified to use Jitsi types for objects outside the congestion controller,
 * and not using Network Routes.
 */
private val kPacingInfo0 = PacedPacketInfo(0, 5, 2000)

data class PacketTemplate(
    val ssrc: Long = 1,
    val transportSequenceNumber: Long = 0,
    val rtpSequenceNumber: Int = 2,
    // TODO do we need mediaType?
    val packetSize: DataSize = 100.bytes,

    val ecn: EcnMarking = EcnMarking.kNotEct,
    val sendTimestamp: Instant = Instant.EPOCH,
    val pacingInfo: PacedPacketInfo = PacedPacketInfo(),
    var receiveTimestamp: Instant = NEVER,

    val isAudio: Boolean = false
)

fun createPacketTemplates(
    numberOfSsrcs: Int,
    packetsPerSsrc: Int,
    firstTransportSequenceNumber: Long = 99
): List<PacketTemplate> {
    var transportSequenceNumber: Long = firstTransportSequenceNumber
    var sendTime = Instant.EPOCH + 200.ms
    var receiveTime = Instant.EPOCH + 100.ms
    val packets = mutableListOf<PacketTemplate>()

    for (s in 0 until numberOfSsrcs) {
        val ssrc = (s + 3).toLong()
        for (r in ssrc * 10 until ssrc * 10 + packetsPerSsrc) {
            val rtpSequenceNumber = r.toInt()
            packets.add(
                PacketTemplate(
                    ssrc = ssrc,
                    transportSequenceNumber = transportSequenceNumber++,
                    rtpSequenceNumber = rtpSequenceNumber,
                    sendTimestamp = sendTime,
                    pacingInfo = kPacingInfo0,
                    receiveTimestamp = receiveTime,
                )
            )
            sendTime += 10.ms
            receiveTime += 13.ms
        }
    }
    return packets
}

private fun comparePacketFeedbackVectors(truth: List<PacketTemplate>, input: List<PacketResult>) {
    input.size shouldBe truth.size
    // truth contains the input data for the test, and input is what will be
    // sent to the bandwidth estimator. truth.arrival_tims_ms is used to
    // populate the transport feedback messages. As these times may be changed
    // (because of resolution limits in the packets, and because of the time
    // base adjustment performed by the TransportFeedbackAdapter at the first
    // packet, the truth[x].arrival_time and input[x].arrival_time may not be
    // equal. However, the difference must be the same for all x.
    val arrivalTimeDelta = Duration.between(input[0].receiveTime, truth[0].receiveTimestamp).toDoubleMillis()
    for (i in truth.indices) {
        withClue("feedback vector packet $i") {
            input[i].isReceived() shouldBe truth[i].receiveTimestamp.isFinite()
            if (input[i].isReceived()) {
                // Jitsi change from WebRTC: since we encode the feedback packets to their wire format, we
                // need to account for the rounding of arrival times in CCFB packets.
                Duration.between(input[i].receiveTime, truth[i].receiveTimestamp).toDoubleMillis() shouldBe
                    (arrivalTimeDelta plusOrMinus 1000.0 / 1024.0)
            }
            input[i].sentPacket.sendTime shouldBe truth[i].sendTimestamp
            input[i].sentPacket.sequenceNumber shouldBe truth[i].transportSequenceNumber
            input[i].sentPacket.size shouldBe truth[i].packetSize
            input[i].sentPacket.pacingInfo shouldBe truth[i].pacingInfo
            input[i].sentPacket.audio shouldBe truth[i].isAudio
            input[i].rtpPacketInfo?.rtpSequenceNumber shouldBe truth[i].rtpSequenceNumber
            input[i].rtpPacketInfo?.ssrc shouldBe truth[i].ssrc
            // TODO do we need to compare isRetransmission?
        }
    }
}

private fun createPacketToSend(packet: PacketTemplate): org.jitsi.nlj.PacketInfo {
    val length = packet.packetSize.bytes.toInt()
    val buf = BufferPool.getBuffer(length)
    buf.fill(0, 0, length)

    val sendPacket = RtpPacket(buffer = buf, offset = 0, length = length).apply {
        version = 2
        ssrc = packet.ssrc
        sequenceNumber = packet.rtpSequenceNumber
    }
    val packetInfo = org.jitsi.nlj.PacketInfo(sendPacket).apply {
        probingInfo = packet.pacingInfo
    }

    return packetInfo
}

private fun buildRtcpTransportFeedbackPacket(packets: List<PacketTemplate>): RtcpFbTccPacket {
    val feedbackBuilder = RtcpFbTccPacketBuilder()
    feedbackBuilder.SetBase(
        (packets[0].transportSequenceNumber and 0xFFFF).toInt(),
        packets[0].receiveTimestamp
    )
    packets.forEach { packet ->
        if (packet.receiveTimestamp.isFinite()) {
            feedbackBuilder.AddReceivedPacket(
                (packet.transportSequenceNumber and 0xFFFF).toInt(),
                packet.receiveTimestamp
            )
        }
    }
    return feedbackBuilder.build()
}

private fun buildRtcpCongestionControlFeedbackPacket(packets: List<PacketTemplate>): RtcpFbCcfbPacket {
    // Assume the feedback was sent when the last packet was received.
    // JITSI modification: interpret "last" by time rather than by sequence number
    val feedbackSentTime =
        packets.filter { it.receiveTimestamp.isFinite() }.maxOfOrNull { it.receiveTimestamp } ?: NEVER

    val compactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(feedbackSentTime.toEpochMilli()))

    val packetInfos = mutableListOf<org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.PacketInfo>()
    packets.forEach { packet ->
        val packetInfo = if (packet.receiveTimestamp.isFinite()) {
            ReceivedPacketInfo(
                ssrc = packet.ssrc,
                sequenceNumber = packet.rtpSequenceNumber,
                arrivalTimeOffset = Duration.between(packet.receiveTimestamp, feedbackSentTime),
                ecn = packet.ecn
            )
        } else {
            UnreceivedPacketInfo(ssrc = packet.ssrc, sequenceNumber = packet.rtpSequenceNumber)
        }
        packetInfos.add(packetInfo)
    }
    val feedbackBuilder = RtcpFbCcfbPacketBuilder(reportTimestampCompactNtp = compactNtp, packets = packetInfos)

    return feedbackBuilder.build()
}

enum class FeedbackType {
    Ccfb,
    Tcc
}

private fun timeNow() = Instant.EPOCH + 1234.ms

class OneTransportFeedbackAdapterTest(val feedbackType: FeedbackType) {
    private val logger = createLogger()

    fun createAndProcessFeedback(packets: List<PacketTemplate>): TransportPacketsFeedback? {
        when (feedbackType) {
            FeedbackType.Tcc -> {
                val rtcpFeedback = buildRtcpTransportFeedbackPacket(packets)
                return adapter.processTransportFeedback(rtcpFeedback, timeNow())
            }
            FeedbackType.Ccfb -> {
                val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
                return adapter.processCongestionControlFeedback(rtcpFeedback, timeNow())
            }
        }
    }

    val clock = FakeClock()
    val adapter = TransportFeedbackAdapter(logger)
}

class TransportFeedbackAdapterTest : FreeSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        BufferPool.getBuffer = { size -> ByteArray(size + 10) }
    }

    init {
        "AdaptsFeedbackAndPopulatesSendTimes" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets = createPacketTemplates(numberOfSsrcs = 2, packetsPerSsrc = 3)

                    packets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }
                    val adaptedFeedback = test.createAndProcessFeedback(packets)
                    comparePacketFeedbackVectors(packets, adaptedFeedback!!.packetFeedbacks)
                }
            }
        }

        "FeedbackVectorReportsUnreceived" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val sentPackets = createPacketTemplates(numberOfSsrcs = 2, packetsPerSsrc = 3)

                    sentPackets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    // Note: Important to include the last packet per SSRC, as only unreceived
                    // packets in between received packets can be inferred.
                    sentPackets[1].receiveTimestamp = NEVER
                    sentPackets[4].receiveTimestamp = NEVER
                    val adaptedFeedback = test.createAndProcessFeedback(sentPackets)
                    comparePacketFeedbackVectors(sentPackets, adaptedFeedback!!.packetFeedbacks)
                }
            }
        }

        "HandlesDroppedPackets" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets =
                        createPacketTemplates(numberOfSsrcs = 2, packetsPerSsrc = 3, firstTransportSequenceNumber = 0)

                    val kSendSideDropBefore = 1
                    val kReceiveSideDropAfter = 3

                    val sentPackets = mutableListOf<PacketTemplate>()

                    packets.forEach { packet ->
                        if (packet.transportSequenceNumber >= kSendSideDropBefore) {
                            sentPackets.add(packet)
                        }
                    }

                    sentPackets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    val receivedPackets = mutableListOf<PacketTemplate>()
                    packets.forEach { packet ->
                        if (packet.transportSequenceNumber <= kReceiveSideDropAfter) {
                            receivedPackets.add(packet)
                        }
                    }

                    val adaptedFeedback = test.createAndProcessFeedback(receivedPackets)

                    val expectedPackets = packets.subList(kSendSideDropBefore, kReceiveSideDropAfter + 1)

                    comparePacketFeedbackVectors(expectedPackets, adaptedFeedback!!.packetFeedbacks)
                }
            }
        }

        // Skipped: "FeedbackReportsIfPacketIsAudio"

        "ReceiveTimeWrapsBothWays" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val kHighArrivalTime = RtcpFbTccPacket.Companion.kDeltaScaleFactor * (1 shl 8) * ((1 shl 23) - 1)
                    val packets = listOf(
                        PacketTemplate(
                            transportSequenceNumber = 0,
                            rtpSequenceNumber = 102,
                            receiveTimestamp = Instant.EPOCH + kHighArrivalTime + 64.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 103,
                            receiveTimestamp = Instant.EPOCH + kHighArrivalTime - 64.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 104,
                            receiveTimestamp = Instant.EPOCH + kHighArrivalTime
                        )
                    )

                    packets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    packets.forEach { packet ->
                        val receivedPackets = listOf(packet)
                        val result: TransportPacketsFeedback?
                        when (test.feedbackType) {
                            FeedbackType.Ccfb -> {
                                val feedback = buildRtcpCongestionControlFeedbackPacket(receivedPackets)
                                val rawBuffer = feedback.buffer
                                val offset = feedback.offset
                                val length = feedback.length

                                val parsedFeedback = RtcpFbCcfbPacket(rawBuffer, offset, length)
                                result = test.adapter.processCongestionControlFeedback(parsedFeedback, timeNow())
                            }

                            FeedbackType.Tcc -> {
                                val feedback = buildRtcpTransportFeedbackPacket(receivedPackets)
                                val rawBuffer = feedback.buffer
                                val offset = feedback.offset
                                val length = feedback.length

                                val parsedFeedback = RtcpFbTccPacket(rawBuffer, offset, length)
                                result = test.adapter.processTransportFeedback(parsedFeedback, timeNow())
                            }
                        }
                        result shouldNotBe null
                        comparePacketFeedbackVectors(receivedPackets, result!!.packetFeedbacks)
                    }
                }
            }
        }

        "HandlesArrivalReordering" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets = listOf(
                        PacketTemplate(
                            transportSequenceNumber = 0,
                            rtpSequenceNumber = 101,
                            sendTimestamp = Instant.EPOCH + 200.ms,
                            receiveTimestamp = Instant.EPOCH + 120.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 102,
                            sendTimestamp = Instant.EPOCH + 210.ms,
                            receiveTimestamp = Instant.EPOCH + 110.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 103,
                            sendTimestamp = Instant.EPOCH + 220.ms,
                            receiveTimestamp = Instant.EPOCH + 100.ms
                        ),
                    )

                    packets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    // Adapter keeps the packets ordered by sequence number (which is itself
                    // assigned by the order of transmission). Reordering by some other criteria,
                    // eg. arrival time, is up to the observers.
                    val adaptedFeedback = test.createAndProcessFeedback(packets)
                    comparePacketFeedbackVectors(packets, adaptedFeedback!!.packetFeedbacks)
                }
            }
        }

        "IgnoreDuplicatePacketSentCalls" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packet = PacketTemplate()
                    // Add a packet and then mark it as sent.
                    test.adapter.addPacket(
                        createPacketToSend(packet),
                        packet.transportSequenceNumber,
                        0.bytes,
                        timeNow()
                    )
                    val sentPacket = test.adapter.processSentPacket(
                        SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp, PacketInfo())
                    )
                    sentPacket shouldNotBe null

                    // Call ProcessSentPacket() again with the same sequence number. This packet
                    // has already been marked as sent and the call should be ignored.
                    val duplicatePacket = test.adapter.processSentPacket(
                        SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp, PacketInfo())
                    )
                    duplicatePacket shouldBe null
                }
            }
        }

        "SendReceiveTimeDiffTimeContinuouseBetweenFeedback" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets = listOf(
                        PacketTemplate(
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 101,
                            sendTimestamp = Instant.EPOCH + 100.ms,
                            pacingInfo = kPacingInfo0,
                            receiveTimestamp = Instant.EPOCH + 200.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 102,
                            sendTimestamp = Instant.EPOCH + 110.ms,
                            pacingInfo = kPacingInfo0,
                            receiveTimestamp = Instant.EPOCH + 210.ms
                        )
                    )

                    packets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    val adaptedFeedback1 = test.createAndProcessFeedback(listOf(packets[0]))
                    val adaptedFeedback2 = test.createAndProcessFeedback(listOf(packets[1]))

                    adaptedFeedback1!!.packetFeedbacks.size shouldBe adaptedFeedback2!!.packetFeedbacks.size
                    adaptedFeedback1.packetFeedbacks.size shouldBe 1
                    Duration.between(
                        adaptedFeedback1.packetFeedbacks[0].sentPacket.sendTime,
                        adaptedFeedback1.packetFeedbacks[0].receiveTime
                    ).roundTo(1.ms) shouldBe
                        Duration.between(
                            adaptedFeedback2.packetFeedbacks[0].sentPacket.sendTime,
                            adaptedFeedback2.packetFeedbacks[0].receiveTime
                        ).roundTo(1.ms)
                }
            }
        }

        "ProcessSentPacketIncreaseOutstandingData" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)

                    val packet1 = PacketTemplate(transportSequenceNumber = 1, packetSize = 200.bytes)
                    val packet2 = PacketTemplate(transportSequenceNumber = 2, packetSize = 300.bytes)
                    test.adapter.addPacket(
                        createPacketToSend(packet1),
                        packet1.transportSequenceNumber,
                        0.bytes,
                        timeNow()
                    )
                    val sentPacket1 = test.adapter.processSentPacket(
                        SentPacketInfo(packet1.transportSequenceNumber, packet1.sendTimestamp)
                    )

                    sentPacket1 shouldNotBe null
                    sentPacket1!!.sequenceNumber shouldBe packet1.transportSequenceNumber
                    // Only one packet in flight
                    sentPacket1.dataInFlight shouldBe packet1.packetSize
                    test.adapter.getOutstandingData() shouldBe packet1.packetSize

                    test.adapter.addPacket(
                        createPacketToSend(packet2),
                        packet2.transportSequenceNumber,
                        0.bytes,
                        timeNow()
                    )
                    val sentPacket2 = test.adapter.processSentPacket(
                        SentPacketInfo(packet2.transportSequenceNumber, packet2.sendTimestamp)
                    )

                    sentPacket2 shouldNotBe null
                    // Two packets in flight.
                    sentPacket2!!.dataInFlight shouldBe packet1.packetSize + packet2.packetSize

                    test.adapter.getOutstandingData() shouldBe packet1.packetSize + packet2.packetSize
                }
            }
        }

        "TransportPacketFeedbackHasDataInFlight" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)

                    val packets = listOf(
                        PacketTemplate(
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 101,
                            packetSize = 200.bytes,
                            sendTimestamp = Instant.EPOCH + 100.ms,
                            pacingInfo = kPacingInfo0,
                            receiveTimestamp = Instant.EPOCH + 200.ms,
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 102,
                            packetSize = 300.bytes,
                            sendTimestamp = Instant.EPOCH + 110.ms,
                            pacingInfo = kPacingInfo0,
                            receiveTimestamp = Instant.EPOCH + 210.ms,
                        )
                    )

                    packets.forEach { packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        test.adapter.processSentPacket(
                            SentPacketInfo(
                                packet.transportSequenceNumber,
                                packet.sendTimestamp
                            )
                        )
                    }

                    val adaptedFeedback1 = test.createAndProcessFeedback(listOf(packets[0]))
                    val adaptedFeedback2 = test.createAndProcessFeedback(listOf(packets[1]))
                    adaptedFeedback1!!.dataInFlight shouldBe packets[1].packetSize
                    adaptedFeedback2!!.dataInFlight shouldBe DataSize.ZERO
                }
            }
        }

        "CongestionControlFeedbackResultHasEcn" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    ecn = EcnMarking.kCe,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    ecn = EcnMarking.kEct1,
                    sendTimestamp = Instant.EPOCH + 110.ms,
                    receiveTimestamp = Instant.EPOCH + 210.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            val adaptedFeedback = test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow())

            adaptedFeedback!!.packetFeedbacks.size shouldBe 2
            adaptedFeedback.packetFeedbacks[0].ecn shouldBe EcnMarking.kCe
            adaptedFeedback.packetFeedbacks[1].ecn shouldBe EcnMarking.kEct1
            adaptedFeedback.transportSupportsEcn shouldBe true
        }

        "ReportTransportDoesNotSupportEcnIfFeedbackContainNotEctPacket" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    ecn = EcnMarking.kCe,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    ecn = EcnMarking.kNotEct,
                    sendTimestamp = Instant.EPOCH + 110.ms,
                    receiveTimestamp = Instant.EPOCH + 210.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            val adaptedFeedback = test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow())

            adaptedFeedback!!.transportSupportsEcn shouldBe false
            adaptedFeedback.packetFeedbacks.size shouldBe 2
        }
    }
}
