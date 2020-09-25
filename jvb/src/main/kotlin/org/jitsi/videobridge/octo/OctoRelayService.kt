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
import java.time.Clock
import java.time.Instant

/**
 * The service which is responsible for sending and receiving packets on the Octo link.  Only
 * a single instance exists, and it handles all Octo traffic.  It should _only_ be created
 * when it has been enabled in configuration.  It will throw in the constructor if it
 * is not enabled
 */
class OctoRelayService {
    /**
     * The [UdpTransport] used to send and receive Octo data
     */
    private val udpTransport: UdpTransport

    /**
     * The [BridgeOctoTransport] for handling incoming and outgoing Octo data
     */
    val bridgeOctoTransport: BridgeOctoTransport

    init {
        if (!config.enabled) {
            throw IllegalStateException("Octo relay service is not enabled")
        }

        val address = config.bindAddress
        val publicAddress = config.publicAddress
        val port = config.bindPort

        try {
            // TODO(brian): this should change to have OctoRelayService take a clock which it passes down,
            // but we need to move it away from being created via the singleton first
            udpTransport = UdpTransport(address, port, logger, OCTO_SO_RCVBUF, OCTO_SO_SNDBUF, Clock.systemUTC())
        } catch (t: Throwable) {
            when (t) {
                is UnknownHostException, is SocketException -> {
                    logger.error("Failed to initialize Octo UDP transport with " +
                            "address " + address + ":" + port + ".", t)
                }
                else -> {
                    logger.error("Error creating OctoRelayService UdpTransport", t)
                }
            }
            throw t
        }
        logger.info("Created Octo UDP transport")

        bridgeOctoTransport = BridgeOctoTransport("$publicAddress:$port", logger)

        // Wire the data coming from the UdpTransport to the OctoTransport
        udpTransport.incomingDataHandler = object : UdpTransport.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant) {
                bridgeOctoTransport.dataReceived(data, offset, length, receivedTime)
            }
        }
        // Wire the data going out of OctoTransport to UdpTransport
        bridgeOctoTransport.outgoingDataHandler = object : BridgeOctoTransport.OutgoingOctoPacketHandler {
            override fun sendData(data: ByteArray, off: Int, length: Int, remoteAddresses: Collection<SocketAddress>) {
                udpTransport.send(data, off, length, remoteAddresses)
            }
        }
    }

    fun start() {
        TaskPools.IO_POOL.submit { udpTransport.startReadingData() }
    }

    fun stop() {
        logger.info("Stopping")
        udpTransport.stop()
        bridgeOctoTransport.stop()
    }

    fun getStats(): Stats {
        val octoUdpTransportStats = udpTransport.getStats()
        val octoTransportStats = bridgeOctoTransport.getStats()
        return Stats(
            bytesReceived = octoUdpTransportStats.bytesReceived,
            bytesSent = octoUdpTransportStats.bytesSent,
            packetsReceived = octoUdpTransportStats.packetsReceived,
            packetsSent = octoUdpTransportStats.packetsSent,
            receiveBitrate = octoUdpTransportStats.receiveBitRate,
            receivePacketRate = octoUdpTransportStats.receivePacketRate,
            packetsDropped = (octoUdpTransportStats.incomingPacketsDropped) +
                (octoTransportStats.numInvalidPackets) +
                (octoTransportStats.numIncomingDroppedNoHandler),
            sendBitrate = octoUdpTransportStats.sendBitRate,
            sendPacketRate = octoUdpTransportStats.sendPacketRate,
            relayId = bridgeOctoTransport.relayId
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
