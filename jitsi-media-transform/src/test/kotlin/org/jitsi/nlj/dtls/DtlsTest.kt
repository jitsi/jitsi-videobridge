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

package org.jitsi.nlj.dtls

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.ProtocolVersion
import org.jitsi.config.setNewConfig
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.transform.node.PcapWriter
import org.jitsi.rtp.UnparsedPacket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.concurrent.thread

class DtlsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val debugEnabled = true
    private val pcapEnabled = false
    private val logger = StdoutLogger(_level = Level.OFF)

    fun debug(s: String) {
        if (debugEnabled) {
            println(s)
        }
    }

    /**
     * Runs a full DTLS handshake between two [DtlsStack]s wired directly to each other, exchanges a message in
     * each direction over the resulting connection, and returns the two stacks (server first).
     */
    private fun handshakeAndExchangeData(): Pair<DtlsStack, DtlsStack> {
        val dtlsServer = DtlsStack(logger).apply { actAsServer() }
        val dtlsClient = DtlsStack(logger).apply { actAsClient() }

        val pcapWriter = if (pcapEnabled) PcapWriter(logger, "/tmp/dtls-test.pcap") else null

        dtlsClient.remoteFingerprints = mapOf(
            dtlsServer.localFingerprintHashFunction to listOf(dtlsServer.localFingerprint)
        )
        dtlsServer.remoteFingerprints = mapOf(
            dtlsClient.localFingerprintHashFunction to listOf(dtlsClient.localFingerprint)
        )

        // The DTLS server's send is wired directly to the DTLS client's receive
        dtlsServer.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) {
                pcapWriter?.observe(PacketInfo(UnparsedPacket(data, off, len)), outbound = false)
                dtlsClient.processIncomingProtocolData(data, off, len)
            }
        }

        val serverReceivedData = CompletableFuture<String>()
        val serverToClientMessage = "Goodbye, world"
        dtlsServer.incomingDataHandler = object : DtlsStack.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                val packetData = ByteBuffer.wrap(data, off, len)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Server received message: '$receivedStr'")
                serverReceivedData.complete(receivedStr)

                val serverToClientData = serverToClientMessage.toByteArray()
                dtlsServer.sendApplicationData(serverToClientData, 0, serverToClientData.size)
            }
        }

        // The DTLS client's send is wired directly to the DTLS server's receive
        dtlsClient.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) {
                pcapWriter?.observe(PacketInfo(UnparsedPacket(data, off, len)), outbound = true)
                dtlsServer.processIncomingProtocolData(data, off, len)
            }
        }

        val clientReceivedData = CompletableFuture<String>()
        dtlsClient.incomingDataHandler = object : DtlsStack.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                val packetData = ByteBuffer.wrap(data, off, len)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Client received message: '$receivedStr'")
                clientReceivedData.complete(receivedStr)
            }
        }

        val serverThread = thread {
            debug("Server accepting")
            dtlsServer.start()
            debug("Server accepted connection")
        }

        debug("Client connecting")
        dtlsClient.start()
        // Ensure the server has fully established things on its side as well before we send the
        // message by waiting for the server accept thread to finish
        serverThread.join()
        debug("Client connected, sending message")
        val clientToServerMessage = "Hello, world"
        val clientToServerData = clientToServerMessage.toByteArray()
        dtlsClient.sendApplicationData(clientToServerData, 0, clientToServerData.size)

        serverReceivedData.get(5, TimeUnit.SECONDS) shouldBe clientToServerMessage
        clientReceivedData.get(5, TimeUnit.SECONDS) shouldBe serverToClientMessage

        return Pair(dtlsServer, dtlsClient)
    }

    /**
     * Like the standard test helper for overriding the config, but resets the config even if [block] throws, so a
     * failure doesn't leak the config into other tests.
     */
    private inline fun withConfig(config: String, block: () -> Unit) {
        setNewConfig(config, loadDefaults = true)
        try {
            block()
        } finally {
            setNewConfig("", loadDefaults = true)
        }
    }

    init {
        context("With the default configuration") {
            val (dtlsServer, dtlsClient) = handshakeAndExchangeData()

            should("negotiate DTLS 1.3") {
                dtlsServer.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv13
                dtlsClient.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv13
            }

            should("negotiate the X25519MLKEM768 post-quantum hybrid key exchange") {
                dtlsServer.negotiatedGroup shouldBe NamedGroup.X25519MLKEM768
                dtlsClient.negotiatedGroup shouldBe NamedGroup.X25519MLKEM768
            }

            should("keep the role that negotiated the connection when it is re-applied") {
                // The peer re-signals its setup attribute with transport updates long after the handshake, so the
                // role is re-applied then. It must not replace the role that negotiated this connection.
                val serverRole = dtlsServer.role
                dtlsServer.actAsServer() shouldBe false
                (dtlsServer.role === serverRole) shouldBe true

                val clientRole = dtlsClient.role
                dtlsClient.actAsClient() shouldBe false
                (dtlsClient.role === clientRole) shouldBe true
            }
        }

        context("With DTLS 1.3 disabled") {
            withConfig("jmt.dtls.enable-dtls13 = false") {
                val (dtlsServer, dtlsClient) = handshakeAndExchangeData()

                should("negotiate DTLS 1.2") {
                    dtlsServer.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv12
                    dtlsClient.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv12
                }
            }
        }

        context("With the post-quantum key exchange disabled") {
            withConfig("jmt.dtls.enable-post-quantum-key-exchange = false") {
                val (dtlsServer, dtlsClient) = handshakeAndExchangeData()

                should("negotiate DTLS 1.3 with X25519") {
                    dtlsServer.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv13
                    dtlsClient.negotiatedProtocolVersion shouldBe ProtocolVersion.DTLSv13
                    dtlsServer.negotiatedGroup shouldBe NamedGroup.x25519
                    dtlsClient.negotiatedGroup shouldBe NamedGroup.x25519
                }
            }
        }
    }
}
