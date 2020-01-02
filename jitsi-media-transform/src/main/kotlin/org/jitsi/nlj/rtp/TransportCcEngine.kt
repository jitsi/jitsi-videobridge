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
import java.util.concurrent.ConcurrentSkipListMap
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.RtpUtils.Companion.getSequenceNumberDelta
import org.jitsi.rtp.util.RtpUtils.Companion.isOlderSequenceNumberThan
import org.jitsi.utils.logging2.Logger
import org.json.simple.JSONObject
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Used to synchronize access to [.sentPacketDetails].
     */
    private val sentPacketsSyncRoot = Any()

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
    private val sentPacketDetails = ConcurrentSkipListMap<Int, PacketDetail>(::getSequenceNumberDelta)

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
            val packetDetail = synchronized(sentPacketsSyncRoot) {
                sentPacketDetails.get(tccSeqNum)
            }

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
            val oldestKnownSeqNum = synchronized(sentPacketsSyncRoot) { sentPacketDetails.firstKey() }

            logger.warn("TCC packet contained received sequence numbers: " +
                "${tccPacket.iterator().asSequence()
                    .filterIsInstance<ReceivedPacketReport>()
                    .map(PacketReport::seqNum)
                    .joinToString()}. " +
                "Couldn't find packet detail for the seq nums: ${missingPacketDetailSeqNums.joinToString()}. " +
                (oldestKnownSeqNum?.let { "Oldest known seqNum was $it." } ?: run { "Sent packet details map was empty." }))
            missingPacketDetailSeqNums.clear()
        }
    }

    /** Trim old sent packet details from the map.  Caller should be synchronized on [sentPacketsSyncRoot].
     * Return false if [newSeq] is too old to be put in the map (shouldn't happen for TCC unless something
     * very weird has happened with threading).*/
    private fun cleanupSentPacketDetails(newSeq: Int): Boolean {
        if (sentPacketDetails.isEmpty()) {
            return true
        }

        val latestSeq = sentPacketDetails.lastKey()

        /* If our sequence numbers have jumped by a quarter of the number space, reset the map. */
        val seqJump = getSequenceNumberDelta(newSeq, latestSeq)
        if (seqJump >= 0x4000 || seqJump <= -0x4000) {
            sentPacketDetails.clear()
            return true
        }

        val threshold = applySequenceNumberDelta(latestSeq, -MAX_OUTGOING_PACKETS_HISTORY)

        if (isOlderSequenceNumberThan(newSeq, threshold)) {
            return false
        }

        with(sentPacketDetails.navigableKeySet().iterator()) {
            while (hasNext()) {
                val key = next()
                if (isOlderSequenceNumberThan(key, threshold)) {
                    if (sentPacketDetails.get(key)?.state == PacketDetailState.unreported) {
                        numPacketsUnreported.getAndIncrement()
                    }
                    remove()
                } else {
                    break
                }
            }
        }

        return true
    }

    fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        synchronized(sentPacketsSyncRoot) {
            val now = clock.instant()
            val seq = tccSeqNum and 0xFFFF
            if (!cleanupSentPacketDetails(seq)) {
                /* Very old seq? Something odd is happening with whatever is
                 * generating tccSeqNum values.
                 */
                logger.warn("Not inserting very old TCC seq num $seq ($tccSeqNum), latest is " +
                    "${sentPacketDetails.lastKey()}")
                return
            }
            sentPacketDetails.put(seq, PacketDetail(length, now))
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

    companion object {
        /**
         * The maximum number of received packets and their timestamps to save.
         *
         * XXX this is an uninformed value.
         */
        private const val MAX_OUTGOING_PACKETS_HISTORY = 1000
    }
}
