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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.xmpp.extensions.colibri2.Connect

class ExporterWrapper(parentLogger: Logger) : PotentialPacketHandler {
    val logger = createChildLogger(parentLogger)
    var started = false
    private var exporter: Exporter? = null
    fun setConnects(connects: List<Connect>) {
        when {
            started && connects.isNotEmpty() -> throw FeatureNotImplementedException("Changing connects once enabled.")
            connects.isEmpty() -> stop()
            connects.size > 1 -> throw FeatureNotImplementedException("Multiple connects")
            connects[0].video -> throw FeatureNotImplementedException("Video")
            else -> start(connects[0])
        }
    }

    private fun isConnected() = started && exporter?.isConnected() == true

    /** Whether we want to accept a packet. */
    override fun wants(packet: PacketInfo): Boolean {
        if (!isConnected() || packet.packet !is AudioRtpPacket) return false
        if (packet.payloadType !is OpusPayloadType) {
            logger.warn("Ignore audio with unsupported payload type: ${packet.payloadType}")
            return false
        }
        return true
    }

    /** Accept a packet, add it to the queue. */
    override fun send(packet: PacketInfo) {
        exporter?.send(packet) ?: run {
            ByteBufferPool.returnBuffer(packet.packet.buffer)
        }
    }

    fun stop() {
        if (started) {
            logger.info("Stopping.")
        }
        started = false
        exporter?.stop()
        exporter = null
    }

    fun start(connect: Connect) {
        if (connect.video) throw FeatureNotImplementedException("Video")
        if (connect.protocol != Connect.Protocols.MEDIAJSON) {
            throw FeatureNotImplementedException("Protocol ${connect.protocol}")
        }

        logger.info("Starting with url=${connect.url}")
        if (exporter != null) {
            logger.warn("Exporter already exists, stopping previous one.")
            stop()
        }
        exporter = Exporter(connect.url, logger).apply {
            start()
        }
        started = true
    }
}
