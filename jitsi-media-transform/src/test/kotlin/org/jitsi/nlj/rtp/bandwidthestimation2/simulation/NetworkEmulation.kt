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

import org.jitsi.utils.logging2.createLogger
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.ScheduledExecutorService

/** Test scenario network emulator,
 * based on WebRTC test/network/network_emulation.{h,cc} in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

class EmulatedNetworkNode(
    val clock: Clock,
    val taskQueue: ScheduledExecutorService
) : EmulatedNetworkReceiverInterface {
    val router = NetworkRouterNode(taskQueue)
}

class NetworkRouterNode(val taskQueue: ScheduledExecutorService) : EmulatedNetworkReceiverInterface {
    private val routing = mutableMapOf<InetAddress, EmulatedNetworkReceiverInterface>()

    fun setReceiver(destIp: InetAddress, receiver: EmulatedNetworkReceiverInterface) {
        // TODO run on task queue?
        val curReceiver = routing[destIp]
        check(curReceiver == null || curReceiver == receiver) {
            "Routing for destIp=$destIp already exists"
        }
        routing[destIp] = receiver
    }
}

class EmulatedEndpointImpl(
    val options: Options,
    val taskQueue: ScheduledExecutorService
) : EmulatedEndpoint {
    private val logger = createLogger()

    private val receiverLock = Any()

    private var nextPort = kFirstEphemeralPort

    private val portToReceiver = mutableMapOf<Short, ReceiverBinding>()

    val router = NetworkRouterNode(taskQueue)

    override fun bindReceiver(desiredPort: Short, receiver: EmulatedNetworkReceiverInterface): Short? {
        return bindReceiverInternal(desiredPort, receiver, isOneShot = false)
    }

    private fun bindReceiverInternal(
        desiredPort: Short,
        receiver: EmulatedNetworkReceiverInterface,
        isOneShot: Boolean
    ): Short? {
        synchronized(receiverLock) {
            var port = desiredPort
            if (port == 0.toShort()) {
                // Because client can specify its own port, next_port_ can be already in
                // use, so we need to find next available port.
                val portsPoolSize = (UShort.MAX_VALUE - kFirstEphemeralPort.toUShort() + 1U).toInt()
                for (i in 0..portsPoolSize) {
                    val nextPort = nextPort()
                    if (!portToReceiver.containsKey(nextPort)) {
                        port = nextPort
                        break
                    }
                }
            }
            assert(port != 0.toShort()) {
                "Can't find free port for receiver in endpoint ${options.logName}; id = ${options.id}"
            }
            val result = portToReceiver.put(port, ReceiverBinding(receiver, isOneShot))
            if (result != null) {
                logger.info(
                    "Can't bind receiver to used port ${port.toUShort()} in endpoint ${options.logName}; " +
                        "id=${options.id}"
                )
                return null
            }
            logger.info(
                "New receiver is binded to endpoint ${options.logName}; id=${options.id} " +
                    "on port ${port.toUShort()}"
            )
            return port
        }
    }

    override fun getPeerLocalAddress(): InetAddress {
        return options.ip
    }

    private fun nextPort(): Short {
        val out = nextPort
        if (nextPort.toUShort() == UShort.MAX_VALUE) {
            nextPort = kFirstEphemeralPort
        } else {
            nextPort++
        }
        return out
    }

    class Options(
        // TODO(titovartem) check if we can remove id.
        val id: Long,
        // Endpoint local IP address.
        val ip: InetAddress,
        config: EmulatedEndpointConfig
    ) {
        // Name of the endpoint used for logging purposes
        val logName = "$ip (${config.name ?: ""})"
    }

    private class ReceiverBinding(
        val receiver: EmulatedNetworkReceiverInterface,
        val isOneShot: Boolean
    )

    companion object {
        private val kFirstEphemeralPort = 49152.toShort()
    }
}

class EmulatedRoute(
    val from: EmulatedEndpointImpl,
    val viaNodes: List<EmulatedNetworkNode>,
    val to: EmulatedEndpointImpl,
    val isDefault: Boolean
) {
    var active = true
}
