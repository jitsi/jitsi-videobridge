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

package org.jitsi.videobridge.transport.udp

import org.ice4j.socket.SocketClosedException
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.stats.RateStatistics
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A transport which allows sending and receiving data over UDP.  The transport
 * will bind to [bindPort] on [bindAddress] and will listen for incoming
 * packets there via [startReadingData] until the transport is stopped. Sending
 * can be done via [send], and a remote address (or a set of remote addresses)
 * must be provided.
 */
class UdpTransport @JvmOverloads @Throws(SocketException::class, UnknownHostException::class) constructor(
    private val bindAddress: String,
    private val bindPort: Int,
    parentLogger: Logger,
    soRcvBuf: Int? = null,
    soSndBuf: Int? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createChildLogger(parentLogger, mapOf(
        "address" to bindAddress,
        "port" to bindPort.toString()
    ))

    private val running = AtomicBoolean(true)

    private val socket: DatagramSocket = DatagramSocket(
        InetSocketAddress(InetAddress.getByName(bindAddress), bindPort)
    ).apply {
        soRcvBuf?.let { receiveBufferSize = it }
        soSndBuf?.let { sendBufferSize = it }
    }.also { socket ->
        logger.info("Initialized with bind address $bindAddress and bind port $bindPort. " +
                "Receive buffer size ${socket.receiveBufferSize}${soRcvBuf?.let { " (asked for $it)"} ?: ""}. " +
                "Send buffer size ${socket.sendBufferSize}${soSndBuf?.let { " (asked for $it)"} ?: ""}.")
    }

    val stats = Stats()

    var incomingDataHandler: IncomingDataHandler? = null

    /**
     * Read data for as long as this transport is still running.  Received data
     * is passed to the set [IncomingDataHandler].
     */
    fun startReadingData() {
        val buf = ByteArray(1500)
        val packet = DatagramPacket(buf, 0, 1500)
        while (running.get()) {
            // TODO: needed?
            packet.setData(buf, 0, 1500)
            try {
                socket.receive(packet)
                clock.instant().also { now ->
                    stats.packetReceived(packet.length, now)
                    incomingDataHandler?.dataReceived(buf, packet.offset, packet.length, now) ?: stats.incomingPacketDropped()
                }
            } catch (sce: SocketClosedException) {
                logger.info("Socket closed, stopping reader")
                break
            } catch (e: IOException) {
                logger.warn("Exception while reading ", e)
            }
        }
    }

    /**
     * Send data out via this transport to [remoteAddress]. Does not take ownership
     * of the given buffer.
     */
    fun send(data: ByteArray, off: Int, length: Int, remoteAddress: SocketAddress) {
        if (!running.get()) {
            stats.outgoingPacketsDropped()
            return
        }
        try {
            socket.send(DatagramPacket(data, off, length, remoteAddress).apply { socketAddress = remoteAddress })
            stats.packetSent(length, clock.instant())
        } catch (t: Throwable) {
            logger.warn("Error sending data", t)
        }
    }

    /**
     * Send data out via this transport to [remoteAddresses]. Does not take ownership
     * of the given buffer.
     */
    fun send(data: ByteArray, off: Int, length: Int, remoteAddresses: Set<SocketAddress>) {
        remoteAddresses.forEach { send(data, off, length, it) }
    }

    /**
     * Stop this transport.  It will stop receiving from the socket (and close
     * it) and will no longer send data
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            socket.close()
        }
    }

    /**
     * Get the stats for this transport
     */
    fun getStatsJson(): OrderedJsonObject = OrderedJsonObject().apply {
        put("bind_address", bindAddress)
        put("bind_port", bindPort)
        putAll(stats.toJson())
    }

    class Stats {
        var packetsReceived: Int = 0
            private set
        var bytesReceived: Int = 0
            private set
        var incomingPacketsDropped: Int = 0
            private set
        var packetsSent: Int = 0
            private set
        var bytesSent: Int = 0
            private set
        var outgoingPacketsDropped: Int = 0
        val receivePacketRate: RateStatistics = RateStatistics(RATE_INTERVAL, SCALE)
        val receiveBitRate: RateStatistics = RateStatistics(RATE_INTERVAL)
        val sendPacketRate: RateStatistics = RateStatistics(RATE_INTERVAL, SCALE)
        val sendBitRate: RateStatistics = RateStatistics(RATE_INTERVAL)

        fun packetReceived(numBytes: Int, time: Instant) {
            packetsReceived++
            bytesReceived += numBytes
            time.toEpochMilli().also { timeMs ->
                receivePacketRate.update(1, timeMs)
                receiveBitRate.update(numBytes, timeMs)
            }
        }

        fun packetSent(numBytes: Int, time: Instant) {
            packetsSent++
            bytesSent += numBytes
            time.toEpochMilli().also { timeMs ->
                sendPacketRate.update(1, timeMs)
                sendBitRate.update(numBytes, timeMs)
            }
        }

        fun incomingPacketDropped() {
            incomingPacketsDropped++
        }

        fun outgoingPacketsDropped() {
            outgoingPacketsDropped++
        }

        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("packets_received", packetsReceived)
            put("receive_packet_rate_pps", receivePacketRate.rate)
            put("incoming_packets_dropped", incomingPacketsDropped)
            put("bytes_received", bytesReceived)
            put("packets_sent", packetsSent)
            put("send_packet_rate_pps", sendPacketRate.rate)
            put("outgoing_packets_dropped", outgoingPacketsDropped)
            put("bytes_sent", bytesSent)
        }

        companion object {
            const val RATE_INTERVAL: Int = 60000
            const val SCALE: Float = 1000f
        }
    }

    interface IncomingDataHandler {
        /**
         * Notify the handler that data was received (contained
         * within [data] at [offset] with [length]) at [receivedTime])
         *
         * Note that the handler does *not* own the buffer, and must copy if they
         * want to modify it or keep it longer than the duration of the
         * [dataReceived] call.
         */
        fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant)
    }
}
