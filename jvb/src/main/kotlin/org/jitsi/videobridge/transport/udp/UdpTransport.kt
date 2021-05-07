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

import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.secs
import org.jitsi.utils.stats.RateTracker
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
import java.util.concurrent.atomic.LongAdder

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
    private val logger = createChildLogger(
        parentLogger,
        mapOf(
            "address" to bindAddress,
            "port" to bindPort.toString()
        )
    )

    private val running = AtomicBoolean(true)

    private val socket: DatagramSocket = DatagramSocket(
        InetSocketAddress(InetAddress.getByName(bindAddress), bindPort)
    ).apply {
        soRcvBuf?.let { receiveBufferSize = it }
        soSndBuf?.let { sendBufferSize = it }
    }.also { socket ->
        logger.info(
            "Initialized with bind address $bindAddress and bind port $bindPort. " +
                "Receive buffer size ${socket.receiveBufferSize}${soRcvBuf?.let { " (asked for $it)"} ?: ""}. " +
                "Send buffer size ${socket.sendBufferSize}${soSndBuf?.let { " (asked for $it)"} ?: ""}."
        )
    }

    private val stats = Stats()

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
            } catch (sce: SocketException) {
                logger.info("Socket closed, stopping reader")
                break
            } catch (e: Exception) {
                logger.warn("Exception while reading ", e)
                stats.exceptionOccurred()
                continue
            }

            val now = clock.instant()
            stats.packetReceived(packet.length, now)
            try {
                incomingDataHandler?.dataReceived(buf, packet.offset, packet.length, now)
                    ?: stats.incomingPacketDropped()
            } catch (e: Exception) {
                stats.exceptionOccurred()
                logger.warn("Exception while handling:", e)
            }
        }
    }

    /**
     * Send data out via this transport to [remoteAddress]. Does not take ownership
     * of the given buffer.
     */
    fun send(data: ByteArray, off: Int, length: Int, remoteAddress: SocketAddress) {
        if (!running.get()) {
            stats.outgoingPacketDropped()
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
    fun send(data: ByteArray, off: Int, length: Int, remoteAddresses: Collection<SocketAddress>) {
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

    fun getStats(): StatsSnapshot = stats.toSnapshot()

    class Stats {
        private val packetsReceived = LongAdder()
        private val bytesReceived = LongAdder()
        private val incomingPacketsDropped = LongAdder()
        private val packetsSent = LongAdder()
        private val bytesSent = LongAdder()
        private val outgoingPacketsDropped = LongAdder()
        private val receivePacketRate: RateTracker = RateTracker(RATE_INTERVAL, RATE_BUCKET_SIZE)
        private val receiveBitRate: BitrateTracker = BitrateTracker(RATE_INTERVAL, RATE_BUCKET_SIZE)
        private val sendPacketRate: RateTracker = RateTracker(RATE_INTERVAL, RATE_BUCKET_SIZE)
        private val sendBitRate: BitrateTracker = BitrateTracker(RATE_INTERVAL, RATE_BUCKET_SIZE)
        private val exceptions = LongAdder()

        fun packetReceived(numBytes: Int, time: Instant) {
            packetsReceived.increment()
            bytesReceived.add(numBytes.toLong())
            time.toEpochMilli().also { timeMs ->
                receivePacketRate.update(1, timeMs)
                receiveBitRate.update(numBytes.bytes, timeMs)
            }
        }

        fun packetSent(numBytes: Int, time: Instant) {
            packetsSent.increment()
            bytesSent.add(numBytes.toLong())
            time.toEpochMilli().also { timeMs ->
                sendPacketRate.update(1, timeMs)
                sendBitRate.update(numBytes.bytes, timeMs)
            }
        }

        fun incomingPacketDropped() = incomingPacketsDropped.increment()
        fun outgoingPacketDropped() = outgoingPacketsDropped.increment()
        fun exceptionOccurred() = exceptions.increment()

        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("packets_received", packetsReceived.sum())
            put("receive_packet_rate_pps", receivePacketRate.rate)
            put("incoming_packets_dropped", incomingPacketsDropped.sum())
            put("bytes_received", bytesReceived.sum())
            put("packets_sent", packetsSent.sum())
            put("send_packet_rate_pps", sendPacketRate.rate)
            put("outgoing_packets_dropped", outgoingPacketsDropped.sum())
            put("bytes_sent", bytesSent.sum())
            put("exceptions", exceptions.sum())
        }

        fun toSnapshot(): StatsSnapshot = StatsSnapshot(
            packetsReceived = packetsReceived.sum(),
            bytesReceived = bytesReceived.sum(),
            incomingPacketsDropped = incomingPacketsDropped.sum(),
            packetsSent = packetsSent.sum(),
            bytesSent = bytesSent.sum(),
            outgoingPacketsDropped = outgoingPacketsDropped.sum(),
            receivePacketRate = receivePacketRate.rate,
            receiveBitRate = receiveBitRate.rate.bps.toLong(),
            sendPacketRate = sendPacketRate.rate,
            sendBitRate = sendBitRate.rate.bps.toLong()
        )

        companion object {
            val RATE_INTERVAL = 60.secs
            val RATE_BUCKET_SIZE = 1.secs
        }
    }

    data class StatsSnapshot(
        val packetsReceived: Long,
        val bytesReceived: Long,
        val incomingPacketsDropped: Long,
        val packetsSent: Long,
        val bytesSent: Long,
        val outgoingPacketsDropped: Long,
        val receivePacketRate: Long,
        val receiveBitRate: Long,
        val sendPacketRate: Long,
        val sendBitRate: Long
    )

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
