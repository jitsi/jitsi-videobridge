/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import org.jitsi.xmpp.extensions.colibri2.Connect

class Exporter : PotentialPacketHandler {
    val logger = createLogger()
    var started = false
    val queue = PacketInfoQueue(
        "${javaClass.simpleName}-packet-queue",
        TaskPools.IO_POOL,
        this::doHandlePacket,
        128
    )

    private var wsNotConnectedErrors = 0
    private fun logWsNotConnectedError(): Boolean = (wsNotConnectedErrors++ % 1000) == 0
    private val encoder = MediaJsonEncoder {
        if (recorderWebSocket.isConnected) {
            recorderWebSocket.remote?.sendString(it.toJson())
                ?: logger.info("Websocket is connected, but remote is null")
        } else if (logWsNotConnectedError()) {
            logger.info("Can not send packet, websocket is not connected (count=$wsNotConnectedErrors).")
        }
    }
    private var recorderWebSocket = WebSocketAdapter()

    fun setConnects(exports: List<Connect>) {
        when {
            started && exports.isNotEmpty() -> throw FeatureNotImplementedException("Changing exports once enabled.")
            exports.isEmpty() -> stop()
            exports.size > 1 -> throw FeatureNotImplementedException("Multiple exports")
            exports[0].video -> throw FeatureNotImplementedException("Video")
            else -> start(exports[0])
        }
    }

    private fun doHandlePacket(packet: PacketInfo): Boolean {
        if (started) {
            encoder.encode(packet.packetAs(), packet.endpointId!!)
        }
        ByteBufferPool.returnBuffer(packet.packet.buffer)
        return true
    }

    override fun wants(packet: PacketInfo): Boolean = started && packet.packet is AudioRtpPacket

    override fun send(packet: PacketInfo) {
        if (started) {
            queue.add(packet)
        } else {
            ByteBufferPool.returnBuffer(packet.packet.buffer)
        }
    }

    fun stop() {
        started = false
        logger.info("Stopping.")
        recorderWebSocket.session?.close(org.eclipse.jetty.websocket.core.CloseStatus.SHUTDOWN, "closing")
    }

    fun start(connect: Connect) {
        if (connect.video) throw FeatureNotImplementedException("Video")
        if (connect.protocol != Connect.Protocols.MEDIAJSON) {
            throw FeatureNotImplementedException("Protocol ${connect.protocol}")
        }
        if (connect.type != Connect.Types.RECORDER) {
            throw FeatureNotImplementedException("Type ${connect.type}")
        }

        logger.info("Starting with url=${connect.url}")
        webSocketClient.connect(recorderWebSocket, connect.url, ClientUpgradeRequest())
        started = true
    }

    companion object {
        val webSocketClient = WebSocketClient().apply {
            idleTimeout = WebsocketServiceConfig.config.idleTimeout
            start()
        }
    }
}
