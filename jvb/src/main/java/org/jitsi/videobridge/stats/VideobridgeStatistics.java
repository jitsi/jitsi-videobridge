/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.xmpp.*;
import org.json.simple.*;

import java.lang.management.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.locks.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

/**
 * Implements statistics that are collected by the Videobridge.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class VideobridgeStatistics
    extends Statistics
{
    /**
     * The <tt>DateFormat</tt> to be utilized by <tt>VideobridgeStatistics</tt>
     * in order to represent time and date as <tt>String</tt>.
     */
    private final DateFormat timestampFormat;

    /**
     * The number of buckets to use for conference sizes.
     */
    private static final int CONFERENCE_SIZE_BUCKETS = 22;

    /**
     * The currently configured region.
     */
    private static final String region = OctoConfig.config.getRegion();


    public static final String EPS_NO_MSG_TRANSPORT_AFTER_DELAY =
        "num_eps_no_msg_transport_after_delay";

    public static final String TOTAL_ICE_SUCCEEDED_RELAYED =
        "total_ice_succeeded_relayed";

    /**
     * Number of configured MUC clients.
     */
    public static final String MUC_CLIENTS_CONFIGURED = "muc_clients_configured";

    /**
     * Number of configured MUC clients that are connected to XMPP.
     */
    public static final String MUC_CLIENTS_CONNECTED = "muc_clients_connected";

    /**
     * Number of MUCs that are configured
     */
    public static final String MUCS_CONFIGURED = "mucs_configured";

    /**
     * Number of MUCs that are joined.
     */
    public static final String MUCS_JOINED = "mucs_joined";

    /**
     * The indicator which determines whether {@link #generate()} is executing
     * on this <tt>VideobridgeStatistics</tt>. If <tt>true</tt>, invocations of
     * <tt>generate()</tt> will do nothing. Introduced in order to mitigate an
     * issue in which a blocking in <tt>generate()</tt> will cause a multiple of
     * threads to be initialized and blocked.
     */
    private boolean inGenerate = false;

    private final @NotNull Videobridge videobridge;
    private final @Nullable OctoRelayService octoRelayService;
    private final @NotNull ClientConnection clientConnection;

    /**
     * Creates instance of <tt>VideobridgeStatistics</tt>.
     */
    public VideobridgeStatistics(
        @NotNull Videobridge videobridge,
        @Nullable OctoRelayService octoRelayService,
        @NotNull ClientConnection clientConnection
    )
    {
        this.videobridge = videobridge;
        this.octoRelayService = octoRelayService;
        this.clientConnection = clientConnection;

        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        // Is it necessary to set initial values for all of these?
        unlockedSetStat(BITRATE_DOWNLOAD, 0);
        unlockedSetStat(BITRATE_UPLOAD, 0);
        unlockedSetStat(CONFERENCES, 0);
        unlockedSetStat(PARTICIPANTS, 0);
        unlockedSetStat(THREADS, 0);
        unlockedSetStat(RTP_LOSS, 0d);
        unlockedSetStat(VIDEO_CHANNELS, 0);
        unlockedSetStat(LOSS_RATE_DOWNLOAD, 0d);
        unlockedSetStat(LOSS_RATE_UPLOAD, 0d);
        unlockedSetStat(JITTER_AGGREGATE, 0d);
        unlockedSetStat(RTT_AGGREGATE, 0d);
        unlockedSetStat(LARGEST_CONFERENCE, 0);
        unlockedSetStat(CONFERENCE_SIZES, "[]");

        unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generate()
    {
        // If a thread is already executing generate and has potentially
        // blocked, do not allow other threads to fall into the same trap.
        Lock lock = this.lock.writeLock();
        boolean inGenerate;

        lock.lock();
        try
        {
            if (this.inGenerate)
            {
                inGenerate = true;
            }
            else
            {
                // Enter the generate method.
                inGenerate = false;
                this.inGenerate = true;
            }
        }
        finally
        {
            lock.unlock();
        }
        if (!inGenerate)
        {
            try
            {
                generate0();
            }
            finally
            {
                // Exit the generate method.
                lock.lock();
                try
                {
                    this.inGenerate = false;
                }
                finally
                {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Generates/updates the statistics represented by this instance outside a
     * synchronized block.
     */
    @SuppressWarnings("unchecked")
    private void generate0()
    {
        Videobridge.Statistics jvbStats = videobridge.getStatistics();

        int videoChannels = 0;
        int conferences = 0;
        int octoConferences = 0;
        int endpoints = 0;
        int octoEndpoints = 0;
        double fractionLostSum = 0d; // TODO verify
        int fractionLostCount = 0;
        long packetsReceived = 0;
        long packetsReceivedLost = 0; // TODO verify
        long bitrateDownloadBps = 0;
        long bitrateUploadBps = 0;
        long packetRateUpload = 0;
        long packetRateDownload = 0;

        // Average jitter and RTT across MediaStreams which report a valid value.
        double jitterSumMs = 0; // TODO verify
        int jitterCount = 0;
        double rttSumMs = 0;
        long rttCount = 0;
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];
        int[] audioSendersBuckets = new int[CONFERENCE_SIZE_BUCKETS];
        int[] videoSendersBuckets = new int[CONFERENCE_SIZE_BUCKETS];
        int inactiveConferences = 0;
        int p2pConferences = 0;
        int inactiveEndpoints = 0;
        int receiveOnlyEndpoints = 0;
        int numAudioSenders = 0;
        int numVideoSenders = 0;

        for (Conference conference : videobridge.getConferences())
        {
            ConferenceShim conferenceShim = conference.getShim();
            //TODO: can/should we do everything here via the shim only?
            if (!conference.includeInStatistics())
            {
                continue;
            }
            conferences++;
            if (conference.isP2p())
            {
                p2pConferences++;
            }

            boolean inactive = conference.isInactive();
            if (inactive)
            {
                inactiveConferences++;
                inactiveEndpoints += conference.getEndpointCount();
            }
            if (conference.isOctoEnabled())
            {
                octoConferences++;
            }
            int numConferenceEndpoints = conference.getEndpointCount();
            int numLocalEndpoints = conference.getLocalEndpointCount();
            if (numConferenceEndpoints > largestConferenceSize)
            {
                largestConferenceSize = numConferenceEndpoints;
            }

            updateBuckets(conferenceSizes, numConferenceEndpoints);
            endpoints += numConferenceEndpoints;
            octoEndpoints += (numConferenceEndpoints - numLocalEndpoints);

            int conferenceAudioSenders = 0;
            int conferenceVideoSenders = 0;

            for (ContentShim contentShim : conferenceShim.getContents())
            {
                if (MediaType.VIDEO.equals(contentShim.getMediaType()))
                {
                    videoChannels += contentShim.getChannelCount();
                }
            }
            for (Endpoint endpoint : conference.getLocalEndpoints())
            {
                boolean sendingAudio = endpoint.isSendingAudio();
                boolean sendingVideo = endpoint.isSendingVideo();
                if (sendingAudio)
                {
                    conferenceAudioSenders++;
                }
                if (sendingVideo)
                {
                    conferenceVideoSenders++;
                }
                if (!sendingAudio && !sendingVideo && !inactive)
                {
                    receiveOnlyEndpoints++;
                }
                TransceiverStats transceiverStats
                        = endpoint.getTransceiver().getTransceiverStats();
                IncomingStatisticsSnapshot incomingStats
                        = transceiverStats.getIncomingStats();
                PacketStreamStats.Snapshot incomingPacketStreamStats
                        = transceiverStats.getIncomingPacketStreamStats();
                bitrateDownloadBps += incomingPacketStreamStats.getBitrate();
                packetRateDownload += incomingPacketStreamStats.getPacketRate();
                for (IncomingSsrcStats.Snapshot ssrcStats
                        : incomingStats.getSsrcStats().values())
                {
                    packetsReceived += ssrcStats.getNumReceivedPackets();

                    packetsReceivedLost += ssrcStats.getCumulativePacketsLost();

                    fractionLostCount++;
                    // note(george) this computes the fraction of lost packets
                    // since beginning of reception, which is different from the
                    // rfc 3550 sense.
                    double fractionLost = ssrcStats.getCumulativePacketsLost()
                        / (double) ssrcStats.getNumReceivedPackets();
                    fractionLostSum += fractionLost;

                    double ssrcJitter = ssrcStats.getJitter();
                    if (ssrcJitter != 0)
                    {
                        // We take the abs because otherwise the
                        // aggregate makes no sense.
                        jitterSumMs += Math.abs(ssrcJitter);
                        jitterCount++;
                    }

                }

                PacketStreamStats.Snapshot outgoingStats
                        = transceiverStats.getOutgoingPacketStreamStats();
                bitrateUploadBps += outgoingStats.getBitrate();
                packetRateUpload += outgoingStats.getPacketRate();

                double endpointRtt
                        = transceiverStats.getEndpointConnectionStats().getRtt();
                if (endpointRtt > 0)
                {
                    rttSumMs += endpointRtt;
                    rttCount++;
                }
            }

            updateBuckets(audioSendersBuckets, conferenceAudioSenders);
            numAudioSenders += conferenceAudioSenders;
            updateBuckets(videoSendersBuckets, conferenceVideoSenders);
            numVideoSenders += conferenceVideoSenders;
        }

        // Loss rates
        double lossRateDownload
            = (packetsReceived + packetsReceivedLost > 0)
            ? ((double) packetsReceivedLost) / (packetsReceived + packetsReceivedLost)
            : 0d;
        double lossRateUpload
            = (fractionLostCount > 0)
            ? fractionLostSum / fractionLostCount
            : 0d;

        // JITTER_AGGREGATE
        double jitterAggregate
            = jitterCount > 0
            ? jitterSumMs / jitterCount
            : 0;

        // RTT_AGGREGATE
        double rttAggregate
            = rttCount > 0
            ? rttSumMs / rttCount
            : 0;

        // CONFERENCE_SIZES
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : conferenceSizes)
            conferenceSizesJson.add(size);

        JSONArray audioSendersJson = new JSONArray();
        for (int n : audioSendersBuckets)
        {
            audioSendersJson.add(n);
        }
        JSONArray videoSendersJson = new JSONArray();
        for (int n : videoSendersBuckets)
        {
            videoSendersJson.add(n);
        }

        // THREADS
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

        // Now that (the new values of) the statistics have been calculated and
        // the risks of the current thread hanging have been reduced as much as
        // possible, commit (the new values of) the statistics.
        Lock lock = this.lock.writeLock();

        lock.lock();
        try
        {
            unlockedSetStat(
                    BITRATE_DOWNLOAD,
                    (bitrateDownloadBps + 500) / 1000 /* kbps */);
            unlockedSetStat(
                    BITRATE_UPLOAD,
                    (bitrateUploadBps + 500) / 1000 /* kbps */);
            unlockedSetStat(PACKET_RATE_DOWNLOAD, packetRateDownload);
            unlockedSetStat(PACKET_RATE_UPLOAD, packetRateUpload);
            // Keep for backward compatibility
            unlockedSetStat(
                    RTP_LOSS,
                    lossRateDownload + lossRateUpload);
            // TODO verify
            unlockedSetStat(LOSS_RATE_DOWNLOAD, lossRateDownload);
            // TODO verify
            unlockedSetStat(LOSS_RATE_UPLOAD, lossRateUpload);
            // TODO seems broken (I see values of > 11 seconds)
            unlockedSetStat(JITTER_AGGREGATE, jitterAggregate);
            unlockedSetStat(RTT_AGGREGATE, rttAggregate);
            unlockedSetStat(
                    TOTAL_FAILED_CONFERENCES,
                    jvbStats.totalFailedConferences.get());
            unlockedSetStat(
                    TOTAL_PARTIALLY_FAILED_CONFERENCES,
                    jvbStats.totalPartiallyFailedConferences.get());
            unlockedSetStat(
                    TOTAL_CONFERENCES_CREATED,
                    jvbStats.totalConferencesCreated.get());
            unlockedSetStat(
                    TOTAL_CONFERENCES_COMPLETED,
                    jvbStats.totalConferencesCompleted.get());
            unlockedSetStat(
                    TOTAL_ICE_FAILED,
                    jvbStats.totalIceFailed.get());
            unlockedSetStat(
                    TOTAL_ICE_SUCCEEDED,
                    jvbStats.totalIceSucceeded.get());
            unlockedSetStat(
                    TOTAL_ICE_SUCCEEDED_TCP,
                    jvbStats.totalIceSucceededTcp.get());
            unlockedSetStat(
                    TOTAL_ICE_SUCCEEDED_RELAYED,
                    jvbStats.totalIceSucceededRelayed.get());
            unlockedSetStat(
                    TOTAL_CONFERENCE_SECONDS,
                    jvbStats.totalConferenceSeconds.get());

            unlockedSetStat(
                    TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossControlledParticipantMs.get() / 1000);
            unlockedSetStat(
                    TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossLimitedParticipantMs.get() / 1000);
            unlockedSetStat(
                    TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS,
                   jvbStats.totalLossDegradedParticipantMs.get() / 1000);
            unlockedSetStat(TOTAL_PARTICIPANTS, jvbStats.totalEndpoints.get());
            unlockedSetStat(
                EPS_NO_MSG_TRANSPORT_AFTER_DELAY,
                jvbStats.numEndpointsNoMessageTransportAfterDelay.get()
            );
            unlockedSetStat(
                "stress_level",
                jvbStats.stressLevel
            );
            unlockedSetStat(CONFERENCES, conferences);
            unlockedSetStat(OCTO_CONFERENCES, octoConferences);
            unlockedSetStat(INACTIVE_CONFERENCES, inactiveConferences);
            unlockedSetStat(P2P_CONFERENCES, p2pConferences);
            unlockedSetStat(PARTICIPANTS, endpoints);
            unlockedSetStat(RECEIVE_ONLY_ENDPOINTS, receiveOnlyEndpoints);
            unlockedSetStat(INACTIVE_ENDPOINTS, inactiveEndpoints);
            unlockedSetStat(OCTO_ENDPOINTS, octoEndpoints);
            unlockedSetStat(ENDPOINTS_SENDING_AUDIO, numAudioSenders);
            unlockedSetStat(ENDPOINTS_SENDING_VIDEO, numVideoSenders);
            unlockedSetStat(VIDEO_CHANNELS, videoChannels);
            unlockedSetStat(LARGEST_CONFERENCE, largestConferenceSize);
            unlockedSetStat(CONFERENCE_SIZES, conferenceSizesJson);
            unlockedSetStat(CONFERENCES_BY_AUDIO_SENDERS, audioSendersJson);
            unlockedSetStat(CONFERENCES_BY_VIDEO_SENDERS, videoSendersJson);
            unlockedSetStat(THREADS, threadCount);
            unlockedSetStat(
                    SHUTDOWN_IN_PROGRESS,
                    videobridge.isShutdownInProgress());
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED,
                            jvbStats.totalDataChannelMessagesReceived.get());
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_SENT,
                            jvbStats.totalDataChannelMessagesSent.get());
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED,
                            jvbStats.totalColibriWebSocketMessagesReceived.get());
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT,
                            jvbStats.totalColibriWebSocketMessagesSent.get());
            unlockedSetStat(
                    TOTAL_BYTES_RECEIVED, jvbStats.totalBytesReceived.get());
            unlockedSetStat("dtls_failed_endpoints", jvbStats.dtlsFailedEndpoints.get());
            unlockedSetStat(TOTAL_BYTES_SENT, jvbStats.totalBytesSent.get());
            unlockedSetStat(
                    TOTAL_PACKETS_RECEIVED, jvbStats.totalPacketsReceived.get());
            unlockedSetStat(TOTAL_PACKETS_SENT, jvbStats.totalPacketsSent.get());

            OctoRelayService.Stats octoRelayServiceStats
                = octoRelayService == null ? null : octoRelayService.getStats();

            if (octoRelayService != null)
            {
                unlockedSetStat("octo_version", OctoRelayService.OCTO_VERSION);
            }

            unlockedSetStat(
                    TOTAL_BYTES_RECEIVED_OCTO,
                    octoRelayServiceStats == null ? 0 : octoRelayServiceStats.getBytesReceived());
            unlockedSetStat(
                    TOTAL_BYTES_SENT_OCTO,
                    octoRelayServiceStats == null ? 0 : octoRelayServiceStats.getBytesSent());
            unlockedSetStat(
                    TOTAL_PACKETS_RECEIVED_OCTO,
                    octoRelayServiceStats == null ? 0 : octoRelayServiceStats.getPacketsReceived());
            unlockedSetStat(
                    TOTAL_PACKETS_SENT_OCTO,
                    octoRelayServiceStats == null ? 0 : octoRelayServiceStats.getPacketsSent());
            unlockedSetStat(
                    TOTAL_PACKETS_DROPPED_OCTO,
                    octoRelayServiceStats == null ? 0 : octoRelayServiceStats.getPacketsDropped());
            unlockedSetStat(
                    OCTO_RECEIVE_BITRATE,
                    octoRelayServiceStats == null
                            ? 0 : octoRelayServiceStats.getReceiveBitrate());
            unlockedSetStat(
                    OCTO_RECEIVE_PACKET_RATE,
                    octoRelayServiceStats == null
                            ? 0 : octoRelayServiceStats.getReceivePacketRate());
            unlockedSetStat(
                    OCTO_SEND_BITRATE,
                    octoRelayServiceStats == null
                            ? 0 : octoRelayServiceStats.getSendBitrate());
            unlockedSetStat(
                    OCTO_SEND_PACKET_RATE,
                    octoRelayServiceStats == null
                            ? 0 : octoRelayServiceStats.getSendPacketRate());
            unlockedSetStat(
                    TOTAL_DOMINANT_SPEAKER_CHANGES,
                    jvbStats.totalDominantSpeakerChanges.sum());

            unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
            if (octoRelayServiceStats != null)
            {
                unlockedSetStat(RELAY_ID, octoRelayServiceStats.getRelayId());
            }
            if (region != null)
            {
                unlockedSetStat(REGION, region);
            }
            unlockedSetStat(VERSION, videobridge.getVersion().toString());

            // TODO(brian): expose these stats in a `getStats` call in ClientConnection
            //  rather than calling clientConnection.getMucClientManager?
            unlockedSetStat(
                    MUC_CLIENTS_CONFIGURED,
                    clientConnection.getMucClientManager().getClientCount());
            unlockedSetStat(
                    MUC_CLIENTS_CONNECTED,
                    clientConnection.getMucClientManager().getClientConnectedCount());
            unlockedSetStat(
                    MUCS_CONFIGURED,
                    clientConnection.getMucClientManager().getMucCount());
            unlockedSetStat(
                    MUCS_JOINED,
                    clientConnection.getMucClientManager().getMucJoinedCount());
        }
        finally
        {
            lock.unlock();
        }
    }

    private static void updateBuckets(int[] buckets, int n)
    {
        int index = Math.min(n, buckets.length - 1);
        buckets[index]++;
    }
}
