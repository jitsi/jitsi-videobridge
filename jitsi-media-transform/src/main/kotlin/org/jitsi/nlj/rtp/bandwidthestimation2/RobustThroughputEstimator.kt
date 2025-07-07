/*
 * Copyright @ 2019-present 8x8, Inc
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

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.per
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.max
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayDeque

/**
 * Robust throughput estimator.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/robust_throughput_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 */
class RobustThroughputEstimator(val settings: RobustThroughputEstimatorSettings) :
    AcknowledgedBitrateEstimatorInterface {
    // TODO: pass parent logger in so we have log contexts
    private val logger = createLogger()

    init {
        require(settings.enabled)
    }

    override fun incomingPacketFeedbackVector(packetFeedbackVector: List<PacketResult>) {
        require(packetFeedbackVector.isSorted(compareBy { it.receiveTime }))
        packetFeedbackVector.forEach { packet ->
            // Ignore packets without valid send or receive times.
            // (This should not happen in production since lost packets are filtered
            // out before passing the feedback vector to the throughput estimator.
            // However, explicitly handling this case makes the estimator more robust
            // and avoids a hard-to-detect bad state.)
            if (packet.receiveTime.isInfinite() ||
                packet.sentPacket.sendTime.isInfinite()
            ) {
                return@forEach
            }
            // Insert the new packet.
            window.addLast(packet)
            window.last().sentPacket.priorUnackedData =
                window.last().sentPacket.priorUnackedData *
                settings.unackedWeight
            // In most cases, receive timestamps should already be in order, but in the
            // rare case where feedback packets have been reordered, we do some swaps to
            // ensure that the window is sorted.
            var i = window.size - 1
            while (i > 0 && window[i].receiveTime < window[i - 1].receiveTime) {
                Collections.swap(window, i, i - 1)
                i--
            }
            val kMaxReorderingTime = 1.secs
            val receiveDelta = Duration.between(packet.receiveTime, window.last().receiveTime)

            if (receiveDelta > kMaxReorderingTime) {
                logger.warn("Severe packet re-ordering or timestamps offset changed: $receiveDelta")
                window.clear()
                latestDiscardedSendTime = Instant.MIN
            }
        }

        // Remove old packets.
        while (firstPacketOutsideWindow()) {
            latestDiscardedSendTime = max(latestDiscardedSendTime, window.first().sentPacket.sendTime)
            window.removeFirst()
        }
    }

    override fun bitrate(): Bandwidth? {
        if (window.isEmpty() || window.size < settings.requiredPackets) {
            return null
        }

        var largestRecvGap = Duration.ZERO
        var secondLargestRecvGap = Duration.ZERO
        for (i in 1 until window.size) {
            // Find receive time gaps.
            val gap = Duration.between(window[i - 1].receiveTime, window[i].receiveTime)
            if (gap > largestRecvGap) {
                secondLargestRecvGap = largestRecvGap
                largestRecvGap = gap
            } else if (gap > secondLargestRecvGap) {
                secondLargestRecvGap = gap
            }
        }

        var firstSendTime = Instant.MAX
        var lastSendTime = Instant.MIN
        var firstRecvTime = Instant.MAX
        var lastRecvTime = Instant.MIN
        var recvSize = 0.bytes
        var sendSize = 0.bytes
        var firstRecvSize = 0.bytes
        var lastSendSize = 0.bytes
        var numSentPacketsInWindow = 0
        window.forEach { packet ->
            if (packet.receiveTime < firstRecvTime) {
                firstRecvTime = packet.receiveTime
                firstRecvSize =
                    packet.sentPacket.size + packet.sentPacket.priorUnackedData
            }
            lastRecvTime = max(lastRecvTime, packet.receiveTime)
            recvSize += packet.sentPacket.size
            recvSize += packet.sentPacket.priorUnackedData

            if (packet.sentPacket.sendTime < latestDiscardedSendTime) {
                // If we have dropped packets from the window that were sent after
                // this packet, then this packet was reordered. Ignore it from
                // the send rate computation (since the send time may be very far
                // in the past, leading to underestimation of the send rate.)
                // However, ignoring packets creates a risk that we end up without
                // any packets left to compute a send rate.
                return@forEach
            }
            if (packet.sentPacket.sendTime > lastSendTime) {
                lastSendTime = packet.sentPacket.sendTime
                lastSendSize =
                    packet.sentPacket.size + packet.sentPacket.priorUnackedData
            }
            firstSendTime = min(firstSendTime, packet.sentPacket.sendTime)

            sendSize += packet.sentPacket.size
            sendSize += packet.sentPacket.priorUnackedData
            ++numSentPacketsInWindow
        }

        // Suppose a packet of size S is sent every T milliseconds.
        // A window of N packets would contain N*S bytes, but the time difference
        // between the first and the last packet would only be (N-1)*T. Thus, we
        // need to remove the size of one packet to get the correct rate of S/T.
        // Which packet to remove (if the packets have varying sizes),
        // depends on the network model.
        // Suppose that 2 packets with sizes s1 and s2, are received at times t1
        // and t2, respectively. If the packets were transmitted back to back over
        // a bottleneck with rate capacity r, then we'd expect t2 = t1 + r * s2.
        // Thus, r = (t2-t1) / s2, so the size of the first packet doesn't affect
        // the difference between t1 and t2.
        // Analoguously, if the first packet is sent at time t1 and the sender
        // paces the packets at rate r, then the second packet can be sent at time
        // t2 = t1 + r * s1. Thus, the send rate estimate r = (t2-t1) / s1 doesn't
        // depend on the size of the last packet.
        recvSize -= firstRecvSize
        sendSize -= lastSendSize

        // Remove the largest gap by replacing it by the second largest gap.
        // This is to ensure that spurious "delay spikes" (i.e. when the
        // network stops transmitting packets for a short period, followed
        // by a burst of delayed packets), don't cause the estimate to drop.
        // This could cause an overestimation, which we guard against by
        // never returning an estimate above the send rate.
        check(firstRecvTime.isFinite())
        check(lastRecvTime.isFinite())
        val recvDuration = (
            Duration.between(firstRecvTime, lastRecvTime) -
                largestRecvGap + secondLargestRecvGap
            ).coerceAtLeast(1.ms)

        if (numSentPacketsInWindow < settings.requiredPackets) {
            // Too few send times to calculate a reliable send rate.
            return recvSize.per(recvDuration)
        }

        check(firstSendTime.isFinite())
        check(lastSendTime.isFinite())
        val sendDuration = Duration.between(firstSendTime, lastSendTime).coerceAtLeast(1.ms)

        return min(sendSize.per(sendDuration), recvSize.per(recvDuration))
    }

    override fun peekRate(): Bandwidth? = bitrate()

    override fun setAlr(inAlr: Boolean) {}

    override fun setAlrEndedTime(alrEndedTime: Instant) {}

    private fun firstPacketOutsideWindow(): Boolean {
        if (window.isEmpty()) {
            return false
        }
        if (window.size > settings.maxWindowPackets) {
            return true
        }
        val currentWindowDuration = Duration.between(window.first().receiveTime, window.last().receiveTime)
        if (currentWindowDuration > settings.maxWindowDuration) {
            return true
        }
        if (window.size > settings.windowPackets &&
            currentWindowDuration > settings.minWindowDuration
        ) {
            return true
        }
        return false
    }

    private val window = ArrayDeque<PacketResult>()

    private var latestDiscardedSendTime: Instant = Instant.MIN
}

/* TODO: move this to a util somewhere */
fun <E> Collection<E>.isSorted(comparator: Comparator<E>): Boolean {
    this.asSequence().windowed(2).forEach { (a, b) ->
        if (comparator.compare(a, b) > 0) {
            return@isSorted false
        }
    }
    return true
}
