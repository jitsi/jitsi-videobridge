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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.wrapsTo
import org.jitsi.service.neomedia.format.MediaFormat
import org.jitsi.util.RTPUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Track various statistics about received RTP streams
 */
class StatisticsTracker : Node("Incoming statistics tracker") {
    val streamStats = mutableMapOf<Long, StreamStatistics>()
    override fun doProcessPackets(p: List<PacketInfo>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                streamStats.values.forEach {
                    it.payloadFormats[event.payloadType] = event.format
                }
            }
            is RtpPayloadTypeClearEvent -> {
                streamStats.values.forEach {
                    it.payloadFormats.clear()
                }
            }
        }
        super.handleEvent(event)
    }
}


/**
 * Tracks various statistics for the stream using ssrc [ssrc]
 * TODO: handle PT changes
 */
class StreamStatistics(
    private val ssrc: Long
) {
    /*private*/ val payloadFormats: MutableMap<Byte, MediaFormat> = ConcurrentHashMap()
    /**
     * This will be initialized to the first sequence number we process
     */
    private var baseSeqNum: Int = -1
    private var maxSeqNum: Int = 0
    private var seqNumCycles: Int = 0
    private var cumulativePacketsLost: Int = 0
    private var outOfOrderPacketCount: Int = 0
    /**
     * The timestamp of the previously received packet, converted to a millisecond timestamp based on the received
     * RTP timestamp and the clock rate for that stream.
     * 'previously received packet' here is as defined by the order in which the packets were received by this code,
     * which may be different than the order according to sequence number.
     */
    private var previousPacketSentTimestamp: Int = -1
    private var previousPacketReceivedTimestamp: Int = -1
    private var probation: Int = INITIAL_MIN_SEQUENTIAL
    private var jitter: Double = 0.0

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

        const val MAX_MISSING = 3000

        fun calculateJitter(
            currentJitter: Double,
            previousPacketSentTimestamp: Int,
            previousPacketReceivedTimestamp: Int,
            currentPacketSentTimestamp: Int,
            currentPacketReceivedTimestamp: Int
        ): Double {
            /**
             * If Si is the RTP timestamp from packet i, and Ri is the time of
             * arrival in RTP timestamp units for packet i, then for two packets
             * i and j, D may be expressed as
             *
             * D(i,j) = (Rj - Ri) - (Sj - Si) = (Rj - Sj) - (Ri - Si)
             */
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

    fun packetReceived(
        packetSequenceNumber: Int,
        packetRtpTimestamp: Int,
        packetReceivedTimeMs: Int,
        //TODO: could we not pass in PT and pass in the translated timestamp instead? then we would have to manage
        // PT type changes elsewhere (maybe that's better?)
        packetPayloadType: Byte
    ) {
        if (baseSeqNum == -1) {
            baseSeqNum = packetSequenceNumber
        }
        val seqNumDelta = RTPUtils.getSequenceNumberDelta(maxSeqNum, packetSequenceNumber)
        when {
            seqNumDelta == -1 -> { // packet's sequence number = maxSeqNum + 1, i.e. this is the next expected packet
                if (probation > 0) {
                    probation--
                }
            }
            seqNumDelta < -1 -> { // maxSeqNum < packet's sequence number by more than 1, i.e. missing packets
                cumulativePacketsLost += (-seqNumDelta - 1)
                resetProbation()
                if (maxSeqNum wrapsTo packetSequenceNumber) {
                    seqNumCycles++
                }
            }
            seqNumDelta > 0 -> { // maxSeqNum > packet's sequence number, i.e. this is an 'old' packet
                if (seqNumDelta < MAX_OOO_AMOUNT) {
                    // This packet would've already been counted as lost, so subtract from the loss count
                    // NOTE: this is susceptible to inaccuracy in the case of duplicate, out-of-order packets
                    // but for now we'll assume those are rare enough not to cause issues.
                    cumulativePacketsLost--
                }
                outOfOrderPacketCount++
                resetProbation()
            }
        }
        val currentPacketSentTimestamp = convertRtpTimestampToMs(
            packetRtpTimestamp,
            payloadFormats[packetPayloadType]!!.clockRate
        )

        jitter = calculateJitter(
            jitter,
            previousPacketSentTimestamp,
            previousPacketReceivedTimestamp,
            currentPacketSentTimestamp,
            packetReceivedTimeMs
        )


    }

    private fun resetProbation() {
        // When we reset, we subtract 1 since we've already started receiving packets so we already have
        // a 'starting point' for our sequential check (unlike when this instance is initialized, when we
        // haven't processing any incoming packets yet)
        probation = INITIAL_MIN_SEQUENTIAL - 1
    }

    private fun convertRtpTimestampToMs(rtpTimestamp: Int, ticksPerSecond: Double): Int {
        return ((rtpTimestamp / ticksPerSecond) * 1000).toInt()
    }

}
