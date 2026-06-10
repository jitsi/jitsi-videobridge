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
import java.net.URI

class ExporterWrapper(
    parentLogger: Logger,
    private val handleTranscriptionResult: ((TranscriptionResultEvent) -> Unit)
) : PotentialPacketHandler {
    val logger = createChildLogger(parentLogger)
    var started = false

    /** One running [Exporter] per requested connect, keyed by the connect's URL. */
    private val exporters = mutableMapOf<URI, Entry>()

    /**
     * Reconcile the running exporters with the requested set of connects, using the URL as the connect's identity:
     *  - stop exporters whose URL is no longer requested,
     *  - start exporters for URLs that weren't already running,
     *  - for URLs that are still requested, pass any change in the connect's other parameters to the existing
     *    exporter as an update.
     */
    fun setConnects(connects: List<Connect>) {
        // Validate up front so a rejected connect doesn't disturb the already-running exporters.
        connects.forEach { validate(it) }

        val desired = connects.associateBy { it.url }

        // Stop exporters whose URL is no longer requested.
        (exporters.keys - desired.keys).forEach { url ->
            logger.info("Stopping exporter for url=$url")
            exporters.remove(url)?.exporter?.stop()
        }

        desired.forEach { (url, connect) ->
            val params = ConnectParams(connect)
            val existing = exporters[url]
            when {
                // A URL that wasn't running: start a new exporter for it.
                existing == null -> exporters[url] = Entry(createExporter(connect), params)
                // A URL that was running, but some other parameter changed: hand the update to the exporter.
                existing.params != params -> {
                    logger.info("Updating exporter for url=$url")
                    existing.exporter.update(connect)
                    existing.params = params
                }
            }
        }

        started = exporters.isNotEmpty()
    }

    private fun isConnected() = started && exporters.values.any { it.exporter.isConnected() }

    /** Whether we want to accept a packet. */
    override fun wants(packet: PacketInfo): Boolean {
        if (!isConnected() || packet.packet !is AudioRtpPacket) return false
        return true
    }

    /** Accept a packet, fanning it out to each exporter. */
    override fun send(packet: PacketInfo) {
        val entries = exporters.values
        if (entries.isEmpty()) {
            ByteBufferPool.returnBuffer(packet.packet.buffer)
            return
        }
        // Each exporter takes ownership of the packet it's given (and returns its buffer), so hand each one its
        // own clone. The last exporter gets the original to avoid an unnecessary clone+copy.
        val lastIndex = entries.size - 1
        entries.forEachIndexed { index, entry ->
            entry.exporter.send(if (index == lastIndex) packet else packet.clone())
        }
    }

    fun stop() {
        if (started) {
            logger.info("Stopping.")
        }
        started = false
        exporters.values.forEach { it.exporter.stop() }
        exporters.clear()
    }

    private fun validate(connect: Connect) {
        if (connect.video) throw FeatureNotImplementedException("Video")
        if (connect.protocol != Connect.Protocols.MEDIAJSON) {
            throw FeatureNotImplementedException("Protocol ${connect.protocol}")
        }
    }

    private fun createExporter(connect: Connect): Exporter {
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
        exporters.values.forEach { exportersArray.add(it.exporter.debugState()) }
    }

    /** A running exporter together with the connect parameters it was last (re)configured with. */
    private class Entry(val exporter: Exporter, var params: ConnectParams)

    /**
     * The parameters of a [Connect] other than its URL (which is its identity). Used to detect whether a re-signaled
     * connect for an already-running URL has changed and therefore needs to be passed to the exporter as an update.
     * Source-name lists are normalized (sorted) so reordering alone isn't treated as a change.
     */
    private data class ConnectParams(
        val protocol: Connect.Protocols,
        val type: Connect.Types,
        val audio: Boolean,
        val video: Boolean,
        val headers: Map<String, String>,
        val pingInterval: Int?,
        val pingTimeout: Int?,
        val exports: List<String>,
        val requests: List<String>
    ) {
        constructor(connect: Connect) : this(
            connect.protocol,
            connect.type,
            connect.audio,
            connect.video,
            connect.getHttpHeaders().associate { it.name to it.value },
            connect.getPing()?.interval,
            connect.getPing()?.timeout,
            connect.getExports().sorted(),
            connect.getRequests().sorted()
        )
    }
}
