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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.xmpp.extensions.colibri2.Connect

class ExporterWrapper(
    parentLogger: Logger,
    private val handleTranscriptionResult: ((TranscriptionResultEvent) -> Unit)
) : PotentialPacketHandler {
    val logger = createChildLogger(parentLogger)
    var started = false

    /** One [Exporter] per [Connect]. The same audio is sent to all of them. */
    private val exporters = mutableListOf<Exporter>()

    fun setConnects(connects: List<Connect>) {
        when {
            started && connects.isNotEmpty() -> throw FeatureNotImplementedException("Changing connects once enabled.")
            connects.isEmpty() -> stop()
            else -> start(connects)
        }
    }

    private fun isConnected() = started && exporters.any { it.isConnected() }

    /** Whether we want to accept a packet. */
    override fun wants(packet: PacketInfo): Boolean {
        if (!isConnected() || packet.packet !is AudioRtpPacket) return false
        return true
    }

    /** Accept a packet, fanning it out to each exporter. */
    override fun send(packet: PacketInfo) {
        if (exporters.isEmpty()) {
            ByteBufferPool.returnBuffer(packet.packet.buffer)
            return
        }
        // Each exporter takes ownership of the packet it's given (and returns its buffer), so hand each one its
        // own clone. The last exporter gets the original to avoid an unnecessary clone+copy.
        val lastIndex = exporters.size - 1
        exporters.forEachIndexed { index, exporter ->
            exporter.send(if (index == lastIndex) packet else packet.clone())
        }
    }

    fun stop() {
        if (started) {
            logger.info("Stopping.")
        }
        started = false
        exporters.forEach { it.stop() }
        exporters.clear()
    }

    fun start(connects: List<Connect>) {
        if (exporters.isNotEmpty()) {
            logger.warn("Exporters already exist, stopping previous ones.")
            stop()
        }
        try {
            connects.forEach { exporters.add(createExporter(it)) }
        } catch (e: Exception) {
            // Don't leave partially-started exporters behind if one of the connects is rejected.
            stop()
            throw e
        }
        started = true
    }

    private fun createExporter(connect: Connect): Exporter {
        if (connect.video) throw FeatureNotImplementedException("Video")
        if (connect.protocol != Connect.Protocols.MEDIAJSON) {
            throw FeatureNotImplementedException("Protocol ${connect.protocol}")
        }

        logger.info("Starting with url=${connect.url}")
        val httpHeaders = connect.getHttpHeaders().associate { header ->
            header.name to header.value
        }

        // Extract ping configuration if present
        val ping = connect.getPing()
        val pingEnabled = ping != null
        // Default values in case ping is enabled, but no values are specified.
        val pingIntervalMs = ping?.interval ?: 10000
        val pingTimeoutMs = ping?.timeout ?: 3000

        // The source names to export (send out) and request (receive back). Not yet acted upon.
        val exports = connect.getExports()
        val requests = connect.getRequests()

        return Exporter(
            connect.url,
            httpHeaders,
            logger,
            handleTranscriptionResult,
            pingEnabled,
            pingIntervalMs,
            pingTimeoutMs,
            connect.type,
            exports,
            requests
        ).apply {
            start()
        }
    }

    fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("started", started)
        val exportersArray = putArray("exporters")
        exporters.forEach { exportersArray.add(it.debugState()) }
    }
}
