/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.octo

import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.octo.config.OctoConfig.Companion.config
import org.jitsi.videobridge.transport.octo.BridgeOctoTransport
import org.jitsi.videobridge.transport.udp.UdpTransport
import org.jitsi.videobridge.util.TaskPools
import java.net.SocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Instant

class OctoRelayService {
    /**
     * The [UdpTransport] used to send and receive Octo data
     */
    private var udpTransport: UdpTransport? = null

    /**
     * The [BridgeOctoTransport] for handling incoming and outgoing Octo data
     */
    var bridgeOctoTransport: BridgeOctoTransport? = null
        private set

    fun start() {
        if (!config.enabled) {
            logger.info("Octo relay is disabled")
            return
        }

        val address = config.bindAddress
        val publicAddress = config.publicAddress
        val port = config.bindPort

        try {
            udpTransport = UdpTransport(address, port, logger, OCTO_SO_RCVBUF, OCTO_SO_SNDBUF)
            logger.info("Created Octo UDP transport")
        } catch (e: Exception) {
            when (e) {
                is UnknownHostException, is SocketException -> {
                    logger.error("Failed to initialize Octo UDP transport with " +
                            "address " + address + ":" + port + ".", e)
                    return
                }
                else -> throw e
            }
        }
        bridgeOctoTransport = BridgeOctoTransport("$publicAddress:$port", logger)

        // Wire the data coming from the UdpTransport to the OctoTransport
        udpTransport!!.incomingDataHandler = object : UdpTransport.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant) {
                bridgeOctoTransport!!.dataReceived(data, offset, length, receivedTime)
            }
        }
        // Wire the data going out of OctoTransport to UdpTransport
        bridgeOctoTransport!!.outgoingDataHandler = object : BridgeOctoTransport.OutgoingOctoPacketHandler {
            override fun sendData(data: ByteArray, off: Int, length: Int, remoteAddresses: Collection<SocketAddress>) {
                udpTransport!!.send(data, off, length, remoteAddresses)
            }
        }
        TaskPools.IO_POOL.submit { udpTransport!!.startReadingData() }
    }

    fun stop() {
        logger.info("Stopping")
        udpTransport?.stop()
        bridgeOctoTransport?.stop()
    }

    fun getStats(): Stats {
        val octoUdpTransportStats = udpTransport?.getStats()
        val octoTransportStats = bridgeOctoTransport?.getStats()
        return Stats(
            bytesReceived = octoUdpTransportStats?.bytesReceived ?: 0,
            bytesSent = octoUdpTransportStats?.bytesSent ?: 0,
            packetsReceived = octoUdpTransportStats?.packetsReceived ?: 0,
            packetsSent = octoUdpTransportStats?.packetsSent ?: 0,
            receiveBitrate = octoUdpTransportStats?.receiveBitRate ?: 0,
            receivePacketRate = octoUdpTransportStats?.receivePacketRate ?: 0,
            packetsDropped = (octoUdpTransportStats?.incomingPacketsDropped ?: 0) +
                    (octoTransportStats?.numInvalidPackets ?: 0) +
                    (octoTransportStats?.numIncomingDroppedNoHandler ?: 0),
            sendBitrate = octoUdpTransportStats?.sendBitRate ?: 0,
            sendPacketRate = octoUdpTransportStats?.sendPacketRate ?: 0,
            relayId = bridgeOctoTransport?.relayId ?: "no relay ID"
        )
    }

    companion object {
        private val logger = LoggerImpl(OctoRelayService::class.java.name)

        /**
         * The receive buffer size for the Octo socket
         */
        private const val OCTO_SO_RCVBUF = 10 * 1024 * 1024

        /**
         * The send buffer size for the Octo socket
         */
        private const val OCTO_SO_SNDBUF = 10 * 1024 * 1024

        /**
         * The version of the octo protocol.
         */
        const val OCTO_VERSION = 1
    }

    data class Stats(
        val bytesReceived: Long,
        val bytesSent: Long,
        val packetsReceived: Long,
        val packetsSent: Long,
        val packetsDropped: Long,
        val receiveBitrate: Long,
        val receivePacketRate: Long,
        val sendBitrate: Long,
        val sendPacketRate: Long,
        val relayId: String
    )
}
