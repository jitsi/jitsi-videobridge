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

package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.toDouble
import org.jitsi.nlj.util.toEpochMicro
import org.jitsi.nlj.util.toRoundedMillis
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import kotlin.math.roundToInt

/** Test scenario network nodes,
 * based on WebRTC test/scenario/network_node.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

fun createSimulationConfig(config: NetworkSimulationConfig): BuiltInNetworkBehaviorConfig {
    val simConfig = BuiltInNetworkBehaviorConfig()
    simConfig.linkCapacityKbps = config.bandwidth?.kbps?.roundToInt() ?: 0
    simConfig.lossPercent = (config.lossRate * 100).toInt()
    simConfig.queueDelayMs = config.delay.toRoundedMillis().toInt()
    simConfig.delayStandardDeviationMs = config.delayStdDev.toRoundedMillis().toInt()
    simConfig.packetOverhead = config.packetOverhead.bytes.roundToInt()
    simConfig.queueLengthPackets = config.packetQueueLengthLimit?.toLong() ?: 0L
    return simConfig
}

class SimulationNode(
    private val config: NetworkSimulationConfig,
    private val simulation: SimulatedNetwork,
    private val networkNode: EmulatedNetworkNode
) {
    fun updateConfig(modifier: (NetworkSimulationConfig) -> Unit) {
        modifier(config)
        val simConfig = createSimulationConfig(config)
        simulation.setConfig(simConfig)
    }

    fun pauseTransmissionUntil(until: Instant) {
        simulation.pauseTransmissionUntil(until.toEpochMicro())
    }

    fun configPrinter(): ColumnPrinter {
        return ColumnPrinter("propagation_delay capacity loss_rate", { sb ->
            sb.append(
                String.format(
                    "%.3lf %.0lf %.2lf",
                    config.delay.toDouble(),
                    config.bandwidth.bps / 8.0,
                    config.lossRate
                )
            )
        })
    }

    fun node(): EmulatedNetworkNode = networkNode

    companion object {
        fun createBehavior(config: NetworkSimulationConfig): SimulatedNetwork {
            val simConfig = createSimulationConfig(config)
            return SimulatedNetwork(simConfig)
        }
    }
}

class NetworkNodeTransport(
    val senderClock: Clock,

) {
    private val mutex = Any()
    private var endpoint: EmulatedEndpoint? = null
    private var localAddress: InetSocketAddress = InetSocketAddress(0)
    private var remoteAddress: InetSocketAddress = InetSocketAddress(0)
    private var currentNetworkRoute: NetworkRoute = NetworkRoute()

    fun connect(endpoint: EmulatedEndpoint, receiverAddress: InetSocketAddress, packetOverhead: DataSize) {
        val route = NetworkRoute()
        route.connected = true
        // We assume that the address will be unique in the lower bytes.
        route.local =
            RouteEndpoint.createWithNetworkId(receiverAddress.address.v4AddressAsHostOrderInteger().toShort())
        route.remote =
            RouteEndpoint.createWithNetworkId(receiverAddress.address.v4AddressAsHostOrderInteger().toShort())
        route.packetOverhead = packetOverhead.bytes.toInt() +
            receiverAddress.address.overhead() +
            kUdpHeaderSize

        synchronized(mutex) {
            assert(receiverAddress.address is Inet4Address)
            this.endpoint = endpoint
            localAddress = InetSocketAddress(endpoint.getPeerLocalAddress(), 0)
            remoteAddress = receiverAddress
            currentNetworkRoute = route
        }

        /* TODO: invoke OnNetworkRouteChanged? */
    }
}

fun InetAddress.v4AddressAsHostOrderInteger(): Int {
    return if (this is Inet4Address) {
        hashCode()
    } else {
        0
    }
}

fun InetAddress.overhead(): Int {
    return when (this) {
        is Inet4Address -> 20
        is Inet6Address -> 40
        else -> 0
    }
}

/* Defined in rtc_base/net_helper.h */
const val kUdpHeaderSize = 8
