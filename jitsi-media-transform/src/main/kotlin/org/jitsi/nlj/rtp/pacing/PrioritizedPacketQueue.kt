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

package org.jitsi.nlj.rtp.pacing

import org.jitsi.nlj.rtp.scream.microsToDuration
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.NEVER
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.ms
import org.jitsi.utils.toMicros
import java.time.Duration
import java.time.Instant

private const val kAudioPrioLevel = 0

private fun getPriorityForType(type: RtpPacketMediaType, originalType: RtpPacketToSend.OriginalType?): Int {
    // Lower number takes priority over higher.
    return when (type) {
        // Audio is always prioritized over other packet types.
        RtpPacketMediaType.kAudio -> kAudioPrioLevel

        // Send retransmissions before new media. If original_type is set, audio
        // retransmission is prioritized more than video retransmission.
        RtpPacketMediaType.kRetransmission ->
            if (originalType == RtpPacketToSend.OriginalType.kVideo) {
                kAudioPrioLevel + 2
            } else {
                kAudioPrioLevel + 1
            }

        // Video has "normal" priority, in the old speak.
        // Send redundancy concurrently to video. If it is delayed it might have a
        // lower chance of being useful.
        RtpPacketMediaType.kVideo,
        RtpPacketMediaType.kForwardErrorCorrection -> kAudioPrioLevel + 3

        // Packets that are in themselves likely useless, only sent to keep the
        // BWE high.
        RtpPacketMediaType.kPadding -> kAudioPrioLevel + 4
    }
}

// Describes how long time a packet may stay in the queue before being dropped.
data class PacketQueueTTL(
    val audioRetransmission: Duration = MAX_DURATION,
    val videoRetransmission: Duration = MAX_DURATION,
    val video: Duration = MAX_DURATION
)

/** Prioritized packet queue,
 * based on WebRTC modules/pacing/prioritized_packet_queue.{h,cc} in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
class PrioritizedPacketQueue(
    parentLogger: Logger,
    creationTime: Instant,
    private val prioritizeAudioRetransmission: Boolean = false,
    packetQueueTtl: PacketQueueTTL = PacketQueueTTL(),
    /**
     * Jitsi local: called with each packet that the queue drops because it stayed in the queue longer than its time
     * to live. WebRTC frees such a packet. Here the caller has to return its buffer.
     */
    private val onPacketDropped: (RtpPacketToSend) -> Unit = {}
) {
    private val logger = createChildLogger(parentLogger)

    // Add a packet to the queue. The enqueue time is used for queue time stats
    // and to report the leading packet enqueue time per packet type.
    fun push(enqueueTime: Instant, packet: RtpPacketToSend) {
        val streamQueue = streams.getOrPut(packet.ssrc) { StreamQueue(enqueueTime) }

        val enqueueTimeNode = enqueueTimes.add(enqueueTime)
        val packetType = packet.packetType
        val prioLevel = getPriorityForType(
            packetType,
            if (prioritizeAudioRetransmission) packet.originalPacketType else null
        )
        purgeOldPacketsAtPriorityLevel(prioLevel, enqueueTime)
        check(prioLevel >= 0)
        check(prioLevel < kNumPriorityLevels)
        val queuedPacket = QueuedPacket(packet, enqueueTime, enqueueTimeNode)
        // In order to figure out how much time a packet has spent in the queue
        // while not in a paused state, we subtract the total amount of time the
        // queue has been paused so far, and when the packet is popped we subtract
        // the total amount of time the queue has been paused at that moment. This
        // way we subtract the total amount of time the packet has spent in the
        // queue while in a paused state.
        updateAverageQueueTime(enqueueTime)
        queuedPacket.enqueueTime -= pauseTimeSum
        ++sizePackets
        ++sizePacketsPerMediaType[packetType.ordinal]
        sizePayload += queuedPacket.packetSize()

        if (streamQueue.enqueuePacket(queuedPacket, prioLevel)) {
            // Number packets at `prio_level` for this steam is now non-zero.
            streamsByPrio[prioLevel].addLast(streamQueue)
        }
        if (topActivePrioLevel < 0 || prioLevel < topActivePrioLevel) {
            topActivePrioLevel = prioLevel
        }

        if (Duration.between(lastCullingTime, enqueueTime) > kTimeout) {
            val streamIt = streams.entries.iterator()
            while (streamIt.hasNext()) {
                val stream = streamIt.next().value
                if (stream.isEmpty() && stream.lastEnqueueTime() + kTimeout < enqueueTime) {
                    streamIt.remove()
                }
            }
            lastCullingTime = enqueueTime
        }
    }

    // Remove the next packet from the queue. Packets a prioritized first
    // according to packet type, in the following order:
    // - audio, retransmissions, video / fec, padding
    // For each packet type, we use one FIFO-queue per SSRC and emit from
    // those queues in a round-robin fashion.
    fun pop(): RtpPacketToSend? {
        if (sizePackets == 0) {
            return null
        }

        check(topActivePrioLevel >= 0)
        val streamQueue = streamsByPrio[topActivePrioLevel].first()
        val packet = streamQueue.dequeuePacket(topActivePrioLevel)
        dequeuePacketInternal(packet)

        // Remove StreamQueue from head of fifo-queue for this prio level, and
        // and add it to the end if it still has packets.
        streamsByPrio[topActivePrioLevel].removeFirst()
        if (streamQueue.hasPacketsAtPrio(topActivePrioLevel)) {
            streamsByPrio[topActivePrioLevel].addLast(streamQueue)
        } else {
            maybeUpdateTopPrioLevel()
        }

        return packet.packet
    }

    // Number of packets in the queue.
    fun sizeInPackets(): Int = sizePackets

    // Sum of all payload bytes in the queue, where the payload is calculated
    // as `packet->payload_size() + packet->padding_size()`.
    fun sizeInPayloadBytes(): DataSize = sizePayload

    // Convenience method for `SizeInPackets() == 0`.
    fun empty(): Boolean = sizePackets == 0

    // Total packets in the queue per media type (RtpPacketMediaType values are
    // used as lookup index).
    fun sizeInPacketsPerRtpPacketMediaType(): IntArray = sizePacketsPerMediaType

    // The enqueue time of the next packet this queue will return via the Pop()
    // method, for the given packet type. If queue has no packets, of that type,
    // returns Timestamp::MinusInfinity().
    fun leadingPacketEnqueueTime(type: RtpPacketMediaType): Instant {
        check(type != RtpPacketMediaType.kRetransmission)
        val priorityLevel = getPriorityForType(type, null)
        if (streamsByPrio[priorityLevel].isEmpty()) {
            return NEVER
        }
        return streamsByPrio[priorityLevel].first().leadingPacketEnqueueTime(priorityLevel)
    }

    fun leadingPacketEnqueueTimeForRetransmission(): Instant {
        if (!prioritizeAudioRetransmission) {
            val priorityLevel = getPriorityForType(RtpPacketMediaType.kRetransmission, null)
            if (streamsByPrio[priorityLevel].isEmpty()) {
                return Instant.MAX
            }
            return streamsByPrio[priorityLevel].first().leadingPacketEnqueueTime(priorityLevel)
        }
        val audioPriorityLevel =
            getPriorityForType(RtpPacketMediaType.kRetransmission, RtpPacketToSend.OriginalType.kAudio)
        val videoPriorityLevel =
            getPriorityForType(RtpPacketMediaType.kRetransmission, RtpPacketToSend.OriginalType.kVideo)

        val nextAudio = if (streamsByPrio[audioPriorityLevel].isEmpty()) {
            Instant.MAX
        } else {
            streamsByPrio[audioPriorityLevel].first().leadingPacketEnqueueTime(audioPriorityLevel)
        }
        val nextVideo = if (streamsByPrio[videoPriorityLevel].isEmpty()) {
            Instant.MAX
        } else {
            streamsByPrio[videoPriorityLevel].first().leadingPacketEnqueueTime(videoPriorityLevel)
        }
        return minOf(nextAudio, nextVideo)
    }

    // Enqueue time of the oldest packet in the queue,
    // Timestamp::MinusInfinity() if queue is empty.
    fun oldestEnqueueTime(): Instant = enqueueTimes.front() ?: NEVER

    // Average queue time for the packets currently in the queue.
    // The queuing time is calculated from Push() to the last UpdateQueueTime()
    // call - with any time spent in a paused state subtracted.
    // Returns TimeDelta::Zero() for an empty queue.
    fun averageQueueTime(): Duration {
        if (sizePackets == 0) {
            return Duration.ZERO
        }
        return microsToDuration(queueTimeSum.toMicros() / sizePackets)
    }

    // Called during packet processing or when pause stats changes. Since the
    // AverageQueueTime() method does not look at the wall time, this method
    // needs to be called before querying queue time.
    fun updateAverageQueueTime(now: Instant) {
        check(now >= lastUpdateTime)
        if (now == lastUpdateTime) {
            return
        }

        val delta = Duration.between(lastUpdateTime, now)

        if (paused) {
            pauseTimeSum += delta
        } else {
            queueTimeSum += delta.multipliedBy(sizePackets.toLong())
        }

        lastUpdateTime = now
    }

    // Set the pause state, while `paused` is true queuing time is not counted.
    fun setPauseState(paused: Boolean, now: Instant) {
        updateAverageQueueTime(now)
        this.paused = paused
    }

    // Remove any packets matching the given SSRC.
    // TODO(bugs.webrtc.org/564720400): Either remove or make permanent returning
    // removed packets to allow retransmission.
    fun removePacketsForSsrc(ssrc: Long): List<RtpPacketToSend> {
        val removedPackets = mutableListOf<RtpPacketToSend>()
        val queue = streams[ssrc]
        if (queue != null) {
            // Dequeue all packets from the queue for this SSRC.
            val packetsByPrio = queue.dequeueAll()
            for (i in 0 until kNumPriorityLevels) {
                val packetQueue = packetsByPrio[i]
                if (packetQueue.isEmpty()) {
                    continue
                }

                // First erase all packets at this prio level.
                while (!packetQueue.isEmpty()) {
                    val packet = packetQueue.removeFirst()
                    dequeuePacketInternal(packet)
                    removedPackets.add(packet.packet)
                }

                // Next, deregister this `StreamQueue` from the round-robin tables.
                check(!streamsByPrio[i].isEmpty())
                if (streamsByPrio[i].size == 1) {
                    // This is the last and only queue that had packets for this prio level.
                    // Update the global top prio level if neccessary.
                    check(streamsByPrio[i].first() === queue)
                    streamsByPrio[i].removeFirst()
                } else {
                    // More than stream had packets at this prio level, filter this one out.
                    streamsByPrio[i].removeAll { it === queue }
                }
            }
        }
        maybeUpdateTopPrioLevel()
        return removedPackets
    }

    // Checks if the queue for the given SSRC has original (retransmissions not
    // counted) video packets containing keyframe data.
    fun hasKeyframePackets(ssrc: Long): Boolean = streams[ssrc]?.hasKeyframePackets() ?: false

    /**
     * Jitsi local: the list of the enqueue times, which is a std::list in WebRTC. A [QueuedPacket] has the node of its
     * enqueue time, where WebRTC has an iterator, for fast removal.
     */
    private class EnqueueTimes {
        class Node(val time: Instant) {
            var previous: Node? = null
            var next: Node? = null
        }

        private var head: Node? = null
        private var tail: Node? = null

        fun add(time: Instant): Node {
            val node = Node(time)
            node.previous = tail
            tail?.next = node
            tail = node
            if (head == null) {
                head = node
            }
            return node
        }

        fun remove(node: Node) {
            val previous = node.previous
            val next = node.next
            if (previous != null) previous.next = next else head = next
            if (next != null) next.previous = previous else tail = previous
            node.previous = null
            node.next = null
        }

        fun front(): Instant? = head?.time
    }

    private class QueuedPacket(
        val packet: RtpPacketToSend,
        var enqueueTime: Instant,
        val enqueueTimeNode: EnqueueTimes.Node
    ) {
        fun packetSize(): DataSize = (packet.payloadSize + packet.paddingSize).bytes
    }

    // Class containing packets for an RTP stream.
    // For each priority level, packets are simply stored in a fifo queue.
    private class StreamQueue(creationTime: Instant) {
        // Enqueue packet at the given priority level. Returns true if the packet
        // count for that priority level went from zero to non-zero.
        fun enqueuePacket(packet: QueuedPacket, priorityLevel: Int): Boolean {
            if (packet.packet.isKeyFrame) {
                ++numKeyframePackets
            }
            val firstPacketAtLevel = packets[priorityLevel].isEmpty()
            packets[priorityLevel].addLast(packet)
            return firstPacketAtLevel
        }

        fun dequeuePacket(priorityLevel: Int): QueuedPacket {
            check(!packets[priorityLevel].isEmpty())
            val packet = packets[priorityLevel].removeFirst()
            if (packet.packet.isKeyFrame) {
                check(numKeyframePackets > 0)
                --numKeyframePackets
            }
            return packet
        }

        fun hasPacketsAtPrio(priorityLevel: Int): Boolean = !packets[priorityLevel].isEmpty()

        fun isEmpty(): Boolean = packets.all { it.isEmpty() }

        fun leadingPacketEnqueueTime(priorityLevel: Int): Instant {
            check(!packets[priorityLevel].isEmpty())
            return packets[priorityLevel].first().enqueueTime
        }

        fun lastEnqueueTime(): Instant = lastEnqueueTime

        fun hasKeyframePackets(): Boolean = numKeyframePackets > 0

        fun dequeueAll(): Array<ArrayDeque<QueuedPacket>> {
            val packetsByPrio = packets
            packets = Array(kNumPriorityLevels) { ArrayDeque() }
            numKeyframePackets = 0
            return packetsByPrio
        }

        private var packets = Array(kNumPriorityLevels) { ArrayDeque<QueuedPacket>() }
        private val lastEnqueueTime: Instant = creationTime
        private var numKeyframePackets = 0
    }

    // Remove the packet from the internal state, e.g. queue time / size etc.
    private fun dequeuePacketInternal(packet: QueuedPacket) {
        --sizePackets
        val packetType = packet.packet.packetType
        --sizePacketsPerMediaType[packetType.ordinal]
        check(sizePacketsPerMediaType[packetType.ordinal] >= 0)
        sizePayload -= packet.packetSize()

        // Calculate the total amount of time spent by this packet in the queue
        // while in a non-paused state. Note that the `pause_time_sum_ms_` was
        // subtracted from `packet.enqueue_time_ms` when the packet was pushed, and
        // by subtracting it now we effectively remove the time spent in in the
        // queue while in a paused state.
        val timeInNonPausedState = Duration.between(packet.enqueueTime, lastUpdateTime) - pauseTimeSum
        queueTimeSum -= timeInNonPausedState

        // Set the time spent in the send queue, which is the per-packet equivalent of
        // totalPacketSendDelay. The notion of being paused is an implementation
        // detail that we do not want to expose, so it makes sense to report the
        // metric excluding the pause time. This also avoids spikes in the metric.
        // https://w3c.github.io/webrtc-stats/#dom-rtcoutboundrtpstreamstats-totalpacketsenddelay
        packet.packet.timeInSendQueue = timeInNonPausedState

        check(sizePackets > 0 || queueTimeSum == Duration.ZERO)

        enqueueTimes.remove(packet.enqueueTimeNode)
    }

    // Check if the queue pointed to by `top_active_prio_level_` is empty and
    // if so move it to the lowest non-empty index.
    private fun maybeUpdateTopPrioLevel() {
        if (topActivePrioLevel != -1 && !streamsByPrio[topActivePrioLevel].isEmpty()) {
            return
        }
        // No stream queues have packets at top_active_prio_level_, find top priority
        // that is not empty.
        for (i in 0 until kNumPriorityLevels) {
            purgeOldPacketsAtPriorityLevel(i, lastUpdateTime)
            if (!streamsByPrio[i].isEmpty()) {
                topActivePrioLevel = i
                break
            }
        }
        if (sizePackets == 0) {
            // There are no packets left to send. Last packet may have been purged. Prio
            // will change when a new packet is pushed.
            topActivePrioLevel = -1
        }
    }

    private fun purgeOldPacketsAtPriorityLevel(prioLevel: Int, now: Instant) {
        check(prioLevel >= 0 && prioLevel < kNumPriorityLevels)
        val timeToLive = timeToLivePerPrio[prioLevel]
        if (timeToLive == MAX_DURATION) {
            return
        }

        val iter = streamsByPrio[prioLevel].iterator()
        while (iter.hasNext()) {
            val queue = iter.next()
            while (queue.hasPacketsAtPrio(prioLevel) &&
                Duration.between(queue.leadingPacketEnqueueTime(prioLevel), now) > timeToLive
            ) {
                val packet = queue.dequeuePacket(prioLevel)
                // Jitsi local: this is logged at the info level in WebRTC.
                logger.debug {
                    "Dropping old packet on SSRC: ${packet.packet.ssrc} seq:${packet.packet.sequenceNumber} " +
                        "time in queue:${Duration.between(packet.enqueueTime, now).toMillis()} ms"
                }
                dequeuePacketInternal(packet)
                onPacketDropped(packet.packet)
            }
            if (!queue.hasPacketsAtPrio(prioLevel)) {
                iter.remove()
            }
        }
    }

    private val timeToLivePerPrio: Array<Duration> = toTtlPerPrio(packetQueueTtl)

    // Cumulative sum, over all packets, of time spent in the queue.
    private var queueTimeSum: Duration = Duration.ZERO

    // Cumulative sum of time the queue has spent in a paused state.
    private var pauseTimeSum: Duration = Duration.ZERO

    // Total number of packets stored in this queue.
    private var sizePackets = 0

    // Total number of packets stored in this queue per RtpPacketMediaType.
    private val sizePacketsPerMediaType = IntArray(RtpPacketMediaType.kNumMediaTypes)

    // Sum of payload sizes for all packts stored in this queue.
    private var sizePayload = DataSize.ZERO

    // The last time queue/pause time sums were updated.
    private var lastUpdateTime: Instant = creationTime
    private var paused = false

    // Last time `streams_` was culled for inactive streams.
    private var lastCullingTime: Instant = creationTime

    // Map from SSRC to packet queues for the associated RTP stream.
    private val streams = HashMap<Long, StreamQueue>()

    // For each priority level, a queue of StreamQueues which have at least one
    // packet pending for that prio level.
    private val streamsByPrio = Array(kNumPriorityLevels) { ArrayDeque<StreamQueue>() }

    // The first index into `stream_by_prio_` that is non-empty.
    private var topActivePrioLevel = -1

    // Ordered list of enqueue times. Additions are always increasing and added to
    // the end. QueuedPacket instances have a iterators into this list for fast
    // removal.
    private val enqueueTimes = EnqueueTimes()

    companion object {
        private const val kNumPriorityLevels = 5

        private val kTimeout = 500.ms

        private fun toTtlPerPrio(packetQueueTtl: PacketQueueTTL): Array<Duration> {
            val ttlPerPrio = Array(kNumPriorityLevels) { MAX_DURATION }
            ttlPerPrio[
                getPriorityForType(RtpPacketMediaType.kRetransmission, RtpPacketToSend.OriginalType.kAudio)
            ] = packetQueueTtl.audioRetransmission
            ttlPerPrio[
                getPriorityForType(RtpPacketMediaType.kRetransmission, RtpPacketToSend.OriginalType.kVideo)
            ] = packetQueueTtl.videoRetransmission
            ttlPerPrio[getPriorityForType(RtpPacketMediaType.kVideo, null)] = packetQueueTtl.video
            return ttlPerPrio
        }
    }
}
