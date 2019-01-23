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

import org.ice4j.util.RateStatistics
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.RtpUtils.Companion.convertRtpTimestampToMs
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.isNewerThan
import org.jitsi.nlj.util.isNextAfter
import org.jitsi.nlj.util.numPacketsTo
import org.jitsi.nlj.util.rolledOverTo
import org.jitsi.rtp.RtpPacket
import toUInt
import unsigned.toUShort
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Track various statistics about received RTP streams to be used in SR/RR report blocks
 */
class IncomingStatisticsTracker : Node("Incoming statistics tracker") {
    private val streamStats: MutableMap<Long, IncomingStreamStatistics> = ConcurrentHashMap()
    private val payloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()
    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<RtpPacket> { packetInfo, rtpPacket ->
            val stats = streamStats.computeIfAbsent(rtpPacket.header.ssrc) {
                IncomingStreamStatistics(rtpPacket.header.ssrc, rtpPacket.header.sequenceNumber)
            }
            payloadTypes[rtpPacket.header.payloadType.toByte()]?.let {
                val packetSentTimestamp = convertRtpTimestampToMs(rtpPacket.header.timestamp.toUInt(), it.clockRate)
                stats.packetReceived(rtpPacket, packetSentTimestamp, packetInfo.receivedTime)
            }
        }
        next(p)
    }

    fun getCurrentStats(): Map<Long, IncomingStreamStatistics> = streamStats.toMap()

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                // We don't want to track jitter, etc. for RTX streams
                if (event.payloadType is RtxPayloadType) {
                    logger.cinfo { "Statistics tracker ignoring RTX format: ${event.payloadType}" }
                } else {
                    payloadTypes[event.payloadType.pt] = event.payloadType
                }
            }
            is RtpPayloadTypeClearEvent -> payloadTypes.clear()
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            val stats = getCurrentStats()
            stats.forEach { ssrc, streamStats ->
                addStat("source: $ssrc")
                addStat(streamStats.getSnapshot().toString())
            }
        }
    }
}


/**
 * Tracks various statistics for the stream using ssrc [ssrc].  Some statistics are tracked only
 * over a specific interval (in between calls to [reset]) and others persist across calls to [reset].  This
 * is tuned for use in generating an RR packet.
 * TODO: max dropout/max misorder/probation handling according to appendix A.1
 */
class IncomingStreamStatistics(
    private val ssrc: Long,
    private var baseSeqNum: Int
) {
    //TODO: for now we'll synchronize access to all the stats so we can create a consistent snapshot when it's
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
        get()  = calculateExpectedPacketCount(0, baseSeqNum, seqNumCycles, maxSeqNum)
    private var cumulativePacketsLost: Int = 0
    private var outOfOrderPacketCount: Int = 0
    private var jitter: Double = 0.0
    private var numReceivedPackets: Int = 0
    private val bitrate = RateStatistics(Duration.ofSeconds(1).toMillis().toUInt())
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
        synchronized (statsLock) {
            return Snapshot(numReceivedPackets, maxSeqNum, seqNumCycles, numExpectedPackets,
                    cumulativePacketsLost, jitter, bitrate.rate)
        }
    }

    /**
     * Resets this [IncomingStreamStatistics]'s tracking variables such that:
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
     * Notify this [IncomingStreamStatistics] instance that an RTP packet [packet] for the stream it is
     * tracking has been received and that it: was sent at [packetSentTimestampMs] (note this is NOT the
     * raw RTP timestamp, but the 'translated' timestamp which is a function of the RTP timestamp and the clockrate)
     * and was received at [packetReceivedTimeMs]
     */
    fun packetReceived(
        packet: RtpPacket,
        packetSentTimestampMs: Long,
        packetReceivedTimeMs: Long
    ) {
        val packetSequenceNumber = packet.header.sequenceNumber
        synchronized(statsLock) {
            numReceivedPackets++
            bitrate.update(packet.size, System.currentTimeMillis())
            if (packetSequenceNumber isNewerThan maxSeqNum) {
                if (packetSequenceNumber isNextAfter maxSeqNum) {
                    if (probation > 0) {
                        probation--
                        //TODO: do we want to 'reset' the sequence after the probation period is finished?
                    }
                } else if (maxSeqNum numPacketsTo packetSequenceNumber in 0..MAX_DROPOUT) {
                    // In order with a gap, but gap is within acceptable range
                    cumulativePacketsLost += maxSeqNum numPacketsTo packetSequenceNumber
                    maybeResetProbation()
                } else {
                    // Very large jump
                    //TODO
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
                    //TODO
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
     * A class to export a consistent snapshot of the data held inside [IncomingStreamStatistics]
     */
    data class Snapshot(
        val numRececivedPackets: Int = 0,
        val maxSeqNum: Int = 0,
        val seqNumCycles: Int = 0,
        val numExpectedPackets: Int = 0,
        val cumulativePacketsLost: Int = 0,
        val jitter: Double = 0.0,
        val bitrate: Long = 0
    ) {
        val fractionLost: Int
            get() = (cumulativePacketsLost / numExpectedPackets) * 256

        fun getDelta(previousSnapshot: Snapshot): Snapshot {
            return Snapshot(
                numRececivedPackets - previousSnapshot.numRececivedPackets,
                maxSeqNum,
                seqNumCycles,
                numExpectedPackets - previousSnapshot.numExpectedPackets,
                cumulativePacketsLost - previousSnapshot.cumulativePacketsLost,
                jitter
            )
        }
    }
}

/*
 * Per-source state information
 *
 * typedef struct {
 *     u_int16 max_seq;        /* highest seq. number seen */
 *     u_int32 cycles;         /* shifted count of seq. number cycles */
 *     u_int32 base_seq;       /* base seq number */
 *     u_int32 bad_seq;        /* last 'bad' seq number + 1 */
 *     u_int32 probation;      /* sequ. packets till source is valid */
 *     u_int32 received;       /* packets received */
 *     u_int32 expected_prior; /* packet expected at last interval */
 *     u_int32 received_prior; /* packet received at last interval */
 *     u_int32 transit;        /* relative trans time for prev pkt */
 *     u_int32 jitter;         /* estimated jitter */
 *     /* ... */
 * } source;
 */
class Source {
    var max_seq: Short = 0
    var cycles: Int = 0
    var base_seq: Int = 0
    var bad_seq: Int = StreamStatistics2.RTP_SEQ_MOD + 1
    var probation: Int = 0
    var received: Int = 0
    var expected_prior: Int = 0
    var received_prior: Int = 0
    var transit: Int = 0
    var jitter: Int = 0
}

class StreamStatistics2 {
    companion object {
        const val RTP_SEQ_MOD = 1 shl 16
        const val MIN_SEQUENTIAL = 2
        const val MAX_DROPOUT = 3000
        const val MAX_MISORDER = 100

        fun init_seq(s: Source, seq: Short) {
            s.base_seq = seq.toInt()
            s.max_seq = seq
            s.bad_seq = RTP_SEQ_MOD + 1
            s.cycles = 0
            s.received = 0
            s.received_prior = 0
            s.expected_prior = 0
        }

        fun update_seq(s: Source, seq: Short): Int {
            val delta = (seq - s.max_seq).toUShort()
            if (s.probation > 0) {
                if (seq == (s.max_seq + 1).toShort()) {
                    /* packet is in sequence */
                    s.probation--
                    s.max_seq = seq
                    if (s.probation == 0) {
                        init_seq(s, seq)
                        s.received++
                        return 1
                    }

                } else {
                    s.probation = MIN_SEQUENTIAL - 1
                    s.max_seq = seq
                }
                return 0
            } else if (delta < MAX_DROPOUT) {
                /* in order, with permissible gap */
                if (seq < s.max_seq) {
                    /*
                     * Sequence number wrapped - count another 64K cycle.
                     */
                    s.cycles += RTP_SEQ_MOD
                }
                s.max_seq = seq
            } else if (delta <= RTP_SEQ_MOD - MAX_MISORDER) {
                /* the sequence number made a very large jump */
                if (seq == s.bad_seq.toShort()) {
                    /*
                     * Two sequential packets -- assume that the other side
                     * restarted without telling us so just re-sync
                     * (i.e., pretend this was the first packet).
                     */
                    init_seq(s, seq)
                } else {
                    s.bad_seq = (seq + 1) and (RTP_SEQ_MOD - 1)
                    return 0
                }
            } else {
                /* duplicate or reordered packet */
            }
            s.received++
            return 1
        }
    }
}

class Receiver : Node("RTP receiver"){
    val sources = mutableMapOf<Long, Source>()

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<RtpPacket> { packetInfo, rtpPacket ->
            val source = sources.computeIfAbsent(rtpPacket.header.ssrc) {
                val newSource = Source()
                StreamStatistics2.init_seq(newSource, rtpPacket.header.sequenceNumber.toShort())
                newSource.max_seq = (rtpPacket.header.sequenceNumber - 1).toShort()
                newSource.probation = StreamStatistics2.MIN_SEQUENTIAL

                newSource
            }
            StreamStatistics2.update_seq(source, rtpPacket.header.sequenceNumber.toShort())
        }
    }


}
