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
package org.jitsi.videobridge.dcsctp

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.dcsctp4j.DcSctpMessage
import org.jitsi.dcsctp4j.DcSctpOptions
import org.jitsi.dcsctp4j.DcSctpSocketCallbacks
import org.jitsi.dcsctp4j.DcSctpSocketFactory
import org.jitsi.dcsctp4j.DcSctpSocketInterface
import org.jitsi.dcsctp4j.SendOptions
import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.dcsctp4j.Timeout
import org.jitsi.nlj.PacketInfo
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.sctp.SctpConfig
import org.jitsi.videobridge.util.TaskPools
import java.lang.ref.WeakReference
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DcSctpTransport(
    val name: String,
    parentLogger: Logger
) {
    val logger = createChildLogger(parentLogger)
    private val lock = Any()
    private var socket: DcSctpSocketInterface? = null

    /** Exceptions a callback raised that dcsctp4j surfaced from a socket method; see [withSocket]. */
    private val surfacedExceptions = AtomicLong()

    fun start(callbacks: DcSctpSocketCallbacks, options: DcSctpOptions = DEFAULT_SOCKET_OPTIONS) {
        synchronized(lock) {
            socket = factory.create(name, callbacks, null, options)
        }
    }

    /**
     * Runs [block] on the socket, under the lock, if there is one; otherwise returns [ifNoSocket].
     *
     * dcsctp4j surfaces an exception thrown by one of our callbacks from the socket method that was
     * running at the time (dcsctp itself is compiled without exception support, so it cannot be
     * thrown through it). These methods are the only way into the socket, so this is the one place
     * to catch and log such an exception, whichever thread it surfaces on. [ifNoSocket] is returned
     * in that case too. This runs on the ingress path for every packet, so after the first few the
     * stack trace is dropped and only every hundredth occurrence is logged.
     */
    private inline fun <T> withSocket(what: String, ifNoSocket: T, block: (DcSctpSocketInterface) -> T): T {
        synchronized(lock) {
            val s = socket ?: return ifNoSocket
            return try {
                block(s)
            } catch (e: Throwable) {
                val n = surfacedExceptions.incrementAndGet()
                if (n <= 3) {
                    logger.warn("Exception $what", e)
                } else if (n % 100 == 0L) {
                    logger.warn("Exception $what ($n so far): $e")
                }
                ifNoSocket
            }
        }
    }

    fun handleIncomingSctp(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        withSocket("processing incoming SCTP packet", Unit) {
            it.receivePacket(packet.getBuffer(), packet.getOffset(), packet.getLength())
        }
    }

    fun stop() {
        synchronized(lock) {
            // Null the field first: if close() throws, nothing may keep calling into the closed socket.
            val s = socket ?: return
            socket = null
            try {
                s.close()
            } catch (e: Throwable) {
                logger.warn("Exception closing SCTP socket", e)
            }
        }
    }

    fun connect() {
        withSocket("connecting SCTP socket", Unit) { it.connect() }
    }

    /**
     * Returns [SendStatus.kErrorShuttingDown] when there is no socket, and also when a callback
     * exception surfaced from the send: there is no status for that, the warning logged above
     * shows the real cause, and the socket should be treated as unusable from then on anyway.
     */
    fun send(message: DcSctpMessage, options: SendOptions): SendStatus =
        withSocket("sending SCTP message", SendStatus.kErrorShuttingDown) { it.send(message, options) }

    fun handleTimeout(timeoutId: Long) {
        withSocket("handling SCTP timeout", Unit) { it.handleTimeout(timeoutId) }
    }

    fun getDebugState(): ObjectNode {
        val metrics = withSocket("reading SCTP metrics", null) { it.metrics }
        return JsonNodeFactory.instance.objectNode().apply {
            put("surfaced_callback_exceptions", surfacedExceptions.get())
            if (metrics != null) {
                put("tx_packets_count", metrics.txPacketsCount)
                put("tx_messages_count", metrics.txMessagesCount)
                put("rtx_packets_count", metrics.rtxPacketsCount)
                put("rtx_bytes_count", metrics.rtxBytesCount)
                put("cwnd_bytes", metrics.cwndBytes)
                put("srtt_ms", metrics.srttMs)
                put("unack_data_count", metrics.unackDataCount)
                put("rx_packets_count", metrics.rxPacketsCount)
                put("rx_messages_count", metrics.rxMessagesCount)
                put("peer_rwnd_bytes", metrics.peerRwndBytes)
                put("peer_implementation", metrics.peerImplementation.name)
                put("uses_message_interleaving", metrics.usesMessageInterleaving())
                put("uses_zero_checksum", metrics.usesZeroChecksum())
                put("negotiated_maximum_incoming_streams", metrics.negotiatedMaximumIncomingStreams)
                put("negotiated_maximum_outgoing_streams", metrics.negotiatedMaximumOutgoingStreams)
            }
        }
    }

    companion object {
        private val factory by lazy {
            check(SctpConfig.config.enabled()) { "SCTP is disabled in configuration" }
            DcSctpSocketFactory()
        }

        /* Copying value set by Chrome's dcsctp_transport. */
        const val DEFAULT_MAX_TIMER_DURATION = 3000L

        val DEFAULT_SOCKET_OPTIONS by lazy {
            check(SctpConfig.config.enabled()) { "SCTP is disabled in configuration" }
            DcSctpOptions().apply {
                maxTimerBackoffDuration = DEFAULT_MAX_TIMER_DURATION
                // Because we're making retransmits faster, we need to allow unlimited retransmits
                // or SCTP can time out (which we don't handle).  Peer connection timeouts are handled at
                // a higher layer.
                maxRetransmissions = null
                maxInitRetransmits = null
            }
        }

        val DEFAULT_SEND_OPTIONS by lazy {
            check(SctpConfig.config.enabled()) { "SCTP is disabled in configuration" }
            SendOptions()
        }

        const val DEFAULT_SCTP_PORT: Int = 5000
    }
}

abstract class DcSctpBaseCallbacks(
    val transport: DcSctpTransport,
    val clock: Clock = Clock.systemUTC()
) : DcSctpSocketCallbacks {
    /* Methods we can usefully implement for every JVB socket */
    override fun createTimeout(p0: DcSctpSocketCallbacks.DelayPrecision): Timeout = ATimeout(transport)

    override fun Now(): Instant = clock.instant()

    override fun getRandomInt(low: Long, high: Long): Long = ThreadLocalRandom.current().nextLong(low, high)

    /* Methods we wouldn't normally expect to be called for a JVB SCTP socket. */
    override fun OnConnectionRestarted() {
        transport.logger.info("Surprising SCTP callback: connection restarted")
    }

    override fun OnStreamsResetFailed(outgoingStreams: ShortArray, reason: String) {
        transport.logger.info(
            "Surprising SCTP callback: streams ${outgoingStreams.joinToString()} reset failed: $reason"
        )
    }

    override fun OnStreamsResetPerformed(outgoingStreams: ShortArray) {
        // This is normal following a call to close(), which is a hard-close (as opposed to shutdown() which is
        // soft-close)
        transport.logger.info("Outgoing streams ${outgoingStreams.joinToString()} reset")
    }

    override fun OnIncomingStreamsReset(incomingStreams: ShortArray) {
        /* Does Chrome ever reset streams? */
        transport.logger.info("Surprising SCTP callback: incoming streams ${incomingStreams.joinToString()} reset")
    }

    private class ATimeout(transport: DcSctpTransport) : Timeout {
        // This holds a weak reference to the transport, to break JNI reference cycles
        private val weakTransport = WeakReference(transport)
        private val transport: DcSctpTransport?
            get() = weakTransport.get()
        private var timeoutId: Long = 0

        // Assigned on the SCHEDULED_POOL thread and read by stop() from another. Volatile makes the
        // assignments visible to stop(); a stop() racing the scheduled lambda before `future` is set
        // can still miss the IO task, which then calls handleTimeout for a stopped timer, which
        // dcsctp ignores.
        @Volatile
        private var scheduledFuture: ScheduledFuture<*>? = null

        @Volatile
        private var future: Future<*>? = null
        override fun start(duration: Long, timeoutId: Long) {
            try {
                this.timeoutId = timeoutId
                scheduledFuture = TaskPools.SCHEDULED_POOL.schedule({
                    /* Execute it on the IO_POOL, because a timer may trigger sending new SCTP packets. */
                    future = TaskPools.IO_POOL.submit {
                        transport?.handleTimeout(timeoutId)
                    }
                }, duration, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                transport?.logger?.warn("Exception scheduling DCSCTP timeout", e)
            }
        }

        override fun stop() {
            scheduledFuture?.cancel(false)
            future?.cancel(false)
            scheduledFuture = null
            future = null
        }
    }
}
