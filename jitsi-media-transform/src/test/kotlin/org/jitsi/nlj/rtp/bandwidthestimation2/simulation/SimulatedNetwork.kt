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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

/** Test scenario simulated network,
 * based on WebRTC call/simulated_network.{h,cc} and
 * api/test/simulated_network.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */


// BuiltInNetworkBehaviorConfig is a built-in network behavior configuration
// for built-in network behavior that will be used by WebRTC if no custom
// NetworkBehaviorInterface is provided.
class BuiltInNetworkBehaviorConfig(
    //  Queue length in number of packets.
    var queueLengthPackets: Long = 0,
    // Delay in addition to capacity induced delay.
    var queueDelayMs: Int = 0,
    // Standard deviation of the extra delay.
    var delayStandardDeviationMs: Int = 0,
    // Link capacity in kbps.
    var linkCapacityKbps: Int = 0,
    // Random packet loss.
    var lossPercent: Int = 0,
    // If packets are allowed to be reordered.
    var allowReordering: Boolean = false,
    // The average length of a burst of lost packets.
    var avgBurstLossLength: Int = -1,
    // Additional bytes to add to packet size.
    var packetOverhead: Int = 0
)

interface NetworkBehaviorInterface {

}

interface SimulatedNetworkInterface: NetworkBehaviorInterface {
    // Pauses the network until `until_us`. This affects both delivery (calling
    // DequeueDeliverablePackets before `until_us` results in an empty std::vector
    // of packets) and capacity (the network is paused, so packets are not
    // flowing and they will restart flowing at `until_us`).
    fun pauseTransmissionUntil(untilUs: Long)
}

class SimulatedNetwork(
    config: BuiltInNetworkBehaviorConfig,
    randomSeed: Long = 1
): SimulatedNetworkInterface {
    private val configLock = Any()
    private val configState = ConfigState()

    init { setConfig(config) }

    fun setConfig(config: BuiltInNetworkBehaviorConfig) {
        synchronized(configLock) {
            configState.config = config
            val probLoss = config.lossPercent / 100.0
            if (configState.config.avgBurstLossLength == -1) {
                // Uniform loss
                configState.probLossBursting = probLoss
                configState.probStartBursting = probLoss
            } else {
                // Lose packets according to a gilbert-elliot model.
                val avgBurstLossLength = config.avgBurstLossLength
                val minAvgBurstLossLength = Math.ceil(probLoss / (1 - probLoss))

                assert(avgBurstLossLength > minAvgBurstLossLength) {
                    "For a total packet loss of ${config.lossPercent}% " +
                        "then avgBurstLossLength must be ${minAvgBurstLossLength + 1} or higher."
                }

                configState.probLossBursting = (1.0 - 1.0 / avgBurstLossLength)
                configState.probStartBursting = probLoss / (1 - probLoss) / avgBurstLossLength
            }
        }
    }

    override fun pauseTransmissionUntil(until: Long) {
        synchronized(configLock) {
            configState.pauseTransmissionUntilUs = until
        }
    }

    private class ConfigState {
        // Static link configuration.
        var config = BuiltInNetworkBehaviorConfig()
        // The probability to drop the packet if we are currently dropping a
        // burst of packet
        var probLossBursting: Double = 0.0
        // The probability to drop a burst of packets.
        var probStartBursting: Double = 0.0
        // Used for temporary delay spikes.
        var pauseTransmissionUntilUs: Long = 0
    }
}