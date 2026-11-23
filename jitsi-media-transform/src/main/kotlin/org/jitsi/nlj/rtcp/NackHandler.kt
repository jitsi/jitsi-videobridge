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
package org.jitsi.nlj.rtcp

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.PacketCache
import org.jitsi.nlj.util.times
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 *
 * It does not retransmit a packet that was first sent more than max([maxPacketAge], [maxPacketAgeRtts] * RTT) ago
 * (by default max(3 s, 9 RTT), see `jmt.nack` in reference.conf). A receiver that comes back after a network outage
 * can NACK all the packets that it lost. Packets that old are no longer useful to the receiver, and a burst of them
 * fills the send queue.
 *
 * It does not retransmit a packet less than min(0.9 * RTT, RTT - 5 ms) after the previous transmission of the packet.
 * When the RTT is unknown, it does not wait before the first retransmission. For a packet that it already
 * retransmitted, the interval is at least [minResendInterval].
 *
 * It also limits the bitrate of its retransmissions with a token bucket ([maxRetransmissionBitrate],
 * [retransmissionBurst]). There is one [NackHandler], and thus one budget, for each RTP sender: one for each
 * endpoint. A relay has one for each local endpoint that sends over the relay, and one more for the packets that
 * have no endpoint ID. Packets over the limit are not retransmitted, and are not marked as retransmitted, so a later
 * NACK can request them again.
 *
 * The budget goes to the packets in the order of the requests: one NACK after the other, and in one NACK from the
 * lowest sequence number up. When the budget is too small for all the requests, the stream that is NACKed first
 * gets it, and in that stream the packets with the lowest sequence numbers. These are the oldest requested packets,
 * but not when the sequence numbers in the NACK wrap.
 */
class NackHandler(
    private val packetCache: PacketCache,
    private val onNackedPacketsReady: PacketHandler,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val maxPacketAge: Duration = maxPacketAgeConfig,
    private val maxPacketAgeRtts: Double = maxPacketAgeRttsConfig,
    private val maxRetransmissionBitrate: Bandwidth = maxRetransmissionBitrateConfig,
    private val retransmissionBurst: Duration = retransmissionBurstConfig,
    private val minResendInterval: Duration = minResendIntervalConfig
) : NodeStatsProducer, RtcpListener, EndpointConnectionStats.EndpointConnectionStatsListener {
    private var numNacksReceived = 0
    private var numNackedPackets = 0
    private var numRetransmittedPackets = 0
    private var numPacketsNotResentDueToDelay = 0
    private var numPacketsNotResentDueToMinResendInterval = 0
    private var numPacketsNotResentDueToAge = 0
    private var numPacketsNotResentDueToRateLimit = 0
    private var numCacheMisses = 0
    private val logger = createChildLogger(parentLogger)
    private var currRtt: Double = -1.0
    private val budget = RetransmissionBudget(maxRetransmissionBitrate, retransmissionBurst, clock.millis())

    init {
        require(!minResendInterval.isNegative) {
            "The minimum resend interval must not be negative, but is $minResendInterval"
        }
    }

    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?) {
        if (packet is RtcpFbNackPacket) {
            onNackPacket(packet)
        }
    }

    private fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        logger.cdebug { "Nack received for ${nackPacket.mediaSourceSsrc} ${nackPacket.missingSeqNums}" }
        val now = clock.millis()
        val maxAgeMs = if (currRtt == -1.0) {
            maxPacketAge.toMillis().toDouble()
        } else {
            Math.max(maxPacketAge.toMillis().toDouble(), maxPacketAgeRtts * currRtt)
        }

        // The minimum delay between the first transmission of a packet and its first retransmission is
        // min(0.9 * RTT, RTT - 5 ms), or none when the RTT is unknown. The first NACK for a packet arrives about one
        // RTT after we sent the packet.
        val minFirstDelayMs = if (currRtt == -1.0) 0.0 else Math.min(currRtt * .9, currRtt - 5)
        // The minimum delay between two retransmissions of a packet is also at least [minResendInterval]. Without
        // this lower bound, the delay is zero when the RTT is unknown, or 5 ms or less.
        val minRepeatDelayMs = Math.max(minResendInterval.toMillis().toDouble(), minFirstDelayMs)

        numNacksReceived++
        val nackedPackets = mutableListOf<RtpPacket>()
        val ssrc = nackPacket.mediaSourceSsrc
        numNackedPackets += nackPacket.missingSeqNums.size
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            // Read the timestamps without a copy of the packet. A NACK can request hundreds of packets that we do not
            // retransmit, and a copy of each one is expensive.
            val cached = packetCache.getPacketInfo(ssrc, missingSeqNum)
            if (cached == null) {
                logger.cdebug { "Nack'd packet $ssrc $missingSeqNum wasn't in cache, unable to retransmit" }
                numCacheMisses++
                return@forEach
            }
            // The time of the first transmission. The cache updates [timeAdded] when we retransmit the packet.
            val age = now - cached.timeInserted
            // The time since the last transmission (the first one, or the last retransmission).
            val delay = now - cached.timeAdded
            // We update the timestamp of a packet in the cache only when we retransmit the packet.
            val alreadyRetransmitted = cached.timestampUpdated
            when {
                age > maxAgeMs -> numPacketsNotResentDueToAge++

                delay < minFirstDelayMs -> numPacketsNotResentDueToDelay++

                alreadyRetransmitted && delay < minRepeatDelayMs -> numPacketsNotResentDueToMinResendInterval++

                !budget.tryConsume(cached.length, now) -> numPacketsNotResentDueToRateLimit++

                else -> {
                    val packet = packetCache.get(ssrc, missingSeqNum)?.item
                    if (packet == null) {
                        // The cache dropped the packet after we read its timestamps. We do not send the packet, so it
                        // must not use the budget.
                        budget.refund(cached.length)
                        numCacheMisses++
                    } else {
                        nackedPackets.add(packet)
                        packetCache.updateTimestamp(ssrc, missingSeqNum, now)
                        numRetransmittedPackets++
                    }
                }
            }
        }
        nackedPackets.forEach {
            val packetInfo = PacketInfo(it)
            packetInfo.packetOrigin = PacketOrigin.Retransmission
            onNackedPacketsReady.processPacket(packetInfo)
        }
    }

    override fun onRttUpdate(newRttMs: Double) {
        currRtt = newRttMs
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Nack handler").apply {
        addNumber("num_nack_packets_received", numNacksReceived)
        addNumber("num_nacked_packets", numNackedPackets)
        addNumber("num_retransmitted_packets", numRetransmittedPackets)
        addNumber("num_packets_not_retransmitted", numPacketsNotResentDueToDelay)
        addNumber("num_packets_not_retransmitted_min_resend_interval", numPacketsNotResentDueToMinResendInterval)
        addNumber("num_packets_not_retransmitted_too_old", numPacketsNotResentDueToAge)
        addNumber("num_packets_not_retransmitted_rate_limited", numPacketsNotResentDueToRateLimit)
        addNumber("num_cache_misses", numCacheMisses)
    }

    companion object {
        /** The minimum value of the max age of a packet that we retransmit. */
        private val maxPacketAgeConfig: Duration by config {
            "jmt.nack.max-packet-age".from(JitsiConfig.newConfig)
        }

        /** The max age of a packet that we retransmit, as a number of RTTs (if larger than [maxPacketAgeConfig]). */
        private val maxPacketAgeRttsConfig: Double by config {
            "jmt.nack.max-packet-age-rtts".from(JitsiConfig.newConfig)
        }

        // The checks of the three values below are in the conversion, so that the error names the key.
        private val maxRetransmissionBitrateConfig: Bandwidth by config {
            "jmt.nack.max-retransmission-bitrate".from(JitsiConfig.newConfig).convertFrom<String> {
                val bitrate = try {
                    Bandwidth.fromString(it)
                } catch (e: Exception) {
                    throw IllegalArgumentException("jmt.nack.max-retransmission-bitrate has an invalid value: $it", e)
                }
                require(bitrate > Bandwidth.ZERO) {
                    "jmt.nack.max-retransmission-bitrate must be more than 0, but is $it"
                }
                bitrate
            }
        }

        private val retransmissionBurstConfig: Duration by config {
            "jmt.nack.retransmission-burst".from(JitsiConfig.newConfig).convertFrom<Duration> {
                require(!it.isNegative) { "jmt.nack.retransmission-burst must not be negative, but is $it" }
                it
            }
        }

        private val minResendIntervalConfig: Duration by config {
            "jmt.nack.min-resend-interval".from(JitsiConfig.newConfig).convertFrom<Duration> {
                require(!it.isNegative) { "jmt.nack.min-resend-interval must not be negative, but is $it" }
                it
            }
        }
    }
}

/**
 * A token bucket for the retransmissions of one [NackHandler]. The bucket holds [bitrate] * [burst] bytes, and is
 * full at [startMs]. It adds tokens at [bitrate], up to its capacity. Not thread safe: [NackHandler] calls it from the
 * pipeline that receives the RTCP.
 */
private class RetransmissionBudget(bitrate: Bandwidth, burst: Duration, startMs: Long) {
    init {
        // A bucket with no tokens refuses each packet, so 0 can not mean "no limit".
        require(bitrate > Bandwidth.ZERO) { "The maximum retransmission bitrate must be more than 0, but is $bitrate" }
        require(!burst.isNegative) { "The retransmission burst must not be negative, but is $burst" }
    }

    private val bytesPerMs = bitrate.bytesPerSec / 1000

    /** At least [MIN_CAPACITY_BYTES], so that a short [burst] does not refuse each packet that is longer than it. */
    private val capacity = Math.max((bitrate * burst).bytes, MIN_CAPACITY_BYTES)
    private var tokens = capacity
    private var lastUpdateMs = startMs

    fun tryConsume(bytes: Int, nowMs: Long): Boolean {
        // The clock can go back. In that case we add no tokens, and we count the time from the new value of the clock.
        val elapsedMs = Math.max(0L, nowMs - lastUpdateMs)
        tokens = Math.min(capacity, tokens + elapsedMs * bytesPerMs)
        lastUpdateMs = nowMs
        if (tokens < bytes) {
            return false
        }
        tokens -= bytes
        return true
    }

    /** Give back the tokens of a packet that passed [tryConsume], and that we did not send. */
    fun refund(bytes: Int) {
        tokens = Math.min(capacity, tokens + bytes)
    }

    companion object {
        /** The size of the largest packet that we expect to retransmit. */
        private const val MIN_CAPACITY_BYTES = 1500.0
    }
}
