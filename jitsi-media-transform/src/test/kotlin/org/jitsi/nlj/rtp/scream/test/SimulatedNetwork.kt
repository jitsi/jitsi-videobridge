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
package org.jitsi.nlj.rtp.scream.test

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Simulated network,
 * based on WebRTC test/network/simulated_network.{h,cc} and api/test/simulated_network.h in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Times are in microseconds, as in the interfaces of WebRTC. kPlusInfinityUs and kMinusInfinityUs stand for the
 * infinite values of its Timestamp. There is no locking: the tests are single threaded.
 */

// BuiltInNetworkBehaviorConfig is a built-in network behavior configuration
// for built-in network behavior that will be used by WebRTC if no custom
// NetworkBehaviorInterface is provided.
data class BuiltInNetworkBehaviorConfig(
    //  Queue length in number of packets.
    val queueLengthPackets: Int = 0,
    // Delay in addition to capacity induced delay.
    val queueDelayMs: Int = 0,
    // Standard deviation of the extra delay.
    val delayStandardDeviationMs: Int = 0,
    // Link capacity.
    val linkCapacity: Bandwidth = Bandwidth.INFINITY,
    // Random packet loss, range 0 to 100.
    val lossPercent: Double = 0.0,
    // If packets are allowed to be reordered.
    val allowReordering: Boolean = false,
    // The average length of a burst of lost packets.
    val avgBurstLossLength: Int = -1,
    // Additional bytes to add to packet size.
    val packetOverhead: Int = 0,
    // Forward ECN from previous nodes in the network. If false, ECN bits
    // are set to not ECT. See https://www.rfc-editor.org/rfc/rfc9331.html.
    val forwardEcn: Boolean = true
)

// Calculate the time that it takes to send N `bits` on a
// network with link capacity equal to `capacity_kbps` starting at time
// `start_time`.
private fun calculateArrivalTime(startTimeUs: Long, bits: Long, capacity: Bandwidth): Long {
    if (capacity.isInfinite()) {
        return startTimeUs
    }
    if (capacity == Bandwidth.ZERO) {
        return kPlusInfinityUs
    }

    // Adding `capacity - 1` to the numerator rounds the extra delay caused by
    // capacity constraints up to an integral microsecond. Sending 0 bits takes 0
    // extra time, while sending 1 bit gets rounded up to 1 (the multiplication by
    // 1000 is because capacity is in kbps).
    // The factor 1000 comes from 10^6 / 10^3, where 10^6 is due to the time unit
    // being us and 10^3 is due to the rate unit being kbps.
    val kbps = capacity.toRoundedKbps()
    return startTimeUs + (1000 * bits + kbps - 1) / kbps
}

// Class simulating a network link. This is a simple and naive solution just
// faking capacity and adding an extra transport delay in addition to the
// capacity introduced delay.
class SimulatedNetwork(
    config: BuiltInNetworkBehaviorConfig,
    randomSeed: ULong = 1uL,
    private val queue: NetworkQueue = LeakyBucketNetworkQueue()
) {
    private class PacketInfo(
        val packet: PacketInFlightInfo,
        var lastUpdateTimeUs: Long,
        var bitsLeftToSend: Long,
        var arrivalTimeUs: Long
    )

    private class ConfigState {
        var config = BuiltInNetworkBehaviorConfig()
        var probLossBursting = 0.0
        var probStartBursting = 0.0
        var pauseTransmissionUntilUs = 0L
    }

    private var capacityLink: PacketInfo? = null
    private val delayLink = ArrayDeque<PacketInfo>()
    private var nextProcessTimeUs = kPlusInfinityUs
    private var nextProcessTimeChangedCallback: (() -> Unit)? = null
    private val configState = ConfigState()
    private val random = WebRtcRandom(randomSeed)
    private var bursting = false

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private var lastEnqueueTimeUs = 0L
    private var lastCapacityLinkExitTimeUs = kMinusInfinityUs

    init {
        setConfig(config)
    }

    // Sets a new configuration.
    fun setConfig(config: BuiltInNetworkBehaviorConfig) {
        queue.setMaxPacketCapacity(
            if (config.queueLengthPackets > 0) {
                // -1 to account for the packet in the capacity link.
                config.queueLengthPackets - 1
            } else {
                NetworkQueue.kMaxPacketCapacity
            }
        )

        configState.config = config
        val probLoss = config.lossPercent / 100.0
        if (configState.config.avgBurstLossLength == -1) {
            // Uniform loss
            configState.probLossBursting = probLoss
            configState.probStartBursting = probLoss
        } else {
            // Lose packets according to a gilbert-elliot model.
            val avgBurstLossLength = config.avgBurstLossLength
            val minAvgBurstLossLength = ceil(probLoss / (1 - probLoss)).toInt()

            check(avgBurstLossLength > minAvgBurstLossLength) {
                "For a total packet loss of ${config.lossPercent}% then avg_burst_loss_length must be " +
                    "${minAvgBurstLossLength + 1} or higher."
            }

            configState.probLossBursting = (1.0 - 1.0 / avgBurstLossLength)
            configState.probStartBursting = probLoss / (1 - probLoss) / avgBurstLossLength
        }
    }

    fun setConfig(newConfig: BuiltInNetworkBehaviorConfig, configUpdateTimeUs: Long) {
        capacityLink?.let { capacityLink ->
            // Calculate and update how large portion of the packet first in the
            // capacity link is left to to send at time `config_update_time`.
            val currentConfig = configState.config
            val durationWithCurrentConfigUs = configUpdateTimeUs - capacityLink.lastUpdateTimeUs
            check(durationWithCurrentConfigUs >= 0)
            // TimeDelta::ms() rounds to the nearest millisecond.
            val durationWithCurrentConfigMs = Math.floorDiv(durationWithCurrentConfigUs + 500, 1000L)
            capacityLink.bitsLeftToSend -= min(
                durationWithCurrentConfigMs * currentConfig.linkCapacity.toRoundedKbps(),
                capacityLink.bitsLeftToSend
            )
            capacityLink.lastUpdateTimeUs = configUpdateTimeUs
        }
        setConfig(newConfig)
        updateCapacityLink(configState, configUpdateTimeUs)

        if (updateNextProcessTime()) {
            nextProcessTimeChangedCallback?.invoke()
        }
    }

    // Pauses the network until `until_us`. This affects both delivery (calling
    // DequeueDeliverablePackets before `until_us` results in an empty std::vector
    // of packets) and capacity (the network is paused, so packets are not
    // flowing and they will restart flowing at `until_us`).
    fun pauseTransmissionUntil(untilUs: Long) {
        configState.pauseTransmissionUntilUs = untilUs
    }

    // Enqueues a packet in the network and returns true if the action was
    // successful, false otherwise (for example, because the network capacity has
    // been saturated). If the return value is false, the packet should be
    // considered as dropped and it will not be returned by future calls
    // to DequeueDeliverablePackets.
    // Packets enqueued will exit the network when DequeueDeliverablePackets is
    // called and enough time has passed (see NextDeliveryTimeUs).
    fun enqueuePacket(packetInfo: PacketInFlightInfo): Boolean {
        lastEnqueueTimeUs = packetInfo.sendTimeUs

        // If the network config requires packet overhead, let's apply it as early as
        // possible.
        val packet = packetInfo.copy(size = packetInfo.size + configState.config.packetOverhead)

        val enqueueTimeUs = packet.sendTimeUs
        val packetEnqueued = queue.enqueuePacket(packet)
        // A packet can not enter the narrow section before the last packet has exit.
        if (capacityLink != null) {
            // A packet is already in the capacity link. Wait until it exits.
            return packetEnqueued
        }
        var nextPacket = packet
        if (!queue.empty()) {
            nextPacket = queue.dequeuePacket(enqueueTimeUs)!!
        }
        val arrivalTimeUs = calculateArrivalTime(
            max(nextPacket.sendTimeUs, lastCapacityLinkExitTimeUs),
            packet.size * 8,
            configState.config.linkCapacity
        )

        capacityLink = PacketInfo(
            packet = nextPacket,
            lastUpdateTimeUs = enqueueTimeUs,
            bitsLeftToSend = 8 * nextPacket.size,
            arrivalTimeUs = arrivalTimeUs
        )

        // Only update `next_process_time_` if not already set. Otherwise,
        // next_process_time_ is calculated when a packet is dequeued. Note that this
        // means that the newly enqueued packet risk having an arrival time before
        // `next_process_time_` if packet reordering is allowed and
        // config.delay_standard_deviation_ms is set.
        // TODO(bugs.webrtc.org/14525): Consider preventing this.
        if (nextProcessTimeUs == kPlusInfinityUs && arrivalTimeUs != kPlusInfinityUs) {
            nextProcessTimeUs = arrivalTimeUs
        }
        return true
    }

    // Returns time in microseconds when caller should call
    // DequeueDeliverablePackets to get the next set of delivered packets. It is
    // possible that no packet will be delivered by that time (e.g. in case of
    // random extra delay), in such case this method should be called again to get
    // the updated estimated delivery time.
    fun nextDeliveryTimeUs(): Long? = if (nextProcessTimeUs != kPlusInfinityUs) nextProcessTimeUs else null

    private fun updateCapacityLink(state: ConfigState, timeNowUs: Long) {
        capacityLink?.let { capacityLink ->
            // Recalculate the arrival time of the packet currently in the capacity link
            // since it may have changed if the capacity has changed.
            capacityLink.lastUpdateTimeUs = max(capacityLink.lastUpdateTimeUs, lastCapacityLinkExitTimeUs)
            capacityLink.arrivalTimeUs = calculateArrivalTime(
                capacityLink.lastUpdateTimeUs,
                capacityLink.bitsLeftToSend,
                state.config.linkCapacity
            )
        }

        if (capacityLink == null || timeNowUs < capacityLink!!.arrivalTimeUs) {
            return
        }
        var reorderPackets = false

        do {
            // Time to get this packet (the original or just updated arrival_time is
            // smaller or equal to time_now_us).
            val packet = capacityLink!!
            check(packet.arrivalTimeUs != kPlusInfinityUs && packet.arrivalTimeUs != kMinusInfinityUs)
            capacityLink = null

            // If the network is paused, the pause will be implemented as an extra delay
            // to be spent in the `delay_link_` queue.
            if (state.pauseTransmissionUntilUs > packet.arrivalTimeUs) {
                packet.arrivalTimeUs = state.pauseTransmissionUntilUs
            }

            // Store the original arrival time, before applying packet loss or extra
            // delay. This is needed to know when it is possible for the next packet
            // in the queue to start transmitting.
            lastCapacityLinkExitTimeUs = packet.arrivalTimeUs

            // Drop packets at an average rate of `state.config.loss_percent` with
            // and average loss burst length of `state.config.avg_burst_loss_length`.
            if ((bursting && random.randDouble() < state.probLossBursting) ||
                (!bursting && random.randDouble() < state.probStartBursting)
            ) {
                bursting = true
                packet.arrivalTimeUs = kMinusInfinityUs
            } else {
                // If packets are not dropped, apply extra delay as configured.
                bursting = false
                var arrivalTimeJitterUs = max(
                    random.gaussian(
                        (state.config.queueDelayMs * 1000).toDouble(),
                        (state.config.delayStandardDeviationMs * 1000).toDouble()
                    ),
                    0.0
                ).toLong()

                // If reordering is not allowed then adjust arrival_time_jitter
                // to make sure all packets are sent in order.
                val lastArrivalTimeUs = if (delayLink.isEmpty()) kMinusInfinityUs else delayLink.last.arrivalTimeUs
                if (!state.config.allowReordering && !delayLink.isEmpty() &&
                    packet.arrivalTimeUs + arrivalTimeJitterUs < lastArrivalTimeUs
                ) {
                    arrivalTimeJitterUs = lastArrivalTimeUs - packet.arrivalTimeUs
                }
                packet.arrivalTimeUs += arrivalTimeJitterUs

                // Optimization: Schedule a reorder only when a packet will exit before
                // the one in front.
                if (lastArrivalTimeUs > packet.arrivalTimeUs) {
                    reorderPackets = true
                }
            }
            delayLink.addLast(packet)

            // If there are no packets in the queue, there is nothing else to do.
            val peekPacket = queue.peekNextPacket() ?: break
            // It is possible that the next packet in the queue has a send time (at
            // least in tests) after the previous packet left the capacity link.
            val nextStartUs = max(lastCapacityLinkExitTimeUs, peekPacket.sendTimeUs)
            val nextPacket = queue.dequeuePacket(nextStartUs)!!
            capacityLink = PacketInfo(
                packet = nextPacket,
                lastUpdateTimeUs = nextStartUs,
                bitsLeftToSend = 8 * nextPacket.size,
                arrivalTimeUs = calculateArrivalTime(nextStartUs, nextPacket.size * 8, state.config.linkCapacity)
            )
            // And if the next packet in the queue needs to exit, let's dequeue it.
        } while (capacityLink!!.arrivalTimeUs <= timeNowUs)

        if (state.config.allowReordering && reorderPackets) {
            // Packets arrived out of order and since the network config allows
            // reordering, let's sort them per arrival_time to make so they will also
            // be delivered out of order.
            val sorted = delayLink.sortedBy { it.arrivalTimeUs }
            delayLink.clear()
            delayLink.addAll(sorted)
        }
    }

    // Retrieves all packets that should be delivered by the given receive time.
    // Not all the packets in the returned list are actually delivered.
    // In order to know the state of each packet it is necessary to check the
    // `receive_time_us` field of each packet. If that is set to
    // PacketDeliveryInfo::kNotReceived then the packet is considered lost in the
    // network.
    fun dequeueDeliverablePackets(receiveTimeUs: Long): List<PacketDeliveryInfo> {
        updateCapacityLink(configState, receiveTimeUs)
        val packetsToDeliver = mutableListOf<PacketDeliveryInfo>()

        for (packet in queue.dequeueDroppedPackets()) {
            packetsToDeliver.add(PacketDeliveryInfo(packet, PacketDeliveryInfo.kNotReceived))
        }

        // Check the extra delay queue.
        while (!delayLink.isEmpty() && receiveTimeUs >= delayLink.first.arrivalTimeUs) {
            val packetInfo = delayLink.removeFirst()
            packetsToDeliver.add(
                PacketDeliveryInfo(
                    packetInfo.packet,
                    if (packetInfo.arrivalTimeUs != kMinusInfinityUs && packetInfo.arrivalTimeUs != kPlusInfinityUs) {
                        packetInfo.arrivalTimeUs
                    } else {
                        PacketDeliveryInfo.kNotReceived
                    }
                )
            )
        }
        // There is no need to invoke `next_process_time_changed_callback_` here since
        // it is expected that the user of NetworkBehaviorInterface calls
        // NextDeliveryTimeUs after DequeueDeliverablePackets. See
        // NetworkBehaviorInterface.
        updateNextProcessTime()

        if (!configState.config.forwardEcn) {
            for (packet in packetsToDeliver) {
                packet.ecn = EcnMarking.kNotEct
            }
        }
        return packetsToDeliver
    }

    private fun updateNextProcessTime(): Boolean {
        val nextProcessTime = nextProcessTimeUs

        nextProcessTimeUs = kPlusInfinityUs
        for (packet in delayLink) {
            if (packet.arrivalTimeUs != kMinusInfinityUs && packet.arrivalTimeUs != kPlusInfinityUs) {
                nextProcessTimeUs = packet.arrivalTimeUs
                break
            }
        }
        if (nextProcessTimeUs == kPlusInfinityUs && capacityLink != null) {
            nextProcessTimeUs = capacityLink!!.arrivalTimeUs
        }
        return nextProcessTime != nextProcessTimeUs
    }

    // Registers a callback that should be triggered by an implementation if the
    // next NextDeliveryTimeUs() has changed between a call to NextDeliveryTimeUs
    // and DequeueDeliverablePackets.
    fun registerDeliveryTimeChangedCallback(callback: () -> Unit) {
        nextProcessTimeChangedCallback = callback
    }
}
