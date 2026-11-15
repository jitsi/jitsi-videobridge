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
// This file uses WebRTC's naming style for constants
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.scream.test

import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import java.util.ArrayDeque

/*
 * Network queues for the simulated network,
 * based on WebRTC api/test/simulated_network.h, api/test/network_emulation/network_queue.h and
 * api/test/network_emulation/leaky_bucket_network_queue.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Times are in microseconds, as in the interfaces of WebRTC. kPlusInfinityUs and kMinusInfinityUs stand for the
 * infinite values of its Timestamp and TimeDelta.
 */

const val kPlusInfinityUs = Long.MAX_VALUE
const val kMinusInfinityUs = Long.MIN_VALUE

data class PacketInFlightInfo(
    val size: Long,
    val sendTimeUs: Long,
    // Unique identifier for the packet in relation to other packets in flight.
    val packetId: Long,
    val ecn: EcnMarking = EcnMarking.kNotEct
)

class PacketDeliveryInfo(source: PacketInFlightInfo, val receiveTimeUs: Long) {
    val packetId: Long = source.packetId
    var ecn: EcnMarking = source.ecn

    companion object {
        const val kNotReceived = -1L
    }
}

// NetworkQueue defines the interface for a queue used in network simulation.
// The purpose is to allow for different AQM implementations.
// A queue should not modify PacketInFlightInfo except for the explicit
// congestion notification field (ecn).
interface NetworkQueue {
    // Sets the max capacity of the queue. If there are already more than
    // `max_capacitiy` packets in the queue, the behaviour depends on the
    // implementation.
    fun setMaxPacketCapacity(maxCapacity: Int)

    // Enqueues a packet.
    // Must return true if the packet is enqueued successfully, false otherwise.
    fun enqueuePacket(packetInfo: PacketInFlightInfo): Boolean

    // Next packet that can be dequeued.
    fun peekNextPacket(): PacketInFlightInfo?

    // Dequeues a packet.
    // or null if there are no enqueued packets.
    fun dequeuePacket(timeNowUs: Long): PacketInFlightInfo?

    // Dequeues all packets that are dropped by the queue itself after being
    // enqueued.
    fun dequeueDroppedPackets(): List<PacketInFlightInfo>

    fun empty(): Boolean

    companion object {
        // Max capacity a queue is expected to handle.
        const val kMaxPacketCapacity = 10000
    }
}

// A network queue that uses a leaky bucket to limit the number of packets that
// can be queued.
class LeakyBucketNetworkQueue(config: Config = Config()) : NetworkQueue {
    class Config(
        val seed: Int = 1,
        // If enqueued packets are sent as ECT1 and sojourn time is larger than
        // `target_ect1_sojourn_time`, packets will be marked as CE with
        // probability (sojourn_time - `target_ect1_sojourn_time`) /
        // (`max_ect1_sojourn_time` - `target_ect1_sojourn_time`)
        val maxEct1SojournTimeUs: Long = kPlusInfinityUs,
        val targetEct1SojournTimeUs: Long = kPlusInfinityUs
    )

    private var maxPacketCapacity = NetworkQueue.kMaxPacketCapacity
    private val maxEct1SojournTimeUs = config.maxEct1SojournTimeUs
    private val targetEct1SojournTimeUs = config.targetEct1SojournTimeUs
    private val random = WebRtcRandom(config.seed.toULong())
    private val queue = ArrayDeque<PacketInFlightInfo>()
    private var droppedPackets = mutableListOf<PacketInFlightInfo>()

    init {
        require(config.targetEct1SojournTimeUs <= config.maxEct1SojournTimeUs)
    }

    // If `max_capacity` is larger than current queue length, existing packets are
    // not dropped. But the queue will not accept new packets until queue length
    // is below `max_capacity`,
    override fun setMaxPacketCapacity(maxCapacity: Int) {
        maxPacketCapacity = maxCapacity
    }

    override fun enqueuePacket(packetInfo: PacketInFlightInfo): Boolean {
        if (maxPacketCapacity <= queue.size) {
            return false
        }
        queue.addLast(packetInfo)
        return true
    }

    override fun peekNextPacket(): PacketInFlightInfo? = queue.peekFirst()

    override fun dequeuePacket(timeNowUs: Long): PacketInFlightInfo? {
        if (queue.isEmpty()) {
            return null
        }
        check(queue.first.sendTimeUs <= timeNowUs)
        val packetInfo = queue.removeFirst()
        return maybeMarkAsCe(timeNowUs, packetInfo)
    }

    private fun maybeMarkAsCe(timeNowUs: Long, packetInfo: PacketInFlightInfo): PacketInFlightInfo {
        if (packetInfo.ecn != EcnMarking.kEct1 ||
            targetEct1SojournTimeUs == kPlusInfinityUs ||
            maxEct1SojournTimeUs == kPlusInfinityUs
        ) {
            return packetInfo
        }
        val sojournTimeUs = timeNowUs - packetInfo.sendTimeUs
        val pMark = (
            (sojournTimeUs - targetEct1SojournTimeUs).toDouble() /
                (maxEct1SojournTimeUs - targetEct1SojournTimeUs).toDouble()
            ).coerceIn(0.0, 1.0)
        if (random.randDouble() < pMark) {
            return packetInfo.copy(ecn = EcnMarking.kCe)
        }
        return packetInfo
    }

    override fun empty(): Boolean = queue.isEmpty()

    fun dropOldestPacket() {
        droppedPackets.add(queue.removeFirst())
    }

    override fun dequeueDroppedPackets(): List<PacketInFlightInfo> {
        val dropped = droppedPackets
        droppedPackets = mutableListOf()
        return dropped
    }
}
