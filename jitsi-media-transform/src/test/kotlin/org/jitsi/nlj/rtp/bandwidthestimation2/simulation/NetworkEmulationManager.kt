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

import java.net.Inet4Address
import java.net.InetAddress

/** Test scenario network emulation manager,
 * based on WebRTC api/test/network_emulation_manager.{h,cc}
 * and test/network/network_emulation_manager.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

enum class TimeMode { kRealTime, kSimulated }

class EmulatedEndpointConfig(
    val name: String? = null,
    val ip: InetAddress? = null,
    val startAsEnabled: Boolean = true
)

interface NetworkEmulationManager {
    fun timeController(): TimeController

    // Creates an emulated network node, which represents single network in
    // the emulated network layer. Uses default implementation on network behavior
    // which can be configured with `config`. `random_seed` can be provided to
    // alter randomization behavior.
    fun createEmulatedNode(config: BuiltInNetworkBehaviorConfig, randomSeed: Long = 1): EmulatedNetworkNode

    // Creates an emulated network node, which represents single network in
    // the emulated network layer. `network_behavior` determines how created node
    // will forward incoming packets to the next receiver.
    fun createEmulatedNode(networkBehavior: NetworkBehaviorInterface): EmulatedNetworkNode

    // Creates an emulated endpoint, which represents single network interface on
    // the peer's device.
    fun createEndpoint(config: EmulatedEndpointConfig): EmulatedEndpoint

    // Creates a route between endpoints going through specified network nodes.
    // This route is single direction only and describe how traffic that was
    // sent by network interface `from` have to be delivered to the network
    // interface `to`. Return object can be used to remove created route. The
    // route must contains at least one network node inside it.
    //
    // Assume that E{0-9} are endpoints and N{0-9} are network nodes, then
    // creation of the route have to follow these rules:
    //   1. A route consists of a source endpoint, an ordered list of one or
    //      more network nodes, and a destination endpoint.
    //   2. If (E1, ..., E2) is a route, then E1 != E2.
    //      In other words, the source and the destination may not be the same.
    //   3. Given two simultaneously existing routes (E1, ..., E2) and
    //      (E3, ..., E4), either E1 != E3 or E2 != E4.
    //      In other words, there may be at most one route from any given source
    //      endpoint to any given destination endpoint.
    //   4. Given two simultaneously existing routes (E1, ..., N1, ..., E2)
    //      and (E3, ..., N2, ..., E4), either N1 != N2 or E2 != E4.
    //      In other words, a network node may not belong to two routes that lead
    //      to the same destination endpoint.
    fun createRoute(
        from: EmulatedEndpoint,
        viaNodes: List<EmulatedNetworkNode>,
        to: EmulatedEndpoint
    ): EmulatedRoute;

    // Creates a route over the given `via_nodes` creating the required endpoints
    // in the process. The returned EmulatedRoute pointer can be used in other
    // calls as a transport route for message or cross traffic.
    fun createRoute(
        viaNodes: List<EmulatedNetworkNode>
    ): EmulatedRoute
}

class NetworkEmulationManagerImpl() : NetworkEmulationManager {
    override fun timeController(): TimeController = timeController

    override fun createEmulatedNode(config: BuiltInNetworkBehaviorConfig, randomSeed: Long): EmulatedNetworkNode {
        return createEmulatedNode(SimulatedNetwork(config, randomSeed))
    }

    override fun createEmulatedNode(networkBehavior: NetworkBehaviorInterface): EmulatedNetworkNode {
        val node = EmulatedNetworkNode()
        networkNodes.add(node)
        return node
    }

    override fun createEndpoint(config: EmulatedEndpointConfig): EmulatedEndpoint {
        val ip = getNextIpv4Address()
        check(ip != null) {
            "All IPv4 addresses exhausted"
        }
        val res = usedIpAddresses.add(ip)
        check (res) {
            "IP address $ip already in use"
        }
        val node = EmulatedEndpointImpl(EmulatedEndpointImpl.Options(
            id = nextNodeId++,
            ip = ip,
            config = config
        ))
        endpoints.add(node)
        return node
    }

    override fun createRoute(viaNodes: List<EmulatedNetworkNode>): EmulatedRoute {
        val from = createEndpoint(EmulatedEndpointConfig())
        val to = createEndpoint(EmulatedEndpointConfig())
        return createRoute(from, viaNodes, to)
    }

    override fun createRoute(
        from: EmulatedEndpoint,
        viaNodes: List<EmulatedNetworkNode>,
        to: EmulatedEndpoint
    ): EmulatedRoute {
        TODO("Not yet implemented")
    }

    private fun getNextIpv4Address(): InetAddress? {
        val addressesCount = (kMaxIPv4Address - kMinIPv4Address).toInt()
        repeat(addressesCount) {
            val ip = nextIpv4Address.toInetAddress()
            if (nextIpv4Address == kMaxIPv4Address) {
                nextIpv4Address = kMinIPv4Address
            } else {
                nextIpv4Address++
            }
            if (!usedIpAddresses.contains(ip)) {
                return ip
            }
        }
        return null
    }

    private var nextNodeId = 1L

    private var nextIpv4Address = kMinIPv4Address
    private val usedIpAddresses = mutableSetOf<InetAddress>()

    private val timeController = GlobalSimulatedTimeController()

    private val endpoints = mutableListOf<EmulatedEndpoint>()
    private val networkNodes = mutableListOf<EmulatedNetworkNode>()

    companion object {
        // uint32_t representation of 192.168.0.0 address
        const val kMinIPv4Address = 0xC0A80000
        // uint32_t representation of 192.168.255.255 address
        const val kMaxIPv4Address = 0xC0A8FFFF
    }
}

private fun Long.toInetAddress(): InetAddress {
    val bytes = byteArrayOf(
        (this shl 24).toByte(),
        (this shl 16).toByte(),
        (this shl 8).toByte(),
        this.toByte())
    return InetAddress.getByAddress(bytes)
}