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
import org.jitsi.videobridge.octo.config.OctoConfig.Config
import org.jitsi.videobridge.transport.octo.BridgeOctoTransport
import org.jitsi.videobridge.transport.udp.UdpTransport
import org.jitsi.videobridge.util.TaskPools
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import java.net.SocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Instant

class OctoRelayService : BundleActivator {
    /**
     * The [UdpTransport] used to send and receive Octo data
     */
    private var udpTransport: UdpTransport? = null

    /**
     * The [BridgeOctoTransport] for handling incoming and outgoing Octo data
     */
    var bridgeOctoTransport: BridgeOctoTransport? = null
        private set

    override fun start(bundleContext: BundleContext) {
        if (!Config.enabled()) {
            logger.info("Octo relay is disabled")
            return
        }

        val address = Config.bindAddress()
        val publicAddress = Config.publicAddress()
        val port = Config.bindPort()

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
            override fun sendData(data: ByteArray, off: Int, length: Int, remoteAddresses: Set<SocketAddress>) {
                udpTransport!!.send(data, off, length, remoteAddresses)
            }
        }
        TaskPools.IO_POOL.submit { udpTransport!!.startReadingData() }

        bundleContext.registerService(
            OctoRelayService::class.java.name,
            this,
            null
        )
    }

    override fun stop(context: BundleContext?) {
        udpTransport?.stop()
        bridgeOctoTransport?.stop()
    }

    fun getStats(): Stats {
        val octoUdpTransportStats = udpTransport!!.getStats()
        val octoTransportStats = bridgeOctoTransport!!.getStats()
        return Stats(
            bytesReceived = octoUdpTransportStats.bytesReceived,
            bytesSent = octoUdpTransportStats.bytesSent,
            packetsReceived = octoUdpTransportStats.packetsReceived,
            packetsSent = octoUdpTransportStats.packetsSent,
            receiveBitrate = octoUdpTransportStats.receiveBitRate,
            receivePacketRate = octoUdpTransportStats.receivePacketRate,
            packetsDropped = octoUdpTransportStats.incomingPacketsDropped +
                    octoTransportStats.numInvalidPackets +
                    octoTransportStats.numIncomingDroppedNoHandler,
            sendBitrate = octoUdpTransportStats.sendBitRate,
            sendPacketRate = octoUdpTransportStats.sendPacketRate,
            relayId = bridgeOctoTransport!!.relayId
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
