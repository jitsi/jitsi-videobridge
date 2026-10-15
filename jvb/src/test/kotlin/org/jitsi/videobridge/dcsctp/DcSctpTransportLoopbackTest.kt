/*
 * Copyright @ 2026 - present 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.dcsctp4j.DcSctpMessage
import org.jitsi.dcsctp4j.ErrorKind
import org.jitsi.dcsctp4j.SendPacketStatus
import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.datachannel.protocol.DataChannelProtocolConstants
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Loopback check of JVB's own DcSctpTransport, DcSctpBaseCallbacks and timeout plumbing against
 * the dcsctp4j library: two transports deliver packets to each other, each side's incoming
 * packets in order on a single thread as the real pipeline does, connect, exchange messages both
 * ways, and shut down.
 */
class DcSctpTransportLoopbackTest : ShouldSpec() {
    /**
     * A logger that records warnings, also for the child loggers created from it. DcSctpTransport
     * catches and logs, rather than throws, an exception that dcsctp4j surfaces from one of our
     * callbacks, so this is how the test sees that something went wrong in the transport.
     */
    private class WarningRecordingLogger(
        name: String,
        val warnings: LinkedBlockingQueue<String> = LinkedBlockingQueue()
    ) : LoggerImpl(name) {
        override fun warn(msg: Any?) {
            warnings.add("$msg")
            super.warn(msg)
        }

        override fun warn(msg: Supplier<String>) {
            warnings.add(msg.get())
            super.warn(msg)
        }

        override fun warn(msg: Any?, t: Throwable?) {
            warnings.add("$msg: $t")
            super.warn(msg, t)
        }

        override fun createChildLogger(name: String): Logger = WarningRecordingLogger(name, warnings)

        override fun createChildLogger(name: String, context: Map<String, String>): Logger =
            WarningRecordingLogger(name, warnings)
    }

    private class Side(name: String) {
        val logger = WarningRecordingLogger("DcSctpTransportLoopbackTest.$name")
        val transport = DcSctpTransport(name, logger)

        /** Delivers this side's incoming packets, in order, like the ingress pipeline thread. */
        val incoming = Executors.newSingleThreadExecutor()
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val received = LinkedBlockingQueue<String>()

        /** Anything unexpected the callbacks were told about: SCTP errors and aborts. */
        val errors = LinkedBlockingQueue<String>()
        lateinit var peer: Side

        val callbacks = object : DcSctpBaseCallbacks(transport) {
            override fun sendPacketWithStatus(packet: ByteArray): SendPacketStatus {
                val copy = packet.copyOf()
                peer.incoming.execute {
                    peer.transport.handleIncomingSctp(PacketInfo(UnparsedPacket(copy, 0, copy.size)))
                }
                return SendPacketStatus.kSuccess
            }
            override fun OnMessageReceived(message: DcSctpMessage) {
                received.add(String(message.payload, StandardCharsets.UTF_8))
            }
            override fun OnError(error: ErrorKind, message: String) {
                errors.add("$error: $message")
            }
            override fun OnAborted(error: ErrorKind, message: String) {
                errors.add("aborted $error: $message")
            }
            override fun OnConnected() {
                connected.countDown()
            }
            override fun OnClosed() {
                closed.countDown()
            }
        }
    }

    init {
        context("Two JVB DcSctpTransports") {
            should("connect and exchange data channel messages") {
                val a = Side("a")
                val b = Side("b")
                a.peer = b
                b.peer = a
                try {
                    a.transport.start(a.callbacks)
                    b.transport.start(b.callbacks)

                    a.transport.connect()
                    a.connected.await(5, TimeUnit.SECONDS) shouldBe true
                    b.connected.await(5, TimeUnit.SECONDS) shouldBe true

                    val ppid = DataChannelProtocolConstants.WEBRTC_PPID_STRING
                    for (i in 1..20) {
                        a.transport.send(
                            DcSctpMessage(0, ppid, "a->b $i".toByteArray(StandardCharsets.UTF_8)),
                            DcSctpTransport.DEFAULT_SEND_OPTIONS
                        ) shouldBe SendStatus.kSuccess
                        b.transport.send(
                            DcSctpMessage(0, ppid, "b->a $i".toByteArray(StandardCharsets.UTF_8)),
                            DcSctpTransport.DEFAULT_SEND_OPTIONS
                        ) shouldBe SendStatus.kSuccess
                    }
                    for (i in 1..20) {
                        b.received.poll(5, TimeUnit.SECONDS) shouldBe "a->b $i"
                        a.received.poll(5, TimeUnit.SECONDS) shouldBe "b->a $i"
                    }
                    a.errors.toList() shouldBe emptyList()
                    b.errors.toList() shouldBe emptyList()
                    a.logger.warnings.toList() shouldBe emptyList()
                    b.logger.warnings.toList() shouldBe emptyList()

                    // close() sends an ABORT to the peer and reports OnClosed locally before returning.
                    a.transport.stop()
                    a.closed.await(5, TimeUnit.SECONDS) shouldBe true

                    // Whether b sees a's ABORT (OnAborted) before its own stop() (OnClosed) is a race
                    // between the two sides' delivery threads. Either way b ends up closed, and
                    // nothing else may have been reported.
                    b.transport.stop()
                    if (b.closed.count != 0L) {
                        b.errors.poll()?.startsWith("aborted") shouldBe true
                    }
                    a.errors.toList() shouldBe emptyList()
                    b.errors.toList() shouldBe emptyList()
                    a.logger.warnings.toList() shouldBe emptyList()
                    b.logger.warnings.toList() shouldBe emptyList()
                } finally {
                    // Also on a failed assertion, so no native sockets or timers outlive the test. Stop
                    // both transports before shutting down either delivery executor: close() sends an
                    // ABORT to the peer, which would be rejected by a shut-down executor.
                    a.transport.stop()
                    b.transport.stop()
                    a.incoming.shutdown()
                    b.incoming.shutdown()
                }
            }
        }
    }
}
