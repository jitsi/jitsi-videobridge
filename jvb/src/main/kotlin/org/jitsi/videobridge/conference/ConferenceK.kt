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

package org.jitsi.videobridge.conference

import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.cinfo
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.EndpointConnectionStatusMonitor
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.message.DominantSpeakerMessage
import org.jitsi.videobridge.octo.ConfOctoTransport
import org.jitsi.videobridge.octo.OctoEndpoint
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import java.io.IOException
import java.time.Clock
import java.time.Duration

class ConferenceK(
    videobridge: Videobridge,
    id: String,
    confName: EntityBareJid,
    enableLogging: Boolean,
    gid: Long,
    private val clock: Clock
) : Conference(videobridge, id, confName, enableLogging, gid) {

    private val creationTime = clock.instant()

    private val epConnectionStatusMonitor: EndpointConnectionStatusMonitor?

    init {
        if (enableLogging) {
            epConnectionStatusMonitor = EndpointConnectionStatusMonitor(this, TaskPools.SCHEDULED_POOL, logger, clock)
            epConnectionStatusMonitor.start()
        } else {
            epConnectionStatusMonitor = null
        }
    }

    override fun expire() {
        super.expire()
        epConnectionStatusMonitor?.stop()
    }

    override fun endpointExpired(endpoint: AbstractEndpoint) {
        endpointsById.remove(endpoint.id)?.let {
            updateEndpointsCache()
            endpointsById.values.forEach { ep -> ep.removeReceiver(it.id) }
            epConnectionStatusMonitor?.endpointConnected(it.id)
            tentacle?.endpointExpired(it.id)
            endpointsChanged()
        }
    }

    override fun getTentacle(): ConfOctoTransport {
        if (gid == GID_NOT_SET) {
            throw IllegalStateException("Can not enable Octo without the GID being set.")
        }
        if (tentacle == null) {
            tentacle = ConfOctoTransport(this, clock)
        }
        return tentacle
    }

    /**
     * Notifies this {@link Conference} that one of its {@link Endpoint}s
     * transport channel has become available.
     *
     * @param endpoint the {@link Endpoint} whose transport channel has become
     * available.
     */
    override fun endpointMessageTransportConnected(endpoint: AbstractEndpoint) {
        epConnectionStatusMonitor?.endpointConnected(endpoint.id)

        if (!isExpired) {
            speechActivity.dominantEndpoint?.let {
                try {
                    endpoint.sendMessage(DominantSpeakerMessage(it.id))
                } catch (e: IOException) {
                    logger.error("Failed to send dominant speaker update to ${endpoint.id}", e)
                }
            }
        }
    }

    override fun createLocalEndpoint(id: String, iceControlling: Boolean): Endpoint {
        val existingEndpoint = getEndpoint(id)
        if (existingEndpoint is OctoEndpoint) {
            // It is possible that an Endpoint was migrated from another bridge
            // in the conference to this one, and the sources lists (which
            // implicitly signal the Octo endpoints in the conference) haven't
            // been updated yet. We'll force the Octo endpoint to expire and
            // we'll continue with the creation of a new local Endpoint for the
            // participant.
            existingEndpoint.expire()
        } else if (existingEndpoint != null) {
            throw IllegalArgumentException("Local endpoint with ID = $id already created")
        }
        return Endpoint(id, this, logger, iceControlling, clock).also {
            addEndpoint(it)
        }
    }

    override fun newDiagnosticContext(): DiagnosticContext {
        return DiagnosticContext().apply {
            put("conf_name", conferenceName)
            put("conf_creation_time_ms", creationTime.toEpochMilli())
        }
    }

    override fun shouldExpire(): Boolean =
        endpointCount == 0 && Duration.between(creationTime, clock.instant()) > Duration.ofSeconds(20)

    override fun getDebugState(full: Boolean, endpointId: String?): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", conferenceName.toString())

            if (full) {
                put("gid", gid)
                put("expired", expired.get())
                put("creationTime", creationTime.toEpochMilli())
                put("speechActivity", speechActivity.debugState)
                put("includeInStatistics", includeInStatistics)
                put("statistics", statistics.json)
                tentacle?.let {
                    put("tentacle", it.debugState)
                }
            }
            val endpoints = JSONObject().apply {
                endpointsCache.forEach { ep ->
                    if (endpointId == null || endpointId == ep.id) {
                        put(ep.id, if (full) ep.debugState else ep.statsId)
                    }
                }
            }
            put("endpoints", endpoints)
        }
    }

    override fun updateStatisticsOnExpire() {
        val jvbStats = getVideobridge().statistics

        val durationSeconds = Duration.between(creationTime, clock.instant()).seconds
        jvbStats.totalConferenceSeconds.addAndGet(durationSeconds)
        jvbStats.totalConferencesCompleted.incrementAndGet()

        jvbStats.totalBytesReceived.addAndGet(statistics.totalBytesReceived.get())
        jvbStats.totalBytesSent.addAndGet(statistics.totalBytesSent.get())
        jvbStats.totalPacketsReceived.addAndGet(statistics.totalPacketsReceived.get())
        jvbStats.totalPacketsSent.addAndGet(statistics.totalPacketsSent.get())

        val hasFailed = statistics.hasIceFailedEndpoint && !statistics.hasIceSucceededEndpoint
        val hasPartiallyFailed = statistics.hasIceFailedEndpoint && statistics.hasIceSucceededEndpoint

        jvbStats.dtlsFailedEndpoints.addAndGet(statistics.dtlsFailedEndpoints.get())

        if (hasPartiallyFailed) {
            jvbStats.totalPartiallyFailedConferences.incrementAndGet()
        }

        if (hasFailed) {
            jvbStats.totalFailedConferences.incrementAndGet()
        }

        logger.cinfo {
            "expire_conf,duration=$durationSeconds,has_failed=$hasFailed,has_partially_failed=$hasPartiallyFailed"
        }
    }
}
