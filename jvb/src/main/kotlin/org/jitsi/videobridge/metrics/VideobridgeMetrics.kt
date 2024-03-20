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
package org.jitsi.videobridge.metrics

import org.jitsi.metrics.CounterMetric
import org.jitsi.videobridge.VideobridgeConfig
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer.Companion.instance as metricsContainer

object VideobridgeMetrics {
    val gracefulShutdown = metricsContainer.registerBooleanMetric(
        "graceful_shutdown",
        "Whether the bridge is in graceful shutdown mode (not accepting new conferences)."
    )
    val shuttingDown = metricsContainer.registerBooleanMetric(
        "shutting_down",
        "Whether the bridge is shutting down."
    )
    val drainMode = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "drain_mode",
        "Whether the bridge is in drain shutdown mode.",
        VideobridgeConfig.initialDrainMode
    )

    @JvmField
    val conferencesCompleted = metricsContainer.registerCounter(
        "conferences_completed",
        "The total number of conferences completed/expired on the Videobridge."
    )

    @JvmField
    val conferencesCreated = metricsContainer.registerCounter(
        "conferences_created",
        "The total number of conferences created on the Videobridge."
    )

    @JvmField
    val dataChannelMessagesReceived = metricsContainer.registerCounter(
        "data_channel_messages_received",
        "Number of messages received from the data channels of the endpoints of this conference."
    )

    @JvmField
    val dataChannelMessagesSent = metricsContainer.registerCounter(
        "data_channel_messages_sent",
        "Number of messages sent via the data channels of the endpoints of this conference."
    )

    @JvmField
    val colibriWebSocketMessagesReceived: CounterMetric = metricsContainer.registerCounter(
        "colibri_web_socket_messages_received",
        "Number of messages received from the data channels of the endpoints of this conference."
    )

    @JvmField
    val colibriWebSocketMessagesSent = metricsContainer.registerCounter(
        "colibri_web_socket_messages_sent",
        "Number of messages sent via the data channels of the endpoints of this conference."
    )

    @JvmField
    val packetsReceived = metricsContainer.registerCounter(
        "packets_received",
        "Number of RTP packets received in conferences on this videobridge."
    )

    @JvmField
    val packetsSent = metricsContainer.registerCounter(
        "packets_sent",
        "Number of RTP packets sent in conferences on this videobridge."
    )

    @JvmField
    val relayPacketsReceived = metricsContainer.registerCounter(
        "relay_packets_received",
        "Number of RTP packets received by relays in conferences on this videobridge."
    )

    @JvmField
    val relayPacketsSent = metricsContainer.registerCounter(
        "relay_packets_sent",
        "Number of RTP packets sent by relays in conferences on this videobridge."
    )

    @JvmField
    val totalEndpoints = metricsContainer.registerCounter(
        "endpoints",
        "The total number of endpoints created."
    )

    @JvmField
    val totalVisitors = metricsContainer.registerCounter(
        "visitors",
        "The total number of visitor endpoints created."
    )

    @JvmField
    val numEndpointsNoMessageTransportAfterDelay = metricsContainer.registerCounter(
        "endpoints_no_message_transport_after_delay",
        "Number of endpoints which had not established a relay message transport even after some delay."
    )

    @JvmField
    val totalRelays = metricsContainer.registerCounter(
        "relays",
        "The total number of relays created."
    )

    @JvmField
    val numRelaysNoMessageTransportAfterDelay = metricsContainer.registerCounter(
        "relays_no_message_transport_after_delay",
        "Number of relays which had not established a relay message transport even after some delay."
    )

    @JvmField
    val dominantSpeakerChanges = metricsContainer.registerCounter(
        "dominant_speaker_changes",
        "Number of times the dominant speaker in any conference changed."
    )

    @JvmField
    val endpointsDtlsFailed = metricsContainer.registerCounter(
        "endpoints_dtls_failed",
        "Number of endpoints whose ICE connection was established, but DTLS wasn't (at time of expiration)."
    )

    @JvmField
    val stressLevel = metricsContainer.registerDoubleGauge(
        "stress",
        "Current stress (between 0 and 1)."
    )

    @JvmField
    val preemptiveKeyframeRequestsSent = metricsContainer.registerCounter(
        "preemptive_keyframe_requests_sent",
        "Number of preemptive keyframe requests that were sent."
    )

    @JvmField
    val preemptiveKeyframeRequestsSuppressed = metricsContainer.registerCounter(
        "preemptive_keyframe_requests_suppressed",
        "Number of preemptive keyframe requests that were not sent because no endpoints were in stage view."
    )

    @JvmField
    val keyframesReceived = metricsContainer.registerCounter(
        "keyframes_received",
        "Number of keyframes that were received (updated on endpoint expiration)."
    )

    @JvmField
    val layeringChangesReceived = metricsContainer.registerCounter(
        "layering_changes_received",
        "Number of times the layering of an incoming video stream changed (updated on endpoint expiration)."
    )

    @JvmField
    val currentLocalEndpoints = metricsContainer.registerLongGauge(
        "local_endpoints",
        "Number of local endpoints that exist currently."
    )

    @JvmField
    val currentVisitors = metricsContainer.registerLongGauge(
        "current_visitors",
        "Number of visitor endpoints."
    )

    @JvmField
    val currentConferences = metricsContainer.registerLongGauge(
        "conferences",
        "Current number of conferences."
    )

    /** The currently configured region, if any. */
    val regionInfo = if (RelayConfig.config.region != null) {
        metricsContainer.registerInfo(
            ColibriStatsExtension.REGION,
            "The currently configured region.",
            RelayConfig.config.region!!
        )
    } else {
        null
    }
}
