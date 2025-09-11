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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.ArrayCache
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.utils.NEVER
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.formatMilli
import org.jitsi.utils.joinToRangedString
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.LongAdder

/**
 * Implements transport-cc functionality.
 *
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 */
class ClassicTransportCcEngine(
    private val bandwidthEstimator: BandwidthEstimator,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : TransportCcEngine() {

    /**
     * The [Logger] used by this instance for logging output.
     */
    private val logger: Logger = createChildLogger(parentLogger)

    val numPacketsReported = LongAdder()
    val numPacketsReportedLost = LongAdder()
    val numDuplicateReports = LongAdder()
    val numPacketsReportedAfterLost = LongAdder()
    val numPacketsUnreported = LongAdder()
    val numMissingPacketReports = LongAdder()

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

    private var lastRtt: Duration? = null

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    override fun onRttUpdate(rtt: Duration) {
        val now = clock.instant()
        bandwidthEstimator.onRttUpdate(now, rtt)
        lastRtt = rtt
    }

    override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Instant?) {
        if (rtcpPacket is RtcpFbTccPacket) {
            tccReceived(rtcpPacket)
        }
    }

    private fun tccReceived(tccPacket: RtcpFbTccPacket) {
        val now = clock.instant()
        var currArrivalTimestamp = tccPacket.BaseTime()
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
                    numMissingPacketReports.increment()
                }
                continue
            }

            when (packetReport) {
                is UnreceivedPacketReport -> {
                    if (packetDetail.state == PacketDetailState.Unreported) {
                        bandwidthEstimator.processPacketLoss(now, packetDetail.packetSendTime, tccSeqNum)
                        packetDetail.state = PacketDetailState.ReportedLost
                        numPacketsReported.increment()
                        numPacketsReportedLost.increment()
                        synchronized(this) {
                            lossListeners.forEach {
                                it.packetLost(1)
                            }
                        }
                    }
                }
                is ReceivedPacketReport -> {
                    currArrivalTimestamp += packetReport.deltaDuration

                    when (packetDetail.state) {
                        PacketDetailState.Unreported, PacketDetailState.ReportedLost -> {
                            val previouslyReportedLost = packetDetail.state == PacketDetailState.ReportedLost
                            if (previouslyReportedLost) {
                                numPacketsReportedAfterLost.increment()
                                numPacketsReportedLost.decrement()
                                /* Packet has already been counted in numPacketsReported */
                            } else {
                                numPacketsReported.increment()
                            }

                            val arrivalTimeInLocalClock =
                                currArrivalTimestamp - Duration.between(localReferenceTime, remoteReferenceTime)

                            bandwidthEstimator.processPacketArrival(
                                now,
                                packetDetail.packetSendTime,
                                arrivalTimeInLocalClock,
                                tccSeqNum,
                                packetDetail.packetLength,
                                previouslyReportedLost = previouslyReportedLost
                            )
                            synchronized(this) {
                                lossListeners.forEach {
                                    it.packetReceived(previouslyReportedLost)
                                }
                            }
                            packetDetail.state = PacketDetailState.ReportedReceived
                        }

                        PacketDetailState.ReportedReceived ->
                            numDuplicateReports.increment()
                    }
                }
            }
        }
        bandwidthEstimator.feedbackComplete(now)

        if (missingPacketDetailSeqNums.isNotEmpty()) {
            logger.warn(
                "TCC packet contained received sequence numbers: " +
                    "${tccPacket.iterator().asSequence()
                        .filterIsInstance<ReceivedPacketReport>()
                        .map(PacketReport::seqNum)
                        .joinToRangedString()}. " +
                    "Couldn't find packet detail for the seq nums: " +
                    "${missingPacketDetailSeqNums.joinToRangedString()}. " +
                    if (sentPacketDetails.empty) {
                        "Sent packet details map was empty."
                    } else {
                        "Latest seqNum was ${sentPacketDetails.lastSequence}, size is ${sentPacketDetails.size}."
                    } +
                    (
                        lastRtt?.let {
                            " Latest RTT is ${it.formatMilli()} ms."
                        } ?: run {
                            ""
                        }
                        )
            )
            missingPacketDetailSeqNums.clear()
        }
    }

    override fun mediaPacketTagged(packetInfo: PacketInfo, tccSeqNum: Long) {
        /* Nothing needs to be done */
    }

    override fun mediaPacketSent(packetInfo: PacketInfo, tccSeqNum: Long) {
        val now = clock.instant()
        val seq = (tccSeqNum and 0xFFFF).toInt()
        if (!sentPacketDetails.insert(seq, PacketDetail(packetInfo.packet.length.bytes, now))) {
            /* Very old seq? Something odd is happening with whatever is
             * generating tccSeqNum values.
             */
            logger.warn(
                "Not inserting very old TCC seq num $seq ($tccSeqNum), latest is " +
                    "${sentPacketDetails.lastSequence}, size is ${sentPacketDetails.size}"
            )
            return
        }
    }

    override fun getStatistics(): StatisticsSnapshot {
        return StatisticsSnapshot(
            numPacketsReported.sum(),
            numPacketsReportedLost.sum(),
            numDuplicateReports.sum(),
            numPacketsReportedAfterLost.sum(),
            numPacketsUnreported.sum(),
            numMissingPacketReports.sum(),
            bandwidthEstimator.getStats()
        )
    }

    override fun addBandwidthListener(listener: BandwidthListener) = bandwidthEstimator.addListener(listener)

    override fun removeBandwidthListener(listener: BandwidthListener) = bandwidthEstimator.removeListener(listener)

    override fun start() { }

    override fun stop() { }

    /**
     * [PacketDetailState] is the state of a [PacketDetail]
     */
    private enum class PacketDetailState {
        Unreported,
        ReportedLost,
        ReportedReceived
    }

    /**
     * [PacketDetail] is an object that holds the
     * length(size) of the packet in [packetLength]
     * and the time stamps of the outgoing packet
     * in [packetSendTime]
     */
    @ConsistentCopyVisibility
    private data class PacketDetail internal constructor(
        val packetLength: DataSize,
        val packetSendTime: Instant
    ) {
        /**
         * [state] represents the state of this packet detail with regards to the
         * reception of a TCC feedback from the remote side.  [PacketDetail]s start out
         * as [unreported]: once we receive a TCC feedback from the remote side referring
         * to this packet, the state will transition to either [reportedLost] or [reportedReceived].
         */
        var state = PacketDetailState.Unreported
    }

    class StatisticsSnapshot(
        val numPacketsReported: Long,
        val numPacketsReportedLost: Long,
        val numDuplicateReports: Long,
        val numPacketsReportedAfterLost: Long,
        val numPacketsUnreported: Long,
        val numMissingPacketReports: Long,
        val bandwidthEstimatorStats: BandwidthEstimator.StatisticsSnapshot
    ) : TransportCcEngine.StatisticsSnapshot() {
        override fun toJson(): Map<*, *> {
            return OrderedJsonObject().also {
                it.put("name", ClassicTransportCcEngine::class.java.simpleName)
                it.put("numPacketsReported", numPacketsReported)
                it.put("numPacketsReportedLost", numPacketsReportedLost)
                it.put("numDuplicateReports", numDuplicateReports)
                it.put("numPacketsReportedAfterLost", numPacketsReportedAfterLost)
                it.put("numPacketsUnreported", numPacketsUnreported)
                it.put("numMissingPacketReports", numMissingPacketReports)
                it.put("bandwidth_estimator_stats", bandwidthEstimatorStats.toJson())
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
            if (item.state == PacketDetailState.Unreported) {
                numPacketsUnreported.increment()
            }
        }

        private val rtpSequenceIndexTracker = RtpSequenceIndexTracker()

        /**
         * Gets a packet with a given RTP sequence number from the cache.
         */
        fun get(sequenceNumber: Int): PacketDetail? {
            // Note that we use [interpret] because we don't want the ROC to get out of sync because of funny requests
            // (TCCs)
            val index = rtpSequenceIndexTracker.interpret(sequenceNumber)
            return super.getContainer(index, shouldCloneItem = true)?.item
        }

        fun insert(seq: Int, packetDetail: PacketDetail): Boolean {
            val index = rtpSequenceIndexTracker.update(seq)
            return super.insertItem(packetDetail, index, packetDetail.packetSendTime.toEpochMilli())
        }

        val lastSequence: Int
            get() = if (lastIndex == -1L) -1 else (lastIndex and 0xFFFF).toInt()
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
