/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.videobridge.stats

import org.jitsi.nlj.rtcp.RembHandler
import org.jitsi.videobridge.EndpointConnectionStatusMonitor
import org.jitsi.videobridge.VersionConfig
import org.jitsi.videobridge.health.JvbHealthChecker
import org.jitsi.videobridge.load_management.JvbLoadManager
import org.jitsi.videobridge.metrics.JvmMetrics
import org.jitsi.videobridge.metrics.Metrics
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.metrics.VideobridgePeriodicMetrics
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.version.JvbVersionService
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.BITRATE_DOWNLOAD
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.BITRATE_UPLOAD
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.CONFERENCES
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.DRAIN
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.ENDPOINTS_SENDING_AUDIO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.ENDPOINTS_SENDING_VIDEO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.INACTIVE_CONFERENCES
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.INACTIVE_ENDPOINTS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.LARGEST_CONFERENCE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_CONFERENCES
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_ENDPOINTS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_RECEIVE_BITRATE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_RECEIVE_PACKET_RATE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_SEND_BITRATE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.OCTO_SEND_PACKET_RATE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.P2P_CONFERENCES
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.PACKET_RATE_DOWNLOAD
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.PACKET_RATE_UPLOAD
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.PARTICIPANTS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.RECEIVE_ONLY_ENDPOINTS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.REGION
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.RELAY_ID
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.RELEASE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.RTT_AGGREGATE
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.SHUTDOWN_IN_PROGRESS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.Stat
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.THREADS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TIMESTAMP
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_BYTES_RECEIVED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_BYTES_RECEIVED_OCTO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_BYTES_SENT
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_BYTES_SENT_OCTO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_CONFERENCES_COMPLETED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_CONFERENCES_CREATED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_CONFERENCE_SECONDS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_DATA_CHANNEL_MESSAGES_SENT
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_DOMINANT_SPEAKER_CHANGES
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_ICE_FAILED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_ICE_SUCCEEDED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_PACKETS_RECEIVED
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_PACKETS_RECEIVED_OCTO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_PACKETS_SENT
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_PACKETS_SENT_OCTO
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.TOTAL_PARTICIPANTS
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.VERSION
import org.json.simple.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * A shim layer which translates the Prometheus metrics (from [VideobridgeMetricsContainer]) to the legacy
 * [ColibriStatsExtension] suitable to be added to XMPP presence or converted to JSON for the response to the legacy
 * /colibri/stats
 */
object VideobridgeStatisticsShim {
    fun getStatsJson() = JSONObject().apply {
        getStats().forEach { (k, v) ->
            this[k] = v
        }
    }

    /**
     * Formats statistics in <tt>ColibriStatsExtension</tt> object
     * @param statistics the statistics instance
     * @return the <tt>ColibriStatsExtension</tt> instance.
     */
    fun getColibriStatsExtension() = ColibriStatsExtension().apply {
        getStats().forEach { (key, value) ->
            addStat(Stat(key, value))
        }
    }

    /**
     * Formats statistics in <tt>ColibriStatsExtension</tt> object
     * @param statistics the statistics instance
     * @param whitelist which of the statistics to use
     * @return the <tt>ColibriStatsExtension</tt> instance.
     */
    fun getColibriStatsExtension(whitelist: List<String>) = ColibriStatsExtension().apply {
        val allStats = getStats()
        whitelist.forEach { whitelistedKey ->
            val value = allStats[whitelistedKey]
            if (value != null) {
                addStat(Stat(whitelistedKey, value))
            }
        }
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun getStats(): Map<String, Any> = synchronized(Metrics.lock) {
        return buildMap {
            put("incoming_loss", VideobridgePeriodicMetrics.incomingLoss.get())
            put("outgoing_loss", VideobridgePeriodicMetrics.outgoingLoss.get())
            put("overall_loss", VideobridgePeriodicMetrics.loss.get())
            put("endpoints_with_high_outgoing_loss", VideobridgePeriodicMetrics.endpointsWithHighOutgoingLoss.get())
            put("local_active_endpoints", VideobridgePeriodicMetrics.activeEndpoints.get())
            put(BITRATE_DOWNLOAD, VideobridgePeriodicMetrics.incomingBitrate.get() / 1000)
            put(BITRATE_UPLOAD, VideobridgePeriodicMetrics.outgoingBitrate.get() / 1000)
            put(PACKET_RATE_DOWNLOAD, VideobridgePeriodicMetrics.incomingPacketRate.get())
            put(PACKET_RATE_UPLOAD, VideobridgePeriodicMetrics.outgoingPacketRate.get())
            put(RTT_AGGREGATE, VideobridgePeriodicMetrics.averageRtt.get())
            put("num_eps_oversending", VideobridgePeriodicMetrics.endpointsOversending.get())
            put(OCTO_CONFERENCES, VideobridgePeriodicMetrics.conferencesWithRelay.get())
            put(INACTIVE_CONFERENCES, VideobridgePeriodicMetrics.conferencesInactive.get())
            put(P2P_CONFERENCES, VideobridgePeriodicMetrics.conferencesP2p.get())
            put("endpoints", VideobridgePeriodicMetrics.endpoints.get())
            put(PARTICIPANTS, VideobridgePeriodicMetrics.endpoints.get())
            put(RECEIVE_ONLY_ENDPOINTS, VideobridgePeriodicMetrics.endpointsReceiveOnly.get())
            put(INACTIVE_ENDPOINTS, VideobridgePeriodicMetrics.endpointsInactive.get())
            put(OCTO_ENDPOINTS, VideobridgePeriodicMetrics.endpointsRelayed.get())
            put(ENDPOINTS_SENDING_AUDIO, VideobridgePeriodicMetrics.endpointsSendingAudio.get())
            put(ENDPOINTS_SENDING_VIDEO, VideobridgePeriodicMetrics.endpointsSendingVideo.get())
            put(LARGEST_CONFERENCE, VideobridgePeriodicMetrics.largestConference.get())
            put(OCTO_RECEIVE_BITRATE, VideobridgePeriodicMetrics.relayIncomingBitrate.get())
            put(OCTO_RECEIVE_PACKET_RATE, VideobridgePeriodicMetrics.relayIncomingPacketRate.get())
            put(OCTO_SEND_BITRATE, VideobridgePeriodicMetrics.relayOutgoingBitrate.get())
            put(OCTO_SEND_PACKET_RATE, VideobridgePeriodicMetrics.relayOutgoingPacketRate.get())
            put("endpoints_with_suspended_sources", VideobridgePeriodicMetrics.endpointsWithSuspendedSources.get())

            put(TOTAL_CONFERENCES_CREATED, VideobridgeMetrics.conferencesCreated.get())
            put(TOTAL_CONFERENCES_COMPLETED, VideobridgeMetrics.conferencesCompleted.get())
            put(TOTAL_CONFERENCE_SECONDS, VideobridgeMetrics.totalConferenceSeconds.get())
            put(TOTAL_PARTICIPANTS, VideobridgeMetrics.totalEndpoints.get())
            put("total_visitors", VideobridgeMetrics.totalVisitors.get())
            put(
                "num_eps_no_msg_transport_after_delay",
                VideobridgeMetrics.numEndpointsNoMessageTransportAfterDelay.get()
            )
            put("total_relays", VideobridgeMetrics.totalRelays.get())
            put(
                "num_relays_no_msg_transport_after_delay",
                VideobridgeMetrics.numRelaysNoMessageTransportAfterDelay.get()
            )
            put("total_keyframes_received", VideobridgeMetrics.keyframesReceived.get())
            put("total_layering_changes_received", VideobridgeMetrics.layeringChangesReceived.get())
            put(
                "total_video_stream_milliseconds_received",
                VideobridgeMetrics.totalVideoStreamMillisecondsReceived.get()
            )
            put("stress_level", VideobridgeMetrics.stressLevel.get())
            put(CONFERENCES, VideobridgeMetrics.currentConferences.get())
            put("visitors", VideobridgeMetrics.currentVisitors.get())
            put("local_endpoints", VideobridgeMetrics.currentLocalEndpoints.get())
            put(TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED, VideobridgeMetrics.dataChannelMessagesReceived.get())
            put(TOTAL_DATA_CHANNEL_MESSAGES_SENT, VideobridgeMetrics.dataChannelMessagesSent.get())
            put(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED, VideobridgeMetrics.colibriWebSocketMessagesReceived.get())
            put(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT, VideobridgeMetrics.colibriWebSocketMessagesSent.get())
            put(TOTAL_BYTES_RECEIVED, VideobridgeMetrics.totalBytesReceived.get())
            put("dtls_failed_endpoints", VideobridgeMetrics.endpointsDtlsFailed.get())
            put(TOTAL_BYTES_SENT, VideobridgeMetrics.totalBytesSent.get())
            put(TOTAL_PACKETS_RECEIVED, VideobridgeMetrics.packetsReceived.get())
            put(TOTAL_PACKETS_SENT, VideobridgeMetrics.packetsSent.get())
            put(TOTAL_BYTES_RECEIVED_OCTO, VideobridgeMetrics.totalRelayBytesReceived.get())
            put(TOTAL_BYTES_SENT_OCTO, VideobridgeMetrics.totalRelayBytesSent.get())
            put(TOTAL_PACKETS_RECEIVED_OCTO, VideobridgeMetrics.relayPacketsReceived.get())
            put(TOTAL_PACKETS_SENT_OCTO, VideobridgeMetrics.relayPacketsSent.get())
            put(TOTAL_DOMINANT_SPEAKER_CHANGES, VideobridgeMetrics.dominantSpeakerChanges.get())
            put("preemptive_kfr_sent", VideobridgeMetrics.preemptiveKeyframeRequestsSent.get())
            put("preemptive_kfr_suppressed", VideobridgeMetrics.preemptiveKeyframeRequestsSuppressed.get())

            put(TOTAL_ICE_FAILED, IceTransport.iceFailed.get())
            put(TOTAL_ICE_SUCCEEDED, IceTransport.iceSucceeded.get())
            put("total_ice_succeeded_relayed", IceTransport.iceSucceededRelayed.get())

            put("average_participant_stress", JvbLoadManager.averageParticipantStress)

            JvmMetrics.INSTANCE?.threadCount?.let {
                put(THREADS, it.get())
            }

            put(SHUTDOWN_IN_PROGRESS, VideobridgeMetrics.gracefulShutdown.get())
            put("shutting_down", VideobridgeMetrics.shuttingDown.get())
            put(DRAIN, VideobridgeMetrics.drainMode.get())

            put(TIMESTAMP, timestampFormat.format(Date()))
            if (RelayConfig.config.enabled) {
                put(RELAY_ID, RelayConfig.config.relayId)
            }
            put("muc_clients_configured", XmppConnection.mucClientsConfigured.get())
            put("muc_clients_connected", XmppConnection.mucClientsConnected.get())
            put("mucs_configured", XmppConnection.mucsConfigured.get())
            put("mucs_joined", XmppConnection.mucsJoined.get())

            put("endpoints_with_spurious_remb", RembHandler.endpointsWithSpuriousRemb())
            put("healthy", JvbHealthChecker.healthyMetric.get())
            put("endpoints_disconnected", EndpointConnectionStatusMonitor.endpointsDisconnected.get())
            put("endpoints_reconnected", EndpointConnectionStatusMonitor.endpointsReconnected.get())

            put(VERSION, JvbVersionService.instance.currentVersion.toString())
            VersionConfig.config.release?.let {
                put(RELEASE, it)
            }
            VideobridgeMetrics.regionInfo?.let {
                put(REGION, it.get())
            }
        }
    }
}
