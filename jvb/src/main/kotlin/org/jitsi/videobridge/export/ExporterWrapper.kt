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
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.colibri2.IqProcessingException
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jivesoftware.smack.packet.StanzaError
import java.net.URI

class ExporterWrapper internal constructor(
    parentLogger: Logger,
    private val handleTranscriptionResult: ((TranscriptionResultEvent) -> Unit),
    /** Handles a translated-audio media event received back from a peer. */
    private val handleMediaEvent: ((MediaEvent) -> Unit),
    /** Resolves an audio SSRC to its source name, used to filter outbound audio by a connect's exports. */
    private val getAudioSourceName: (Long) -> String?,
    /** Creates (and starts) the [Exporter] for a connect. Overridable for testing; defaults to the real one. */
    exporterFactory: ((Connect) -> Exporter)?
) {
    constructor(
        parentLogger: Logger,
        handleTranscriptionResult: ((TranscriptionResultEvent) -> Unit),
        handleMediaEvent: ((MediaEvent) -> Unit),
        getAudioSourceName: (Long) -> String?
    ) : this(parentLogger, handleTranscriptionResult, handleMediaEvent, getAudioSourceName, null)

    val logger = createChildLogger(parentLogger)
    var started = false

    private val exporterFactory: (Connect) -> Exporter = exporterFactory ?: ::createExporter

    /** One running [Exporter] per requested connect, keyed by the connect's id. */
    private val exporters = mutableMapOf<String, Entry>()

    /**
     * The running exporters exposed as [PotentialPacketHandler]s for the conference's send path. Each exporter is an
     * independent handler, so the conference's send loop clones a packet only when it actually goes to more than one
     * handler (and not at all for an exporter that filters the packet out via its own [PotentialPacketHandler.wants]).
     * Cached and recomputed only when the set of exporters changes, so it allocates nothing on the per-packet path.
     */
    @Volatile
    private var packetHandlers: List<PotentialPacketHandler> = emptyList()

    /** The current exporters as packet handlers, for the conference's send path. */
    fun getPacketHandlers(): List<PotentialPacketHandler> = packetHandlers

    /**
     * Apply a delta of connects, using the connect's [Connect.id] as its identity. Connects not mentioned are left
     * running unchanged. Each connect is one of:
     *  - `expire=true`: stop and remove the exporter with that id (a no-op with a warning if it isn't running),
     *  - `create=true`: start a new exporter; rejected if an exporter with that id is already running,
     *  - neither: update the already-running exporter with that id; rejected if none is running (i.e. a connect for a
     *    new id must set `create`). Only the exported/requested source names can change live; any other change is
     *    rejected.
     *
     * The whole delta is validated before anything is applied, so a rejected connect doesn't disturb running exporters.
     */
    fun applyConnects(connects: List<Connect>) {
        // Validate up front so a rejected connect doesn't disturb the already-running exporters.
        connects.forEach { validate(it) }

        // Pass 1: classify each connect and fail before mutating anything.
        val toExpire = mutableListOf<String>()
        val toCreate = mutableListOf<Connect>()
        val toUpdate = mutableListOf<Pair<Connect, ConnectParams>>()
        connects.forEach { connect ->
            val id = connect.id
            when {
                connect.expire -> toExpire += id
                connect.create -> {
                    if (exporters.containsKey(id)) {
                        throw IqProcessingException(
                            StanzaError.Condition.bad_request,
                            "Connect with create=true for an already-existing id=$id"
                        )
                    }
                    toCreate += connect
                }
                else -> {
                    val running = exporters[id] ?: throw IqProcessingException(
                        StanzaError.Condition.bad_request,
                        "Connect for unknown id=$id without create=true"
                    )
                    val params = ConnectParams(connect)
                    val unsupported = running.params.nonUpdatableChangesTo(params)
                    if (unsupported.isNotEmpty()) {
                        throw FeatureNotImplementedException(
                            "Updating connect ${unsupported.joinToString()} for id=$id"
                        )
                    }
                    toUpdate += connect to params
                }
            }
        }

        // Pass 2: apply.
        toExpire.forEach { id ->
            val removed = exporters.remove(id)
            if (removed != null) {
                logger.info("Stopping exporter id=$id")
                removed.exporter.stop()
            } else {
                logger.warn("Received expire for unknown connect id=$id")
            }
        }
        toCreate.forEach { connect ->
            exporters[connect.id] = Entry(exporterFactory(connect), ConnectParams(connect))
        }
        toUpdate.forEach { (connect, params) ->
            val existing = exporters.getValue(connect.id)
            if (existing.params != params) {
                logger.info("Updating exporter id=${connect.id}")
                existing.exporter.update(params.exports, params.requests)
                existing.params = params
            }
        }

        started = exporters.isNotEmpty()
        packetHandlers = exporters.values.map { it.exporter }
    }

    fun stop() {
        if (started) {
            logger.info("Stopping.")
        }
        started = false
        exporters.values.forEach { it.exporter.stop() }
        exporters.clear()
        packetHandlers = emptyList()
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
            handleMediaEvent,
            getAudioSourceName,
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
        // Tag each exporter's debug info with its connect id (the identity it is stored and signaled under) so debug
        // entries can be correlated to a specific connect.
        exporters.forEach { (id, entry) -> exportersArray.add(entry.exporter.debugState().put("tag", id)) }
    }

    /** A running exporter together with the connect parameters it was last (re)configured with. */
    private class Entry(val exporter: Exporter, var params: ConnectParams)

    /**
     * The parameters of a [Connect] other than its id (which is its identity). Used to detect whether a re-signaled
     * connect for an already-running id has changed and therefore needs to be passed to the exporter as an update.
     * Source-name lists are normalized (sorted) so reordering alone isn't treated as a change.
     */
    private data class ConnectParams(
        val url: URI,
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
            connect.url,
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

        /**
         * The names of the parameters that differ from [other] and cannot be applied to an already-running exporter.
         * Everything except [exports]/[requests] is connection-level and requires a reconnect to change, so a change
         * to any of them is reported here (and rejected). protocol/video aren't included: [validate] already
         * constrains them.
         */
        fun nonUpdatableChangesTo(other: ConnectParams): List<String> = buildList {
            if (url != other.url) add("url")
            if (type != other.type) add("type")
            if (audio != other.audio) add("audio")
            if (headers != other.headers) add("headers")
            if (pingInterval != other.pingInterval || pingTimeout != other.pingTimeout) add("ping")
        }
    }
}
