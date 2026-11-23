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
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.PacketOrigin
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
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.times
import org.jitsi.utils.toDoubleMillis
import java.time.Duration
import java.time.Instant

/** Unit tests for TransportFeedbackAdapter,
 * based loosely on WebRTC modules/congestion_controller/rtp/transport_feedback_adapter_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156)
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

    val sendAsEct1: Boolean = true,
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
        sendAsEct1 = packet.sendAsEct1
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

// Returns feedback matching transport sequence number.
private fun findFeedback(report: TransportPacketsFeedback?, transportSequenceNumber: Long): PacketResult? =
    report?.packetFeedbacks?.find { it.sentPacket.sequenceNumber == transportSequenceNumber }

class OneTransportFeedbackAdapterTest(val feedbackType: FeedbackType) {
    private val logger = createLogger()

    fun createAndProcessFeedback(
        packets: List<PacketTemplate>,
        feedbackReceiveTime: Instant? = null
    ): TransportPacketsFeedback? {
        val receiveTime = feedbackReceiveTime ?: timeNow()
        when (feedbackType) {
            FeedbackType.Tcc -> {
                val rtcpFeedback = buildRtcpTransportFeedbackPacket(packets)
                return adapter.processTransportFeedback(rtcpFeedback, receiveTime)
            }

            FeedbackType.Ccfb -> {
                val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
                return adapter.processCongestionControlFeedback(rtcpFeedback, receiveTime)
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

        "CountsFeedbackForPacketsNotIndicatedAsSent" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets =
                        createPacketTemplates(numberOfSsrcs = 1, packetsPerSsrc = 4, firstTransportSequenceNumber = 0)

                    // The packets at index 1 and 2 are tagged, but never sent (for example, dropped after tagging).
                    val notSent = setOf(1, 2)
                    packets.forEachIndexed { i, packet ->
                        test.adapter.addPacket(
                            createPacketToSend(packet),
                            packet.transportSequenceNumber,
                            0.bytes,
                            timeNow()
                        )
                        if (i !in notSent) {
                            test.adapter.processSentPacket(
                                SentPacketInfo(
                                    packet.transportSequenceNumber,
                                    packet.sendTimestamp
                                )
                            )
                        }
                    }
                    test.adapter.totalFeedbackBeforeSent shouldBe 0

                    // The receiver did not get the packets that were not sent. It reports them as not received,
                    // because it received a later packet.
                    notSent.forEach { i -> packets[i].receiveTimestamp = NEVER }
                    val adaptedFeedback = test.createAndProcessFeedback(packets)

                    comparePacketFeedbackVectors(listOf(packets[0], packets[3]), adaptedFeedback!!.packetFeedbacks)
                    test.adapter.totalFeedbackBeforeSent shouldBe 2
                    test.adapter.totalUnmatchedReports shouldBe 2
                    test.adapter.getStatisitics().totalFeedbackBeforeSent shouldBe 2
                    test.adapter.getStatisitics().toJson()["total_feedback_before_sent"].asLong() shouldBe 2
                }
            }
        }

        "KeepsReceiveTimesAfterUnmatchedReceivedPackets" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)
                    val packets =
                        createPacketTemplates(numberOfSsrcs = 1, packetsPerSsrc = 4, firstTransportSequenceNumber = 0)

                    // The packets at index 1 and 2 are not in the send-time history, but the feedback reports them
                    // as received.
                    val notInHistory = setOf(1, 2)
                    packets.forEachIndexed { i, packet ->
                        if (i !in notInHistory) {
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
                    }

                    val adaptedFeedback = test.createAndProcessFeedback(packets)

                    // The packet after the unmatched ones keeps its receive time.
                    comparePacketFeedbackVectors(listOf(packets[0], packets[3]), adaptedFeedback!!.packetFeedbacks)
                    test.adapter.totalUnmatchedReports shouldBe 2
                    test.adapter.totalFeedbackBeforeSent shouldBe 0
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
                    // The packet that was never added to the history is unmatched, but it does not count as
                    // feedback before the packet was indicated as sent.
                    test.adapter.totalUnmatchedReports shouldBe 1
                    test.adapter.totalFeedbackBeforeSent shouldBe 0
                }
            }
        }

        // Send packets on two SSRCs, and drop all packets on one of them.
        // Expect TransportFeedbackAdapter to detect such missing packets.
        // This test is trivial for TransportFeedback RTCP message where packets are
        // identified by transport sequence number, and missing numbers is the primary
        // way to report and track dropped packets.
        // However for CongestionControl RTCP message that is not natural - when no
        // packets are received on certain SSRC, such SSRC is not included into the
        // report, yet TransportFeedbackAdapter still can treat such packets as lost.
        "HandlesDroppedPacketsFromAnSsrc" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val kSsrc1 = 123L
                    val kSsrc2 = 456L
                    val test = OneTransportFeedbackAdapterTest(it)

                    val packets = listOf(
                        PacketTemplate(
                            ssrc = kSsrc1,
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 101,
                            sendTimestamp = Instant.EPOCH + 1_200.ms,
                            receiveTimestamp = Instant.EPOCH + 100.ms
                        ),
                        PacketTemplate(
                            ssrc = kSsrc2,
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 201,
                            sendTimestamp = Instant.EPOCH + 1_210.ms
                        ),
                        PacketTemplate(
                            ssrc = kSsrc2,
                            transportSequenceNumber = 3,
                            rtpSequenceNumber = 202,
                            sendTimestamp = Instant.EPOCH + 1_220.ms
                        ),
                        PacketTemplate(
                            ssrc = kSsrc1,
                            transportSequenceNumber = 4,
                            rtpSequenceNumber = 102,
                            sendTimestamp = Instant.EPOCH + 1_230.ms,
                            receiveTimestamp = Instant.EPOCH + 130.ms
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
                            SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp)
                        )
                    }

                    val receivedPackets = listOf(packets[0], packets[3])
                    val adaptedFeedback = test.createAndProcessFeedback(receivedPackets)

                    comparePacketFeedbackVectors(packets, adaptedFeedback!!.packetFeedbacks)
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

        "PacketsInLostFeedbackAreNotReportedAsLost" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)

                    val packets = listOf(
                        PacketTemplate(
                            transportSequenceNumber = 1,
                            rtpSequenceNumber = 101,
                            sendTimestamp = Instant.EPOCH + 100.ms,
                            receiveTimestamp = Instant.EPOCH + 120.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 2,
                            rtpSequenceNumber = 102,
                            sendTimestamp = Instant.EPOCH + 110.ms,
                            receiveTimestamp = Instant.EPOCH + 130.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 3,
                            rtpSequenceNumber = 103,
                            sendTimestamp = Instant.EPOCH + 120.ms,
                            receiveTimestamp = Instant.EPOCH + 140.ms
                        ),
                        PacketTemplate(
                            transportSequenceNumber = 4,
                            rtpSequenceNumber = 104,
                            sendTimestamp = Instant.EPOCH + 130.ms,
                            receiveTimestamp = Instant.EPOCH + 150.ms
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
                            SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp)
                        )
                    }

                    // Feedback 1: Covers packet 1
                    val feedback1Packets = listOf(packets[0])
                    test.createAndProcessFeedback(feedback1Packets)

                    // Feedback 2 (Lost network packet, never processed): would cover packet 2.

                    // Feedback 3: Covers packet 3
                    val feedback3Packets = listOf(packets[2])
                    val adaptedFeedback3 = test.createAndProcessFeedback(feedback3Packets)

                    // Packet 2 is not reported as lost.
                    findFeedback(adaptedFeedback3, 2) shouldBe null

                    // Feedback 4: Covers packet 4
                    val feedback4Packets = listOf(packets[3])
                    val adaptedFeedback4 = test.createAndProcessFeedback(feedback4Packets)

                    // Both CCFB and TWCC never infer packet 2 as lost unless explicitly skipped
                    // within the sequence number range of a single received feedback packet.
                    findFeedback(adaptedFeedback4, 2) shouldBe null
                }
            }
        }

        "HandlesReorderedFeedbackPackets" {
            FeedbackType.entries.forEach {
                withClue(it) {
                    val test = OneTransportFeedbackAdapterTest(it)

                    val packet1 = PacketTemplate(
                        transportSequenceNumber = 1,
                        rtpSequenceNumber = 101,
                        sendTimestamp = Instant.EPOCH + 100.ms,
                        receiveTimestamp = Instant.EPOCH + 120.ms
                    )

                    val packet2 = PacketTemplate(
                        transportSequenceNumber = 2,
                        rtpSequenceNumber = 102,
                        sendTimestamp = Instant.EPOCH + 110.ms,
                        receiveTimestamp = Instant.EPOCH + 130.ms
                    )

                    test.adapter.addPacket(
                        createPacketToSend(packet1),
                        packet1.transportSequenceNumber,
                        0.bytes,
                        timeNow()
                    )
                    test.adapter.processSentPacket(
                        SentPacketInfo(packet1.transportSequenceNumber, packet1.sendTimestamp)
                    )
                    test.adapter.addPacket(
                        createPacketToSend(packet2),
                        packet2.transportSequenceNumber,
                        0.bytes,
                        timeNow()
                    )
                    test.adapter.processSentPacket(
                        SentPacketInfo(packet2.transportSequenceNumber, packet2.sendTimestamp)
                    )

                    // Process the later feedback packet first (reordered)
                    val adaptedFeedback2 = test.createAndProcessFeedback(
                        listOf(packet2),
                        feedbackReceiveTime = Instant.EPOCH + 1000.ms
                    )
                    adaptedFeedback2!!.packetFeedbacks.size shouldBe 1

                    // Process the earlier feedback packet 10ms later due to reordering.
                    val adaptedFeedback1 = test.createAndProcessFeedback(
                        listOf(packet1),
                        feedbackReceiveTime = Instant.EPOCH + 1010.ms
                    )
                    adaptedFeedback1!!.packetFeedbacks.size shouldBe 1

                    val result2 = adaptedFeedback2.packetFeedbacks[0]
                    val result1 = adaptedFeedback1.packetFeedbacks[0]

                    // Even though processed out-of-order, the offset between send and receive
                    // time should be identical within 1ms (due to compact NTP fractional tick
                    // precision limits).
                    val diff2 = Duration.between(result2.sentPacket.sendTime, result2.receiveTime)
                    val diff1 = Duration.between(result1.sentPacket.sendTime, result1.receiveTime)
                    (diff2 - diff1).abs() shouldBeLessThanOrEqualTo 1.ms
                }
            }
        }

        // Jitsi local: WebRTC has no test of this.
        "CongestionControlFeedbackTimestampWraps" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            // The compact NTP timestamp of a report holds the seconds modulo 2^16. It wraps at this time.
            val kWrapTime = Instant.EPOCH + 33152.secs

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = kWrapTime - 50.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 200.ms,
                    receiveTimestamp = kWrapTime + 50.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val adaptedFeedback1 = test.adapter.processCongestionControlFeedback(
                buildRtcpCongestionControlFeedbackPacket(listOf(packets[0])),
                feedbackReceiveTime = Instant.EPOCH + 1000.ms
            )
            // The second report is delayed on the way back.
            val adaptedFeedback2 = test.adapter.processCongestionControlFeedback(
                buildRtcpCongestionControlFeedbackPacket(listOf(packets[1])),
                feedbackReceiveTime = Instant.EPOCH + 1150.ms
            )

            // The receive times follow the timestamps of the reports, not the times the reports arrived.
            val receiveTimeDelta = Duration.between(
                adaptedFeedback1!!.packetFeedbacks[0].receiveTime,
                adaptedFeedback2!!.packetFeedbacks[0].receiveTime
            )
            (receiveTimeDelta - 100.ms).abs() shouldBeLessThanOrEqualTo 1.ms
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
            adaptedFeedback.hasPacketWithBleachedEct1() shouldBe false
        }

        "CongestionControlFeedbackResultPassSentWithEct1PerPacket" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendAsEct1 = true,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendAsEct1 = false,
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
            adaptedFeedback.packetFeedbacks[0].sentWithEct1 shouldBe true
            adaptedFeedback.packetFeedbacks[1].sentWithEct1 shouldBe false
        }

        "ReportBleachedEct1IfEct1PacketIsReceivedAsNotEct" {
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

            adaptedFeedback!!.hasPacketWithBleachedEct1() shouldBe true
            adaptedFeedback.packetFeedbacks.size shouldBe 2
        }

        "DoesNotReportBleachedEct1IfNotEctPacketIsReceivedAsNotEct" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            // A packet that was sent as Not-ECT is expected to be received as Not-ECT.
            // That says nothing about whether the path bleaches ECT(1).
            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendAsEct1 = false,
                    ecn = EcnMarking.kNotEct,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            val adaptedFeedback = test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow())
            adaptedFeedback!!.packetFeedbacks.size shouldBe 1
            adaptedFeedback.hasPacketWithBleachedEct1() shouldBe false
        }

        "DoesNotReportBleachedEct1IfEct1PacketIsLost" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            // A lost packet is reported with a defaulted Not-ECT marking. Treating that
            // as bleaching would turn every burst of loss into a permanent verdict that
            // the path does not support ECN.
            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendAsEct1 = true,
                    ecn = EcnMarking.kEct1,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendAsEct1 = true,
                    sendTimestamp = Instant.EPOCH + 110.ms,
                    receiveTimestamp = NEVER
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            val adaptedFeedback = test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow())
            adaptedFeedback!!.packetFeedbacks.size shouldBe 2
            adaptedFeedback.packetFeedbacks[1].isReceived() shouldBe false
            adaptedFeedback.hasPacketWithBleachedEct1() shouldBe false
        }

        "CongestionControlFeedbackResultReportsLostPacketOnce" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 200.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 110.ms,
                    receiveTimestamp = NEVER
                ),
                PacketTemplate(
                    transportSequenceNumber = 3,
                    rtpSequenceNumber = 103,
                    sendTimestamp = Instant.EPOCH + 120.ms,
                    receiveTimestamp = Instant.EPOCH + 210.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            var packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow()),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false
            packetFeedback.reportedLostForTheFirstTime shouldBe true

            // Process the same report again.
            packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow()),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false
            packetFeedback.reportedLostForTheFirstTime shouldBe false
        }

        "CongestionControlFeedbackResultReportsImplicitlyLostPacketOnce" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    ssrc = 1,
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms
                ),
                PacketTemplate(
                    ssrc = 2,
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 201,
                    sendTimestamp = Instant.EPOCH + 110.ms
                ),
                PacketTemplate(
                    ssrc = 1,
                    transportSequenceNumber = 3,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 120.ms
                ),
                PacketTemplate(
                    ssrc = 2,
                    transportSequenceNumber = 4,
                    rtpSequenceNumber = 202,
                    sendTimestamp = Instant.EPOCH + 120.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            // Produce feedback where 2nd packet is lost.
            packets[0].receiveTimestamp = Instant.EPOCH + 200.ms
            packets[2].receiveTimestamp = Instant.EPOCH + 220.ms
            val feedback1 = listOf(packets[0], packets[2])
            var packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(
                    buildRtcpCongestionControlFeedbackPacket(feedback1),
                    timeNow()
                ),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false

            // Produce feedback where 2nd packet is reported lost and 4th packet is
            // received.
            packets[3].receiveTimestamp = Instant.EPOCH + 240.ms
            val feedback2 = listOf(packets[1], packets[3])
            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(feedback2)
            // 2nd packet is still lost.
            packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow()),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false
            packetFeedback.reportedLostForTheFirstTime shouldBe false
        }

        "CongestionControlFeedbackDoesNotReportUnsentPacketAsLost" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    ssrc = 1,
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms
                ),
                PacketTemplate(
                    ssrc = 2,
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 201,
                    sendTimestamp = Instant.EPOCH + 110.ms
                ),
                PacketTemplate(
                    ssrc = 1,
                    transportSequenceNumber = 3,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 120.ms
                ),
                PacketTemplate(
                    ssrc = 1,
                    transportSequenceNumber = 4,
                    rtpSequenceNumber = 103,
                    sendTimestamp = Instant.EPOCH + 130.ms
                ),
            )

            // The 2nd packet is tagged, but not indicated as sent.
            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                if (packet.transportSequenceNumber != 2L) {
                    test.adapter.processSentPacket(
                        SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp)
                    )
                }
            }

            // Feedback that does not mention the 2nd packet.
            packets[0].receiveTimestamp = Instant.EPOCH + 200.ms
            packets[2].receiveTimestamp = Instant.EPOCH + 220.ms
            val report1 = test.adapter.processCongestionControlFeedback(
                buildRtcpCongestionControlFeedbackPacket(listOf(packets[0], packets[2])),
                timeNow()
            )
            report1 shouldNotBe null
            report1!!.packetFeedbacks.size shouldBe 2
            findFeedback(report1, transportSequenceNumber = 2) shouldBe null

            // The 2nd packet is sent later, and a later feedback reports it lost and the 4th packet received. This is
            // the first report of the loss.
            test.adapter.processSentPacket(SentPacketInfo(2, packets[1].sendTimestamp))
            packets[3].receiveTimestamp = Instant.EPOCH + 240.ms
            val packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(
                    buildRtcpCongestionControlFeedbackPacket(listOf(packets[1], packets[3])),
                    timeNow()
                ),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false
            packetFeedback.reportedLostForTheFirstTime shouldBe true
        }

        "CongestionControlFeedbackResultReportsRecoveredPacketOnce" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 110.ms
                ),
                PacketTemplate(
                    transportSequenceNumber = 3,
                    rtpSequenceNumber = 103,
                    sendTimestamp = Instant.EPOCH + 120.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            // Produce feedback where 2nd packet is lost.
            packets[0].receiveTimestamp = Instant.EPOCH + 200.ms
            packets[2].receiveTimestamp = Instant.EPOCH + 220.ms
            var packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(
                    buildRtcpCongestionControlFeedbackPacket(packets),
                    timeNow()
                ),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe false

            // Produce feedback where 2nd packet is received.
            packets[1].receiveTimestamp = Instant.EPOCH + 240.ms
            val rtcpFeedback = buildRtcpCongestionControlFeedbackPacket(packets)
            packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow()),
                transportSequenceNumber = 2
            )
            packetFeedback shouldNotBe null
            packetFeedback!!.isReceived() shouldBe true
            packetFeedback.reportedRecoveredForTheFirstTime shouldBe true

            // Process the same report again.
            // Packet either shouldn't be reported again, or, if reported, shouldn't be
            // set as recovered again.
            packetFeedback = findFeedback(
                test.adapter.processCongestionControlFeedback(rtcpFeedback, timeNow()),
                transportSequenceNumber = 2
            )
            if (packetFeedback != null) {
                packetFeedback.isReceived() shouldBe true
                packetFeedback.reportedRecoveredForTheFirstTime shouldBe false
            }
        }

        "ClampsExcessiveArrivalTimeOffsetToMaxExpectedAto" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            val packets = listOf(
                PacketTemplate(
                    ssrc = 1234,
                    transportSequenceNumber = 1,
                    rtpSequenceNumber = 101,
                    sendTimestamp = Instant.EPOCH + 100.ms,
                    receiveTimestamp = Instant.EPOCH + 150.ms
                ),
                PacketTemplate(
                    ssrc = 1234,
                    transportSequenceNumber = 2,
                    rtpSequenceNumber = 102,
                    sendTimestamp = Instant.EPOCH + 120.ms,
                    receiveTimestamp = Instant.EPOCH + 180.ms
                ),
            )

            packets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            // First feedback at t = 200ms with report timestamp 150ms.
            val feedback1 = listOf(packets[0])
            test.adapter.processCongestionControlFeedback(
                buildRtcpCongestionControlFeedbackPacket(feedback1),
                Instant.EPOCH + 200.ms
            )

            // Second feedback at t = 230ms with report timestamp 180ms (feedback_delta =
            // 30ms). But reported arrival_time_offset is 400ms (exceeding
            // feedback_delta).
            val packetInfo = ReceivedPacketInfo(
                ssrc = 1234,
                sequenceNumber = 102,
                arrivalTimeOffset = 400.ms,
                ecn = EcnMarking.kNotEct
            )
            val compactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(180))
            val feedback2 =
                RtcpFbCcfbPacketBuilder(reportTimestampCompactNtp = compactNtp, packets = listOf(packetInfo)).build()

            val result = test.adapter.processCongestionControlFeedback(feedback2, Instant.EPOCH + 230.ms)
            result shouldNotBe null
            val packetFeedback = findFeedback(result, transportSequenceNumber = 2)
            packetFeedback shouldNotBe null
            packetFeedback!!.arrivalTimeOffset shouldNotBe null
            // The arrival_time_offset should be clamped to max expected ATO
            // (feedback_delta + 1ms = 31ms) rather than remaining 400ms.
            packetFeedback.arrivalTimeOffset!! shouldBeLessThanOrEqualTo 31.ms
            packetFeedback.ambiguousReceiveTime shouldBe false
        }

        "AmbiguousReceiveTimeForRetransmissionWithoutRtx" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            // Packet 1: Original audio packet
            val packet1 = PacketTemplate(
                ssrc = 1234,
                transportSequenceNumber = 1,
                rtpSequenceNumber = 101,
                sendTimestamp = Instant.EPOCH + 100.ms,
                receiveTimestamp = Instant.EPOCH + 120.ms,
                isAudio = true
            )
            test.adapter.addPacket(createPacketToSend(packet1), packet1.transportSequenceNumber, 0.bytes, timeNow())
            test.adapter.processSentPacket(SentPacketInfo(packet1.transportSequenceNumber, packet1.sendTimestamp))

            // Packet 2: Retransmission of packet 1 without RTX (same SSRC and sequence
            // number)
            val packet2 = packet1.copy(transportSequenceNumber = 2, sendTimestamp = Instant.EPOCH + 150.ms)
            val resendPacket = createPacketToSend(packet2)
            resendPacket.packetOrigin = PacketOrigin.Retransmission
            test.adapter.addPacket(resendPacket, packet2.transportSequenceNumber, 0.bytes, timeNow())
            test.adapter.processSentPacket(SentPacketInfo(packet2.transportSequenceNumber, packet2.sendTimestamp))

            // Feedback received for the RTP packet
            val packetInfo = ReceivedPacketInfo(
                ssrc = 1234,
                sequenceNumber = 101,
                arrivalTimeOffset = 10.ms,
                ecn = EcnMarking.kNotEct
            )
            val compactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(160))
            val feedback =
                RtcpFbCcfbPacketBuilder(reportTimestampCompactNtp = compactNtp, packets = listOf(packetInfo)).build()

            val result = test.adapter.processCongestionControlFeedback(feedback, Instant.EPOCH + 200.ms)
            result shouldNotBe null
            result!!.packetFeedbacks.size shouldBe 1
            result.packetFeedbacks[0].ambiguousReceiveTime shouldBe true
        }

        "AmbiguousReceiveTimeForRetransmissionWhenOriginalAlreadyAcked" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)
            val clock = FakeClock().apply { setTime(Instant.EPOCH + 100.ms) }

            // Packet 1: Original audio packet.
            val packet1 = PacketTemplate(
                ssrc = 1234,
                transportSequenceNumber = 1,
                rtpSequenceNumber = 101,
                sendTimestamp = clock.instant(),
                receiveTimestamp = clock.instant() + 20.ms,
                isAudio = true
            )
            test.adapter.addPacket(
                createPacketToSend(packet1),
                packet1.transportSequenceNumber,
                0.bytes,
                clock.instant()
            )
            test.adapter.processSentPacket(SentPacketInfo(packet1.transportSequenceNumber, packet1.sendTimestamp))

            // Feedback received for packet 1.
            clock.elapse(30.ms)
            val packetInfo1 = ReceivedPacketInfo(
                ssrc = 1234,
                sequenceNumber = 101,
                arrivalTimeOffset = 10.ms,
                ecn = EcnMarking.kNotEct
            )
            val compactNtp1 = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(clock.instant().toEpochMilli()))
            val feedback1 =
                RtcpFbCcfbPacketBuilder(reportTimestampCompactNtp = compactNtp1, packets = listOf(packetInfo1)).build()
            val result1 = test.adapter.processCongestionControlFeedback(feedback1, clock.instant())
            result1 shouldNotBe null
            result1!!.packetFeedbacks[0].ambiguousReceiveTime shouldBe false

            // 1ms later, a queued/spurious retransmission of packet 1 without RTX is
            // sent.
            clock.elapse(1.ms)
            val packet2 = packet1.copy(transportSequenceNumber = 2, sendTimestamp = clock.instant())
            val resendPacket = createPacketToSend(packet2)
            resendPacket.packetOrigin = PacketOrigin.Retransmission
            resendPacket.originalSsrc = 1234
            test.adapter.addPacket(resendPacket, packet2.transportSequenceNumber, 0.bytes, clock.instant())
            test.adapter.processSentPacket(SentPacketInfo(packet2.transportSequenceNumber, packet2.sendTimestamp))

            // Feedback received for the retransmission.
            clock.elapse(30.ms)
            val packetInfo2 = ReceivedPacketInfo(
                ssrc = 1234,
                sequenceNumber = 101,
                arrivalTimeOffset = 10.ms,
                ecn = EcnMarking.kNotEct
            )
            val compactNtp2 = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(clock.instant().toEpochMilli()))
            val feedback2 =
                RtcpFbCcfbPacketBuilder(reportTimestampCompactNtp = compactNtp2, packets = listOf(packetInfo2)).build()
            val result2 = test.adapter.processCongestionControlFeedback(feedback2, clock.instant())
            result2 shouldNotBe null
            result2!!.packetFeedbacks.size shouldBe 1
            result2.packetFeedbacks[0].ambiguousReceiveTime shouldBe true
        }

        "VideoRtxIsNotAmbiguous" {
            val test = OneTransportFeedbackAdapterTest(feedbackType = FeedbackType.Ccfb)

            // Video packet
            val packet1 = PacketTemplate(
                ssrc = 1234,
                transportSequenceNumber = 1,
                rtpSequenceNumber = 101,
                sendTimestamp = Instant.EPOCH + 100.ms,
                receiveTimestamp = Instant.EPOCH + 120.ms,
                isAudio = false
            )
            test.adapter.addPacket(createPacketToSend(packet1), packet1.transportSequenceNumber, 0.bytes, timeNow())
            test.adapter.processSentPacket(SentPacketInfo(packet1.transportSequenceNumber, packet1.sendTimestamp))

            // RTX packet sent on RTX SSRC 5678 with sequence number 501
            val rtxTemplate = PacketTemplate(
                ssrc = 5678,
                transportSequenceNumber = 2,
                rtpSequenceNumber = 501,
                sendTimestamp = Instant.EPOCH + 150.ms,
                receiveTimestamp = Instant.EPOCH + 170.ms,
                isAudio = false
            )
            val rtxPacket = createPacketToSend(rtxTemplate)
            rtxPacket.packetOrigin = PacketOrigin.Retransmission
            rtxPacket.originalSsrc = 1234
            test.adapter.addPacket(rtxPacket, rtxTemplate.transportSequenceNumber, 0.bytes, timeNow())
            test.adapter.processSentPacket(
                SentPacketInfo(rtxTemplate.transportSequenceNumber, rtxTemplate.sendTimestamp)
            )

            // Feedback received for RTX packet on RTX SSRC
            val rtxFeedbackInfo = ReceivedPacketInfo(
                ssrc = 5678,
                sequenceNumber = 501,
                arrivalTimeOffset = 10.ms,
                ecn = EcnMarking.kNotEct
            )
            val compactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(180))
            val feedback = RtcpFbCcfbPacketBuilder(
                reportTimestampCompactNtp = compactNtp,
                packets = listOf(rtxFeedbackInfo)
            ).build()

            val result = test.adapter.processCongestionControlFeedback(feedback, Instant.EPOCH + 200.ms)
            result shouldNotBe null
            result!!.packetFeedbacks.size shouldBe 1
            result.packetFeedbacks[0].ambiguousReceiveTime shouldBe false
        }

        // Jitsi local
        "CongestionControlFeedbackForStreamsWhichWereNotAddedIsIgnored" {
            val test = OneTransportFeedbackAdapterTest(FeedbackType.Ccfb)
            // The peer reports on three streams, but only the packets of the first were added (the others might
            // be audio, which is not congestion controlled).
            val reportedPackets = createPacketTemplates(numberOfSsrcs = 3, packetsPerSsrc = 3)
            val addedPackets = reportedPackets.filter { it.ssrc == reportedPackets[0].ssrc }
            addedPackets.size shouldBe 3

            addedPackets.forEach { packet ->
                test.adapter.addPacket(createPacketToSend(packet), packet.transportSequenceNumber, 0.bytes, timeNow())
                test.adapter.processSentPacket(SentPacketInfo(packet.transportSequenceNumber, packet.sendTimestamp))
            }

            val adaptedFeedback = test.createAndProcessFeedback(reportedPackets)
            comparePacketFeedbackVectors(addedPackets, adaptedFeedback!!.packetFeedbacks)
            test.adapter.totalUnmatchedReports shouldBe 0

            // Feedback which is only about the other streams produces nothing.
            test.createAndProcessFeedback(reportedPackets - addedPackets.toSet()) shouldBe null
            test.adapter.totalUnmatchedReports shouldBe 0

            // A report on a packet of the added stream which is not in the history is still counted.
            val unknownPacket = addedPackets.last().let { it.copy(rtpSequenceNumber = it.rtpSequenceNumber + 1) }
            test.createAndProcessFeedback(listOf(unknownPacket)) shouldBe null
            test.adapter.totalUnmatchedReports shouldBe 1
        }
    }
}
