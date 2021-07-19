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
package org.jitsi.nlj.transform.node.incoming

import java.util.concurrent.ConcurrentHashMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.stats.JitterStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils.Companion.convertRtpTimestampToMs
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isNextAfter
import org.jitsi.rtp.util.numPacketsTo
import org.jitsi.rtp.util.rolledOverTo

/**
 * Track various statistics about received RTP streams to be used in SR/RR report blocks
 */
class IncomingStatisticsTracker(
    private val streamInformationStore: ReadOnlyStreamInformationStore
) : ObserverNode("Incoming statistics tracker") {
    private val ssrcStats: MutableMap<Long, IncomingSsrcStats> = ConcurrentHashMap()

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        streamInformationStore.rtpPayloadTypes[rtpPacket.payloadType.toByte()]?.let {
            // We don't want to track jitter, etc. for RTX streams
            if (it !is RtxPayloadType) {
                val stats = ssrcStats.computeIfAbsent(rtpPacket.ssrc) {
                    IncomingSsrcStats(rtpPacket.ssrc, rtpPacket.sequenceNumber)
                }
                val packetSentTimestamp = convertRtpTimestampToMs(rtpPacket.timestamp.toInt(), it.clockRate)
                stats.packetReceived(rtpPacket, packetSentTimestamp, packetInfo.receivedTime)
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            val stats = getSnapshot()
            stats.ssrcStats.forEach { (ssrc, streamStats) ->
                addJson(ssrc.toString(), streamStats.toJson())
            }
        }
    }

    /**
     * Don't aggregate the per-SSRC stats.
     */
    override fun getNodeStatsToAggregate() = super.getNodeStats()

    override fun trace(f: () -> Unit) = f.invoke()

    fun getSnapshot(): IncomingStatisticsSnapshot {
        return IncomingStatisticsSnapshot(
            ssrcStats.map { (ssrc, stats) ->
                Pair(ssrc, stats.getSnapshot())
            }.toMap()
        )
    }
}

class IncomingStatisticsSnapshot(
    /**
     * Per-ssrc stats.
     */
    val ssrcStats: Map<Long, IncomingSsrcStats.Snapshot>
) {
    fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
        ssrcStats.forEach() { (ssrc, snapshot) ->
            put(ssrc, snapshot.toJson())
        }
    }
}

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
    private val jitterStats = JitterStats()
    private var numReceivedPackets: Int = 0
    private var numReceivedBytes: Int = 0
    // End variables protected by statsLock

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
    }

    fun getSnapshot(): Snapshot {
        synchronized(statsLock) {
            return Snapshot(
                numReceivedPackets, numReceivedBytes, maxSeqNum, seqNumCycles, numExpectedPackets,
                cumulativePacketsLost, jitterStats.jitter
            )
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
            numReceivedBytes += packet.length
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

            jitterStats.addPacket(
                packetSentTimestampMs,
                packetReceivedTimeMs
            )
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
        val numReceivedBytes: Int = 0,
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
                (((numLostPacketsInterval shl 8) / numExpectedPacketsInterval.toDouble())).toInt()
        }

        fun toJson() = OrderedJsonObject().apply {
            put("num_received_packets", numReceivedPackets)
            put("num_received_bytes", numReceivedBytes)
            put("max_seq_num", maxSeqNum)
            put("seq_num_cycles", seqNumCycles)
            put("num_expected_packets", numExpectedPackets)
            put("cumulative_packets_lost", cumulativePacketsLost)
            put("jitter", jitter)
        }
    }
}
