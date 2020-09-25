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
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Videobridge
import org.json.simple.JSONObject
import org.jxmpp.jid.EntityBareJid
import java.time.Clock
import java.time.Duration

class ConferenceK @JvmOverloads constructor(
    videobridge: Videobridge,
    id: String,
    confName: EntityBareJid,
    enableLogging: Boolean,
    gid: Long,
    private val clock: Clock = Clock.systemUTC()
) : Conference(videobridge, id, confName, enableLogging, gid) {

    private val creationTime = clock.instant()

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
