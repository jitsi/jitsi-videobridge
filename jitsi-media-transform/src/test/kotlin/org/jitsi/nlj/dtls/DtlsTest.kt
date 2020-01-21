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

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.PcapWriter
import org.jitsi.nlj.transform.node.incoming.ProtocolReceiver
import org.jitsi.nlj.transform.node.outgoing.ProtocolSender
import org.jitsi.rtp.UnparsedPacket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DtlsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    private val debugEnabled = true
    private val pcapEnabled = false
    private val logger = StdoutLogger()

    fun debug(s: String) {
        if (debugEnabled) {
            println(s)
        }
    }

    init {
        val dtlsServer = DtlsStack(logger).apply { actAsServer() }
        val dtlsClient = DtlsStack(logger).apply { actAsClient() }

        dtlsClient.remoteFingerprints = mapOf(
            dtlsServer.localFingerprintHashFunction to dtlsServer.localFingerprint)
        dtlsServer.remoteFingerprints = mapOf(
            dtlsClient.localFingerprintHashFunction to dtlsClient.localFingerprint)

        val serverSender = ProtocolSender(dtlsServer)
        val serverReceiver = ProtocolReceiver(dtlsServer)

        val clientSender = ProtocolSender(dtlsClient)
        val clientReceiver = ProtocolReceiver(dtlsClient)

        val pcapWriter = if (pcapEnabled) PcapWriter(logger, "/tmp/dtls-test.pcap") else null

        // The server and client senders are connected directly to their
        // peer's receiver
        serverSender.attach(object : ConsumerNode("server network") {
            override fun consume(packetInfo: PacketInfo) {
                pcapWriter?.processPacket(packetInfo)
                clientReceiver.processPacket(packetInfo)
            }
        })
        clientSender.attach(object : ConsumerNode("client network") {
            override fun consume(packetInfo: PacketInfo) {
                pcapWriter?.processPacket(packetInfo)
                serverReceiver.processPacket(packetInfo)
            }
        })

        // We attach a consumer to each peer's receiver to consume the DTLS app packet
        // messages
        val serverReceivedData = CompletableFuture<String>()
        val serverToClientMessage = "Goodbye, world"
        serverReceiver.attach(object : ConsumerNode("server incoming app packets") {
            override fun consume(packetInfo: PacketInfo) {
                val packetData = ByteBuffer.wrap(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Server received message: '$receivedStr'")
                serverReceivedData.complete(receivedStr)
                serverSender.processPacket(PacketInfo(UnparsedPacket(serverToClientMessage.toByteArray())))
            }
        })

        val clientReceivedData = CompletableFuture<String>()
        clientReceiver.attach(object : ConsumerNode("client incoming app packets") {
            override fun consume(packetInfo: PacketInfo) {
                val packetData = ByteBuffer.wrap(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Client received message: '$receivedStr'")
                clientReceivedData.complete(receivedStr)
            }
        })

        val serverThread = thread {
            debug("Server accepting")
            dtlsServer.start()
            debug("Server accepted connection")
        }

        debug("Client connecting")
        dtlsClient.start()
        debug("Client connected, sending message")
        // Ensure the server has fully established things on its side as well before we send the
        // message by waiting for the server accept thread to finish
        serverThread.join()
        val clientToServerMessage = "Hello, world"
        dtlsClient.sendApplicationData(PacketInfo(UnparsedPacket(clientToServerMessage.toByteArray())))

        serverReceivedData.get(5, TimeUnit.SECONDS) shouldBe clientToServerMessage
        clientReceivedData.get(5, TimeUnit.SECONDS) shouldBe serverToClientMessage
    }
}
