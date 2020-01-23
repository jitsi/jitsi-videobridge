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
package org.jitsi.nlj.rtp

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.ArrayCache
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.utils.joinToRangedString
import org.jitsi.utils.logging2.Logger
import org.json.simple.JSONObject

/**
 * Implements transport-cc functionality.
 *
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 */
class TransportCcEngine(
    private val bandwidthEstimator: BandwidthEstimator,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : RtcpListener {

    /**
     * The [Logger] used by this instance for logging output.
     */
    private val logger: Logger = parentLogger.createChildLogger(javaClass.name)

    val numDuplicateReports = AtomicInteger()
    val numPacketsReportedAfterLost = AtomicInteger()
    val numPacketsUnreported = AtomicInteger()
    val numMissingPacketReports = AtomicInteger()

    /**
     * The reference time of the remote clock. This is used to rebase the
     * arrival times in the TCC packets to a meaningful time base (that of the
     * sender). This is technically not necessary and it's done for convenience.
     */
    private var remoteReferenceTime: Instant = NEVER

    /**
     * Local time to map to the reference time of the remote clock. This is used
     * to rebase the arrival times in the TCC packets to a meaningful time base
     * (that of the sender). This is technically not necessary and it's done for
     * convenience.
     */
    private var localReferenceTime: Instant = NEVER

    /**
     * Holds a key value pair of the packet sequence number and an object made
     * up of the packet send time and the packet size.
     */
    private val sentPacketDetails = PacketDetailTracker(clock)

    private val missingPacketDetailSeqNums = mutableListOf<Int>()

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    fun onRttUpdate(rtt: Duration) {
        val now = clock.instant()
        bandwidthEstimator.onRttUpdate(now, rtt)
    }

    override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Long) {
        if (rtcpPacket is RtcpFbTccPacket) {
            tccReceived(rtcpPacket)
        }
    }

    private fun tccReceived(tccPacket: RtcpFbTccPacket) {
        val now = clock.instant()
        var currArrivalTimestamp = Instant.ofEpochMilli(tccPacket.GetBaseTimeUs() / 1000)
        if (remoteReferenceTime == NEVER) {
            remoteReferenceTime = currArrivalTimestamp
            localReferenceTime = now
        }

        for (packetReport in tccPacket) {
            val tccSeqNum = packetReport.seqNum
            val packetDetail = sentPacketDetails.get(tccSeqNum)

            if (packetDetail == null) {
                if (packetReport is ReceivedPacketReport) {
                    currArrivalTimestamp += packetReport.deltaDuration
                    missingPacketDetailSeqNums.add(tccSeqNum)
                    numMissingPacketReports.getAndIncrement()
                }
                continue
            }

            when (packetReport) {
                is UnreceivedPacketReport -> {
                    if (packetDetail.state == PacketDetailState.unreported) {
                        bandwidthEstimator.processPacketLoss(now, packetDetail.packetSendTime, tccSeqNum)
                        packetDetail.state = PacketDetailState.reportedLost
                    }
                }
                is ReceivedPacketReport -> {
                    currArrivalTimestamp += packetReport.deltaDuration

                    when (packetDetail.state) {
                        PacketDetailState.unreported, PacketDetailState.reportedLost -> {
                            if (packetDetail.state == PacketDetailState.reportedLost) {
                                numPacketsReportedAfterLost.getAndIncrement()
                            }

                            val arrivalTimeInLocalClock = currArrivalTimestamp - Duration.between(localReferenceTime, remoteReferenceTime)

                            /* TODO: BandwidthEstimator should have an API for "previously reported lost packet has arrived"
                             * for the reportedLost case. */
                            bandwidthEstimator.processPacketArrival(
                                now, packetDetail.packetSendTime, arrivalTimeInLocalClock, tccSeqNum, packetDetail.packetLength)
                            packetDetail.state = PacketDetailState.reportedReceived
                        }

                        PacketDetailState.reportedReceived ->
                            numDuplicateReports.getAndIncrement()
                    }
                }
            }
        }
        if (missingPacketDetailSeqNums.isNotEmpty()) {
            logger.warn("TCC packet contained received sequence numbers: " +
                "${tccPacket.iterator().asSequence()
                    .filterIsInstance<ReceivedPacketReport>()
                    .map(PacketReport::seqNum)
                    .joinToRangedString()}. " +
                "Couldn't find packet detail for the seq nums: ${missingPacketDetailSeqNums.joinToRangedString()}. " +
                if (sentPacketDetails.empty) {
                    "Sent packet details map was empty."
                } else {
                    "Latest seqNum was ${sentPacketDetails.lastSequence}, size is ${sentPacketDetails.size}."
                })
            missingPacketDetailSeqNums.clear()
        }
    }

    fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        val now = clock.instant()
        val seq = tccSeqNum and 0xFFFF
        if (!sentPacketDetails.insert(seq, PacketDetail(length, now))) {
            /* Very old seq? Something odd is happening with whatever is
             * generating tccSeqNum values.
             */
            logger.warn("Not inserting very old TCC seq num $seq ($tccSeqNum), latest is " +
                "${sentPacketDetails.lastSequence}, size is ${sentPacketDetails.size}")
            return
        }
    }

    fun getStatistics(): StatisticsSnapshot {
        return StatisticsSnapshot(numDuplicateReports.get(),
            numPacketsReportedAfterLost.get(),
            numPacketsUnreported.get(),
            numMissingPacketReports.get()
        )
    }

    /**
     * [PacketDetailState] is the state of a [PacketDetail]
     */
    private enum class PacketDetailState {
        unreported, reportedLost, reportedReceived
    }

    /**
     * [PacketDetail] is an object that holds the
     * length(size) of the packet in [packetLength]
     * and the time stamps of the outgoing packet
     * in [packetSendTime]
     */
    private data class PacketDetail internal constructor(
        internal val packetLength: DataSize,
        internal val packetSendTime: Instant
    ) {
        /**
         * [state] represents the state of this packet detail with regards to the
         * reception of a TCC feedback from the remote side.  [PacketDetail]s start out
         * as [unreported]: once we receive a TCC feedback from the remote side referring
         * to this packet, the state will transition to either [reportedLost] or [reportedReceived].
         */
        var state = PacketDetailState.unreported
    }

    data class StatisticsSnapshot(
        val numDuplicateReports: Int,
        val numPacketsReportedAfterLost: Int,
        val numPacketsUnreported: Int,
        val numMissingPacketReports: Int
    ) {
        fun toJson(): JSONObject {
            return JSONObject().also {
                it.put("numDuplicateReports", numDuplicateReports)
                it.put("numPacketsReportedAfterLost", numPacketsReportedAfterLost)
                it.put("numPacketsUnreported", numPacketsUnreported)
                it.put("numMissingPacketReports", numMissingPacketReports)
            }
        }
    }

    private inner class PacketDetailTracker(clock: Clock) : ArrayCache<PacketDetail>(
        MAX_OUTGOING_PACKETS_HISTORY,
        /* We don't want to clone [PacketDetail] objects that get put in the tracker. */
        { it },
        clock = clock
    ) {
        override fun discardItem(item: PacketDetail) {
            numPacketsUnreported.getAndIncrement()
        }

        private val rfc3711IndexTracker = Rfc3711IndexTracker()

        /**
         * Gets a packet with a given RTP sequence number from the cache.
         */
        fun get(sequenceNumber: Int): PacketDetail? {
            // Note that we use [interpret] because we don't want the ROC to get out of sync because of funny requests
            // (TCCs)
            val index = rfc3711IndexTracker.interpret(sequenceNumber)
            return super.getContainer(index)?.item
        }

        fun insert(seq: Int, packetDetail: PacketDetail): Boolean {
            val index = rfc3711IndexTracker.update(seq)
            return super.insertItem(packetDetail, index, packetDetail.packetSendTime.toEpochMilli())
        }

        val lastSequence: Int
            get() = if (lastIndex == -1) -1 else lastIndex and 0xFFFF
    }

    companion object {
        /**
         * The maximum number of received packets and their timestamps to save.
         *
         * NOTE rtt + minimum amount
         * XXX this is an uninformed value.
         */
        private const val MAX_OUTGOING_PACKETS_HISTORY = 1000
    }
}
