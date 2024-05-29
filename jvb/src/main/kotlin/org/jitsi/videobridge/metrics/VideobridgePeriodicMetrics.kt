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
package org.jitsi.videobridge.metrics

import org.jitsi.nlj.rtcp.RembHandler.Companion.endpointsWithSpuriousRemb
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.stats.ConferencePacketStats
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer.Companion.instance as metricsContainer

/**
 * Holds gauge metrics that need to be updated periodically.
 */
object VideobridgePeriodicMetrics {
    val incomingLoss = metricsContainer.registerDoubleGauge(
        "incoming_loss_fraction",
        "Fraction of incoming RTP packets that are lost."
    )
    val outgoingLoss = metricsContainer.registerDoubleGauge(
        "outgoing_loss_fraction",
        "Fraction of outgoing RTP packets that are lost."
    )
    val loss = metricsContainer.registerDoubleGauge(
        "loss_fraction",
        "Fraction of RTP packets that are lost (incoming and outgoing combined)."
    )

    val relayIncomingBitrate = metricsContainer.registerLongGauge(
        "relay_incoming_bitrate",
        "Incoming RTP/RTCP bitrate from relays in bps."
    )
    val relayOutgoingBitrate = metricsContainer.registerLongGauge(
        "relay_outgoing_bitrate",
        "Outgoing RTP/RTCP bitrate to relays in bps."
    )
    val relayIncomingPacketRate = metricsContainer.registerLongGauge(
        "relay_incoming_packet_rate",
        "Incoming RTP/RTCP packet rate from relays in pps."
    )
    val relayOutgoingPacketRate = metricsContainer.registerLongGauge(
        "relay_outgoing_packet_rate",
        "Outgoing RTP/RTCP packet rate to relays in pps."
    )
    val incomingBitrate = metricsContainer.registerLongGauge(
        "incoming_bitrate",
        "Incoming RTP/RTCP bitrate in bps."
    )
    val outgoingBitrate = metricsContainer.registerLongGauge(
        "outgoing_bitrate",
        "Outgoing RTP/RTCP bitrate in bps."
    )
    val incomingPacketRate = metricsContainer.registerLongGauge(
        "incoming_packet_rate",
        "Incoming RTP/RTCP packet rate in pps."
    )
    val outgoingPacketRate = metricsContainer.registerLongGauge(
        "outgoing_packet_rate",
        "Outgoing RTP/RTCP packet rate in pps."
    )

    val averageRtt = metricsContainer.registerDoubleGauge(
        "average_rtt",
        "Average RTT across all local endpoints in ms."
    )

    val largestConference = metricsContainer.registerLongGauge(
        "largest_conference",
        "The size of the largest conference (number of endpoints)."
    )

    val endpoints = metricsContainer.registerLongGauge(
        "current_endpoints",
        "Number of current endpoints (local and relayed)."
    )
    val endpointsWithHighOutgoingLoss = metricsContainer.registerLongGauge(
        "endpoints_with_high_outgoing_loss",
        "Number of endpoints that have high outgoing loss (>10%)."
    )
    val activeEndpoints = metricsContainer.registerLongGauge(
        "active_endpoints",
        "The number of active local endpoints (in a conference where at least one endpoint sends audio or video)."
    )
    val endpointsSendingAudio = metricsContainer.registerLongGauge(
        "endpoints_sending_audio",
        "The number of local endpoints sending audio."
    )
    val endpointsSendingVideo = metricsContainer.registerLongGauge(
        "endpoints_sending_video",
        "The number of local endpoints sending video."
    )
    val endpointsRelayed = metricsContainer.registerLongGauge(
        "endpoints_relayed",
        "Number of relayed endpoints."
    )
    val endpointsOversending = metricsContainer.registerLongGauge(
        "endpoints_oversending",
        "Number of endpoints that we are oversending to."
    )
    val endpointsReceiveOnly = metricsContainer.registerLongGauge(
        "endpoints_recvonly",
        "Number of endpoints that are not sending audio or video (but are receiveing)."
    )
    val endpointsInactive = metricsContainer.registerLongGauge(
        "endpoints_inactive",
        "Number of endpoints in inactive conferences (where no endpoint sends audio or video)."
    )
    val endpointsWithSpuriousRemb = metricsContainer.registerLongGauge(
        "endpoints_with_spurious_remb",
        "Number of endpoints that have send a REMB packet even though REMB wasn't configured."
    )
    val endpointsWithSuspendedSources = metricsContainer.registerLongGauge(
        "endpoints_with_suspended_sources",
        "Number of endpoints that that we have suspended sending some video streams to because of bwe."
    )

    val conferencesInactive = metricsContainer.registerLongGauge(
        "conferences_inactive",
        "Number of inactive conferences (no endpoint is sending audio or video)."
    )
    val conferencesP2p = metricsContainer.registerLongGauge(
        "conferences_p2p",
        "Number of p2p conferences (inactive with 2 endpoints)."
    )
    val conferencesWithRelay = metricsContainer.registerLongGauge(
        "conferences_with_relay",
        "Number of conferences with one or more relays."
    )

    private val conferenceSizeBuckets = (0..20).map { it.toDouble() }.toList().toDoubleArray()
    val conferencesBySize = metricsContainer.registerHistogram(
        "conferences_by_size",
        "Histogram of conferences by total number of endpoints.",
        *conferenceSizeBuckets
    )
    val conferencesByAudioSender = metricsContainer.registerHistogram(
        "conferences_by_audio_sender",
        "Histogram of conferences by number of local endpoints sending audio.",
        *conferenceSizeBuckets
    )
    val conferencesByVideoSender = metricsContainer.registerHistogram(
        "conferences_by_video_sender",
        "Histogram of conferences by number of local endpoints sending video.",
        *conferenceSizeBuckets
    )

    fun update(videobridge: Videobridge) {
        var endpoints = 0L
        var localEndpoints = 0L
        var octoEndpoints = 0L

        var octoConferences = 0L

        var bitrateDownloadBps = 0.0
        var bitrateUploadBps = 0.0
        var packetRateUpload: Long = 0
        var packetRateDownload: Long = 0

        var relayBitrateIncomingBps = 0.0
        var relayBitrateOutgoingBps = 0.0
        var relayPacketRateOutgoing: Long = 0
        var relayPacketRateIncoming: Long = 0

        // Packets we received
        var incomingPacketsReceived: Long = 0
        // Packets we should have received but were lost
        var incomingPacketsLost: Long = 0
        // Packets we sent that were reported received
        var outgoingPacketsReceived: Long = 0
        // Packets we sent that were reported lost
        var outgoingPacketsLost: Long = 0

        var rttSumMs = 0.0
        var rttCount: Long = 0
        var largestConferenceSize = 0L
        var inactiveConferences = 0L
        var p2pConferences = 0L
        var inactiveEndpoints = 0L
        var receiveOnlyEndpoints = 0L
        var numAudioSenders = 0L
        var numVideoSenders = 0L
        // The number of endpoints to which we're "oversending" (which can occur when
        // enableOnstageVideoSuspend is false)
        var numOversending = 0L
        var endpointsWithHighOutgoingLoss = 0L
        var numLocalActiveEndpoints = 0L
        var endpointsWithSuspendedSources = 0L

        val conferences = videobridge.conferences
        val conferenceSizesList = ArrayList<Long>(conferences.size)
        val audioSendersList = ArrayList<Long>(conferences.size)
        val videoSendersList = ArrayList<Long>(conferences.size)

        for (conference in conferences) {
            var conferenceBitrate: Long = 0
            var conferencePacketRate: Long = 0
            if (conference.isP2p) {
                p2pConferences++
            }
            val inactive = conference.isInactive
            if (inactive) {
                inactiveConferences++
                inactiveEndpoints += conference.endpointCount
            } else {
                numLocalActiveEndpoints += conference.localEndpointCount
            }
            if (conference.hasRelays()) {
                octoConferences++
            }
            val numConferenceEndpoints = conference.endpointCount.toLong()
            val numLocalEndpoints = conference.localEndpointCount
            localEndpoints += numLocalEndpoints
            if (numConferenceEndpoints > largestConferenceSize) {
                largestConferenceSize = numConferenceEndpoints
            }
            conferenceSizesList.add(numConferenceEndpoints)
            endpoints += numConferenceEndpoints
            octoEndpoints += numConferenceEndpoints - numLocalEndpoints
            var conferenceAudioSenders = 0L
            var conferenceVideoSenders = 0L
            for (endpoint in conference.localEndpoints) {
                if (endpoint.isOversending()) {
                    numOversending++
                }
                val sendingAudio = endpoint.isSendingAudio
                val sendingVideo = endpoint.isSendingVideo
                if (sendingAudio) {
                    conferenceAudioSenders++
                }
                if (sendingVideo) {
                    conferenceVideoSenders++
                }
                if (!sendingAudio && !sendingVideo && !inactive) {
                    receiveOnlyEndpoints++
                }
                if (endpoint.hasSuspendedSources()) {
                    endpointsWithSuspendedSources++
                }
                val (endpointConnectionStats, rtpReceiverStats, _, outgoingStats) =
                    endpoint.transceiver.getTransceiverStats()
                val incomingStats = rtpReceiverStats.incomingStats
                val incomingPacketStreamStats = rtpReceiverStats.packetStreamStats
                bitrateDownloadBps += incomingPacketStreamStats.getBitrateBps()
                packetRateDownload += incomingPacketStreamStats.packetRate
                conferenceBitrate = (conferenceBitrate + incomingPacketStreamStats.getBitrateBps()).toLong()
                conferencePacketRate += incomingPacketStreamStats.packetRate
                bitrateUploadBps += outgoingStats.getBitrateBps()
                packetRateUpload += outgoingStats.packetRate
                conferenceBitrate = (conferenceBitrate + outgoingStats.getBitrateBps()).toLong()
                conferencePacketRate += outgoingStats.packetRate
                val endpointRtt = endpointConnectionStats.rtt
                if (endpointRtt > 0) {
                    rttSumMs += endpointRtt
                    rttCount++
                }
                incomingPacketsReceived += endpointConnectionStats.incomingLossStats.packetsReceived
                incomingPacketsLost += endpointConnectionStats.incomingLossStats.packetsLost
                val endpointOutgoingPacketsReceived = endpointConnectionStats.outgoingLossStats.packetsReceived
                val endpointOutgoingPacketsLost = endpointConnectionStats.outgoingLossStats.packetsLost
                outgoingPacketsReceived += endpointOutgoingPacketsReceived
                outgoingPacketsLost += endpointOutgoingPacketsLost
                if (!inactive && endpointOutgoingPacketsLost + endpointOutgoingPacketsReceived > 0) {
                    val endpointOutgoingFractionLost = (
                        endpointOutgoingPacketsLost.toDouble() /
                            (endpointOutgoingPacketsLost + endpointOutgoingPacketsReceived)
                        )
                    if (endpointOutgoingFractionLost > 0.1) {
                        endpointsWithHighOutgoingLoss++
                    }
                }
            }
            for (relay in conference.relays) {
                relayBitrateIncomingBps += relay.incomingBitrateBps
                relayPacketRateIncoming += relay.incomingPacketRate
                conferenceBitrate = (conferenceBitrate + relay.incomingBitrateBps).toLong()
                conferencePacketRate += relay.incomingPacketRate
                relayBitrateOutgoingBps += relay.outgoingBitrateBps
                relayPacketRateOutgoing += relay.outgoingPacketRate
                conferenceBitrate = (conferenceBitrate + relay.outgoingBitrateBps).toLong()
                conferencePacketRate += relay.outgoingPacketRate

                /* TODO: report Relay RTT and loss, like we do for Endpoints? */
            }
            audioSendersList.add(conferenceAudioSenders)
            numAudioSenders += conferenceAudioSenders
            videoSendersList.add(conferenceVideoSenders)
            numVideoSenders += conferenceVideoSenders
            ConferencePacketStats.stats.addValue(
                numConferenceEndpoints.toInt(),
                conferencePacketRate,
                conferenceBitrate
            )
        }

        // RTT_AGGREGATE
        val rttAggregate: Double = if (rttCount > 0) rttSumMs / rttCount else 0.0

        var incomingLoss = 0.0
        if (incomingPacketsReceived + incomingPacketsLost > 0) {
            incomingLoss = incomingPacketsLost.toDouble() / (incomingPacketsReceived + incomingPacketsLost)
        }
        var outgoingLoss = 0.0
        if (outgoingPacketsReceived + outgoingPacketsLost > 0) {
            outgoingLoss = outgoingPacketsLost.toDouble() / (outgoingPacketsReceived + outgoingPacketsLost)
        }
        var overallLoss = 0.0
        if (incomingPacketsReceived + incomingPacketsLost + outgoingPacketsReceived + outgoingPacketsLost > 0) {
            overallLoss = (
                (outgoingPacketsLost + incomingPacketsLost).toDouble() /
                    (incomingPacketsReceived + incomingPacketsLost + outgoingPacketsReceived + outgoingPacketsLost)
                )
        }

        VideobridgePeriodicMetrics.incomingLoss.set(incomingLoss)
        VideobridgePeriodicMetrics.outgoingLoss.set(outgoingLoss)
        VideobridgePeriodicMetrics.loss.set(overallLoss)
        VideobridgePeriodicMetrics.endpoints.set(endpoints)
        VideobridgePeriodicMetrics.endpointsWithHighOutgoingLoss.set(endpointsWithHighOutgoingLoss)
        VideobridgePeriodicMetrics.endpointsWithSpuriousRemb.set(endpointsWithSpuriousRemb().toLong())
        VideobridgePeriodicMetrics.activeEndpoints.set(numLocalActiveEndpoints)
        VideobridgePeriodicMetrics.incomingBitrate.set(bitrateDownloadBps.toLong())
        VideobridgePeriodicMetrics.outgoingBitrate.set(bitrateUploadBps.toLong())
        VideobridgePeriodicMetrics.incomingPacketRate.set(packetRateDownload)
        VideobridgePeriodicMetrics.outgoingPacketRate.set(packetRateUpload)
        VideobridgePeriodicMetrics.averageRtt.set(rttAggregate)
        VideobridgePeriodicMetrics.largestConference.set(largestConferenceSize)
        VideobridgePeriodicMetrics.endpointsSendingAudio.set(numAudioSenders)
        VideobridgePeriodicMetrics.endpointsSendingVideo.set(numVideoSenders)
        VideobridgePeriodicMetrics.endpointsRelayed.set(octoEndpoints)
        VideobridgePeriodicMetrics.endpointsOversending.set(numOversending)
        VideobridgePeriodicMetrics.endpointsReceiveOnly.set(receiveOnlyEndpoints)
        VideobridgePeriodicMetrics.endpointsInactive.set(inactiveEndpoints)
        VideobridgePeriodicMetrics.endpointsWithSuspendedSources.set(endpointsWithSuspendedSources)
        VideobridgePeriodicMetrics.conferencesInactive.set(inactiveConferences)
        VideobridgePeriodicMetrics.conferencesP2p.set(p2pConferences)
        VideobridgePeriodicMetrics.conferencesWithRelay.set(octoConferences)
        VideobridgePeriodicMetrics.relayIncomingBitrate.set(relayBitrateIncomingBps.toLong())
        VideobridgePeriodicMetrics.relayOutgoingBitrate.set(relayBitrateOutgoingBps.toLong())
        VideobridgePeriodicMetrics.relayIncomingPacketRate.set(relayPacketRateIncoming)
        VideobridgePeriodicMetrics.relayOutgoingPacketRate.set(relayPacketRateOutgoing)

        conferencesBySize.histogram.clear()
        conferenceSizesList.forEach { conferencesBySize.histogram.observe(it.toDouble()) }

        conferencesByAudioSender.histogram.clear()
        audioSendersList.forEach { conferencesByAudioSender.histogram.observe(it.toDouble()) }

        conferencesByVideoSender.histogram.clear()
        videoSendersList.forEach { conferencesByVideoSender.histogram.observe(it.toDouble()) }
    }
}
