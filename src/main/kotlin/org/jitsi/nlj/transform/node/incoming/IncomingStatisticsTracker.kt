/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.utils.stats.RateStatistics
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.RtpUtils.Companion.convertRtpTimestampToMs
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.isNewerThan
import org.jitsi.nlj.util.isNextAfter
import org.jitsi.nlj.util.numPacketsTo
import org.jitsi.nlj.util.rolledOverTo
import org.jitsi.rtp.rtp.RtpPacket
import toUInt
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Track various statistics about received RTP streams to be used in SR/RR report blocks
 */
class IncomingStatisticsTracker : ObserverNode("Incoming statistics tracker") {
    private val ssrcStats: MutableMap<Long, IncomingSsrcStats> = ConcurrentHashMap()
    private val payloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()

    /**
     * The bitrate in bits per seconds (for all RTP streams).
     */
    private val bitrate = RateStatistics(Duration.ofSeconds(1).toMillis().toUInt())
    /**
     * The packet rate in packets per second (for all RTP streams).
     */
    private val packetRate = RateStatistics(Duration.ofSeconds(1).toMillis().toUInt(), 1000f)

    /**
     * Total number of bytes received in RTP packets.
     */
    private var bytesReceived: Long = 0

    /**
     * Total number of RTP packets received.
     */
    private var packetsReceived: Long = 0

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        payloadTypes[rtpPacket.payloadType.toByte()]?.let {
            val stats = ssrcStats.computeIfAbsent(rtpPacket.ssrc) {
                IncomingSsrcStats(rtpPacket.ssrc, rtpPacket.sequenceNumber)
            }
            val packetSentTimestamp = convertRtpTimestampToMs(rtpPacket.timestamp.toUInt(), it.clockRate)
            stats.packetReceived(rtpPacket, packetSentTimestamp, packetInfo.receivedTime)
        }

        val now = System.currentTimeMillis()
        val bytes = rtpPacket.length
        bitrate.update(bytes, now)
        packetRate.update(1, now)
        bytesReceived += bytes
        packetsReceived++
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                // We don't want to track jitter, etc. for RTX streams
                if (event.payloadType is RtxPayloadType) {
                    logger.cdebug { "Ignoring RTX format: ${event.payloadType}" }
                } else {
                    payloadTypes[event.payloadType.pt] = event.payloadType
                }
            }
            is RtpPayloadTypeClearEvent -> payloadTypes.clear()
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            val stats = getSnapshot()
            stats.ssrcStats.forEach { ssrc, streamStats ->
                addString("source_ssrc", ssrc.toString())
                addString("stream_stats", streamStats.toString())
            }
        }
    }

    fun getSnapshot(): IncomingStatisticsSnapshot {
        val now = System.currentTimeMillis()
        return IncomingStatisticsSnapshot(
            bitrate.getRate(now),
            packetRate.getRate(now),
            bytesReceived,
            packetsReceived,
            ssrcStats.map { (ssrc, stats) ->
                Pair(ssrc, stats.getSnapshot())
            }.toMap()
        )
    }
}

class IncomingStatisticsSnapshot(
    /**
     * Bitrate in bits per second.
     */
    val bitrate: Long,
    /**
     * Packet rate in packets per second.
     */
    val packetRate: Long,
    /**
     * Total number of bytes received in RTP packets.
     */
    val bytesReceived: Long,

    /**
     * Total number of RTP packets received.
     */
    val packetsReceived: Long,
    /**
     * Per-ssrc stats.
     */
    val ssrcStats: Map<Long, IncomingSsrcStats.Snapshot>
)

/**
 * Tracks various statistics for the stream using ssrc [ssrc].  Some statistics are tracked only
 * over a specific interval (in between calls to [reset]) and others persist across calls to [reset].  This
 * is tuned for use in generating an RR packet.
 * TODO: max dropout/max misorder/probation handling according to appendix A.1
 */
class IncomingSsrcStats(
    private val ssrc: Long,
    private var baseSeqNum: Int
) {
    // TODO: for now we'll synchronize access to all the stats so we can create a consistent snapshot when it's
    // requested from another context.  it'd be great to be able to avoid this (coroutines make it easy to switch
    // contexts and could be a nice option here in the future).  another option would be doing the RR generation
    // in the same context as where we receive packets: this would work fine as long as that sort of interval is
    // acceptable for RRs?
    private var statsLock = Any()
    // Start variables protected by statsLock
    /**
     * This will be initialized to the first sequence number we process
     */
    private var maxSeqNum: Int = baseSeqNum
    private var seqNumCycles: Int = 0
    private val numExpectedPackets: Int
        get() = calculateExpectedPacketCount(0, baseSeqNum, seqNumCycles, maxSeqNum)
    private var cumulativePacketsLost: Int = 0
    private var outOfOrderPacketCount: Int = 0
    private var jitter: Double = 0.0
    private var numReceivedPackets: Int = 0
    // End variables protected by statsLock

    /**
     * The timestamp of the previously received packet, converted to a millisecond timestamp based on the received
     * RTP timestamp and the clock rate for that stream.
     * 'previously received packet' here is as defined by the order in which the packets were received by this code,
     * which may be different than the order according to sequence number.
     */
    private var previousPacketSentTimestamp: Long = -1
    private var previousPacketReceivedTimestamp: Long = -1
    private var probation: Int = INITIAL_MIN_SEQUENTIAL

    companion object {
        /**
         * The maximum 'out-of-order' amount, meaning a packet whose sequence number
         * difference from the current highest sequence number larger than this amount
         * will not be treated as a received out-of-order packet (and therefore subtract
         * from the cumulative loss amount)
         */
        const val MAX_OOO_AMOUNT = 100
        /**
         * https://tools.ietf.org/html/rfc3550#appendix-A.1
         * "...a source is declared valid only after MIN_SEQUENTIAL packets have been received in
         * sequence."
         */
        const val INITIAL_MIN_SEQUENTIAL = 2

        const val MAX_DROPOUT = 3000

        /**
         * Find how many packets we would expected to have received in the range [[baseSeqNum], [currSeqNum]], taking
         * into account the amount of cycles present when we received [baseSeqNum] ([baseSeqNumCycles]) and the
         * amount of cycles when we received [currSeqNum] ([currCycles])
         */
        fun calculateExpectedPacketCount(
            baseSeqNumCycles: Int,
            baseSeqNum: Int,
            currCycles: Int,
            currSeqNum: Int
        ): Int {
            val baseExtended = (baseSeqNumCycles shl 16) + baseSeqNum
            val maxExtended = (currCycles shl 16) + currSeqNum

            return maxExtended - baseExtended + 1
        }

        fun calculateJitter(
            currentJitter: Double,
            previousPacketSentTimestamp: Long,
            previousPacketReceivedTimestamp: Long,
            currentPacketSentTimestamp: Long,
            currentPacketReceivedTimestamp: Long
        ): Double {
            /**
             * If Si is the RTP timestamp from packet i, and Ri is the time of
             * arrival in RTP timestamp units for packet i, then for two packets
             * i and j, D may be expressed as
             *
             * D(i,j) = (Rj - Ri) - (Sj - Si) = (Rj - Sj) - (Ri - Si)
             */
            // TODO(boris) take wraps into account
            val delta = (previousPacketReceivedTimestamp - previousPacketSentTimestamp) -
                    (currentPacketReceivedTimestamp - currentPacketSentTimestamp)

            /**
             * The interarrival jitter SHOULD be calculated continuously as each
             * data packet i is received from source SSRC_n, using this
             * difference D for that packet and the previous packet i-1 in order
             * of arrival (not necessarily in sequence), according to the formula
             *
             * J(i) = J(i-1) + (|D(i-1,i)| - J(i-1))/16
             *
             * Whenever a reception report is issued, the current value of J is
             * sampled.
             */
            return currentJitter + (Math.abs(delta) - currentJitter) / 16.0
        }
    }

    fun getSnapshot(): Snapshot {
        synchronized(statsLock) {
            return Snapshot(numReceivedPackets, maxSeqNum, seqNumCycles, numExpectedPackets,
                    cumulativePacketsLost, jitter)
        }
    }

    /**
     * Resets this [IncomingSsrcStats]'s tracking variables such that:
     * 1) A new base sequence number (the given [newBaseSeqNum]) will be used to start new loss calculations.
     * 2) Any lost packet counters will be reset
     * 3) Jitter calculations will NOT be reset
     */
//    fun reset(newBaseSeqNum: Int) {
//        baseSeqNum = newBaseSeqNum
//        maxSeqNum = baseSeqNum - 1
//        cumulativePacketsLost = 0
//    }

    /**
     * Notify this [IncomingSsrcStats] instance that an RTP packet [packet] for the stream it is
     * tracking has been received and that it: was sent at [packetSentTimestampMs] (note this is NOT the
     * raw RTP timestamp, but the 'translated' timestamp which is a function of the RTP timestamp and the clockrate)
     * and was received at [packetReceivedTimeMs]
     */
    fun packetReceived(
        packet: RtpPacket,
        packetSentTimestampMs: Long,
        packetReceivedTimeMs: Long
    ) {
        val packetSequenceNumber = packet.sequenceNumber
        synchronized(statsLock) {
            numReceivedPackets++
            if (packetSequenceNumber isNewerThan maxSeqNum) {
                if (packetSequenceNumber isNextAfter maxSeqNum) {
                    if (probation > 0) {
                        probation--
                        // TODO: do we want to 'reset' the sequence after the probation period is finished?
                    }
                } else if (maxSeqNum numPacketsTo packetSequenceNumber in 0..MAX_DROPOUT) {
                    // In order with a gap, but gap is within acceptable range
                    cumulativePacketsLost += maxSeqNum numPacketsTo packetSequenceNumber
                    maybeResetProbation()
                } else {
                    // Very large jump
                    // TODO
                }
                if (maxSeqNum rolledOverTo packetSequenceNumber) {
                    seqNumCycles++
                }
                maxSeqNum = packetSequenceNumber
            } else {
                // Older packet
                if (packetSequenceNumber numPacketsTo maxSeqNum in 0..MAX_OOO_AMOUNT) {
                    // This packet would've already been counted as lost, so subtract from the loss count
                    // NOTE: this is susceptible to inaccuracy in the case of duplicate, out-of-order packets
                    // but for now we'll assume those are rare enough not to cause issues.
                    cumulativePacketsLost--
                } else {
                    // Older packet which is too old to be counted as out-of-order
                    // TODO
                }
                outOfOrderPacketCount++
                maybeResetProbation()
            }

            jitter = calculateJitter(
                jitter,
                previousPacketSentTimestamp,
                previousPacketReceivedTimestamp,
                packetSentTimestampMs,
                packetReceivedTimeMs
            )
            previousPacketSentTimestamp = packetSentTimestampMs
            previousPacketReceivedTimestamp = packetReceivedTimeMs
        }
    }

    /**
     * Reset the probation period if it's currently active
     */
    private fun maybeResetProbation() {
        if (probation > 0) {
            // When we reset, we subtract 1 since we've already started receiving packets so we already have
            // a 'starting point' for our sequential check (unlike when this instance is initialized, when we
            // haven't processing any incoming packets yet)
            probation = INITIAL_MIN_SEQUENTIAL - 1
        }
    }

    /**
     * A class to export a consistent snapshot of the data held inside [IncomingSsrcStats]
     * TODO: these really need to be documented!
     */
    data class Snapshot(
        val numReceivedPackets: Int = 0,
        val maxSeqNum: Int = 0,
        val seqNumCycles: Int = 0,
        val numExpectedPackets: Int = 0,
        val cumulativePacketsLost: Int = 0,
        val jitter: Double = 0.0
    ) {
        fun computeFractionLost(previousSnapshot: Snapshot): Int {
            val numExpectedPacketsInterval = numExpectedPackets - previousSnapshot.numExpectedPackets
            val numReceivedPacketsInterval = numReceivedPackets - previousSnapshot.numReceivedPackets

            val numLostPacketsInterval = numExpectedPacketsInterval - numReceivedPacketsInterval
            return if (numExpectedPacketsInterval == 0 || numLostPacketsInterval <= 0)
                0
            else
                (numLostPacketsInterval shl 8) / numExpectedPacketsInterval
        }
    }
}
