/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import java.lang.management.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.locks.*;

import net.java.sip.communicator.util.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.shim.*;
import org.json.simple.*;
import org.osgi.framework.*;

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
     * The name of the number of audio channels statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String AUDIOCHANNELS = "audiochannels";

    /**
     * The name of the bit rate statistic for download.
     */
    public static final String BITRATE_DOWNLOAD = "bit_rate_download";

    /**
     * The name of the bit rate statistic for upload.
     */
    public static final String BITRATE_UPLOAD = "bit_rate_upload";

    /**
     * The name of the packet rate statistic for download.
     */
    public static final String PACKET_RATE_DOWNLOAD = "packet_rate_download";

    /**
     * The name of the packet rate statistic for upload.
     */
    public static final String PACKET_RATE_UPLOAD = "packet_rate_upload";

    /**
     * The name of the number of conferences statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String CONFERENCES = "conferences";

    /**
     * The name of the CPU usage statistic.
     */
    public static final String CPU_USAGE = "cpu_usage";

    /**
     * The <tt>DateFormat</tt> to be utilized by <tt>VideobridgeStatistics</tt>
     * in order to represent time and date as <tt>String</tt>.
     */
    private static final DateFormat dateFormat;

    /**
     * The name of the number of participants statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String NUMBEROFPARTICIPANTS = "participants";

    /**
     * The name of the number of threads statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String NUMBEROFTHREADS = "threads";

    /**
     * The name of the RTP loss statistic.
     * @deprecated
     */
    public static final String RTP_LOSS = "rtp_loss";

    /**
     * The name of the loss rate statistic.
     */
    public static final String LOSS_RATE_DOWNLOAD = "loss_rate_download";

    /**
     * The name of the loss rate statistic.
     */
    public static final String LOSS_RATE_UPLOAD = "loss_rate_upload";

    /**
     * The name of the aggregate jitter statistic.
     */
    public static final String JITTER_AGGREGATE = "jitter_aggregate";

    /**
     * The name of the aggregate RTT statistic.
     */
    public static final String RTT_AGGREGATE = "rtt_aggregate";

    /**
     * The name of the "largest conference" statistic.
     */
    public static final String LARGEST_CONFERENCE = "largest_conference";

    /**
     * The name of the conference sizes statistic.
     */
    public static final String CONFERENCE_SIZES = "conference_sizes";

    /**
     * The number of buckets to use for conference sizes.
     */
    private static final int CONFERENCE_SIZE_BUCKETS = 22;

    /**
     * The name of the stat that indicates the bridge has entered graceful
     * shutdown mode. Its runtime type is {@code Boolean}.
     */
    public static final String SHUTDOWN_IN_PROGRESS = "graceful_shutdown";

    /**
     * The name of the piece of statistic which specifies the date and time at
     * which the associated set of statistics was generated. Its runtime type is
     * {@code String} and the value represents a {@code Date} value.
     */
    public static final String TIMESTAMP = "current_timestamp";

    /**
     * The name of total memory statistic. Its runtime type is {@code Integer}.
     */
    public static final String TOTAL_MEMORY = "total_memory";

    /**
     * The name of the total number of channels without any payload (RTP/RTCP)
     * traffic.
     */
    private static final String TOTAL_NO_PAYLOAD_CHANNELS
        = "total_no_payload_channels";

    /**
     * The name of the total number of channels where the transport failed to
     * be established.
     */
    private static final String TOTAL_NO_TRANSPORT_CHANNELS
        = "total_no_transport_channels";

    /**
     * The name of the total number of channels (failed + succeeded).
     */
    private static final String TOTAL_CHANNELS
        = "total_channels";

    /**
     * The name of the total number of conferences where all channels failed
     * due to no payload traffic.
     */
    private static final String TOTAL_FAILED_CONFERENCES
        = "total_failed_conferences";

    /**
     * The name of the total number of conferences with some failed channels.
     */
    private static final String TOTAL_PARTIALLY_FAILED_CONFERENCES
        = "total_partially_failed_conferences";

    /**
     * The name of the total number of completed/expired conferences
     * (failed + succeeded).
     */
    private static final String TOTAL_CONFERENCES_COMPLETED
        = "total_conferences_completed";

    /**
     * The name of the stat indicating the total number of conferences created.
     */
    private static final String TOTAL_CONFERENCES_CREATED
        = "total_conferences_created";

    /**
     * The name of the stat indicating the total number of conference-seconds
     * (i.e. the sum of the lengths is seconds).
     */
    private static final String TOTAL_CONFERENCE_SECONDS
        = "total_conference_seconds";

    /**
     * The name of the stat indicating the total number of participant-seconds
     * that are loss-controlled (i.e. the sum of the lengths is seconds).
     */
    private static final String TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS
        = "total_loss_controlled_participant_seconds";

    /**
     * The name of the stat indicating the total number of participant-seconds
     * that are loss-limited.
     */
    private static final String TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS
        = "total_loss_limited_participant_seconds";

    /**
     * The name of the stat indicating the total number of participant-seconds
     * that are loss-degraded.
     */
    private static final String TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS
        = "total_loss_degraded_participant_seconds";

    /**
     * The name of the stat indicating the total number of media connections
     * established over UDP.
     */
    private static final String TOTAL_UDP_CONNECTIONS = "total_udp_connections";

    /**
     * The name of the stat indicating the total number of media connections
     * established over TCP.
     */
    private static final String TOTAL_TCP_CONNECTIONS = "total_tcp_connections";

    /**
     * The name of the stat indicating the total number of messages received
     * from data channels.
     */
    private static final String TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED
        = "total_data_channel_messages_received";

    /**
     * The name of the stat indicating the total number of messages sent over
     * data channels.
     */
    private static final String TOTAL_DATA_CHANNEL_MESSAGES_SENT
        = "total_data_channel_messages_sent";

    /**
     * The name of the stat indicating the total number of messages received
     * from data channels.
     */
    private static final String TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED
        = "total_colibri_web_socket_messages_received";

    /**
     * The name of the stat indicating the total number of messages sent over
     * data channels.
     */
    private static final String TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT
        = "total_colibri_web_socket_messages_sent";

    /**
     * The name of the stat indicating the total number of bytes received in
     * RTP packets.
     */
    private static final String TOTAL_BYTES_RECEIVED = "total_bytes_received";

    /**
     * The name of the stat indicating the total number of bytes sent in RTP
     * packets.
     */
    private static final String TOTAL_BYTES_SENT = "total_bytes_sent";

    /**
     * The name of the stat indicating the total number of RTP packets received.
     */
    private static final String TOTAL_PACKETS_RECEIVED
        = "total_packets_received";

    /**
     * The name of the stat indicating the total number of RTP packets sent.
     */
    private static final String TOTAL_PACKETS_SENT = "total_packets_sent";

    /**
     * The name of the stat indicating the total number of bytes received in
     * Octo packets.
     */
    private static final String TOTAL_BYTES_RECEIVED_OCTO
        = "total_bytes_received_octo";

    /**
     * The name of the stat indicating the total number of bytes sent in Octo
     * packets.
     */
    private static final String TOTAL_BYTES_SENT_OCTO = "total_bytes_sent_octo";

    /**
     * The name of the stat indicating the total number of Octo packets received.
     */
    private static final String TOTAL_PACKETS_RECEIVED_OCTO
        = "total_packets_received_octo";

    /**
     * The name of the stat indicating the total number of Octo packets sent.
     */
    private static final String TOTAL_PACKETS_SENT_OCTO
        = "total_packets_sent_octo";

    /**
     * The name of the stat indicating the total number of Octo packets which
     * were dropped (due to a failure to parse, or an unknown conference ID).
     */
    private static final String TOTAL_PACKETS_DROPPED_OCTO
        = "total_packets_dropped_octo";

    /**
     * The name of used memory statistic. Its runtime type is {@code Integer}.
     */
    public static final String USED_MEMORY = "used_memory";

    /**
     * The name of the number of video channels statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String VIDEOCHANNELS = "videochannels";

    /**
     * The name of the number of video streams statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String VIDEOSTREAMS = "videostreams";

    /**
     * The name of the "relay_id" statistic.
     */
    public static final String RELAY_ID = "relay_id";

    /**
     * The name of the "region" statistic.
     */
    public static final String REGION = "region";

    /**
     * The currently configured region.
     */
    public static String region = null;

    /**
     * The name of the property used to configure the region.
     */
    public static final String REGION_PNAME = "org.jitsi.videobridge.REGION";

    static
    {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Returns the current time stamp as a (formatted) <tt>String</tt>.
     * @return the current time stamp as a (formatted) <tt>String</tt>.
     */
    public static String currentTimeMillis()
    {
        return dateFormat.format(new Date());
    }

    /**
     * The indicator which determines whether {@link #generate()} is executing
     * on this <tt>VideobridgeStatistics</tt>. If <tt>true</tt>, invocations of
     * <tt>generate()</tt> will do nothing. Introduced in order to mitigate an
     * issue in which a blocking in <tt>generate()</tt> will cause a multiple of
     * threads to be initialized and blocked.
     */
    private boolean inGenerate = false;

    /**
     * Creates instance of <tt>VideobridgeStatistics</tt>.
     */
    public VideobridgeStatistics()
    {
        BundleContext bundleContext
            = StatsManagerBundleActivator.getBundleContext();

        ConfigurationService cfg
            = ServiceUtils.getService(bundleContext, ConfigurationService.class);
        if (cfg != null)
        {
            region = cfg.getString(REGION_PNAME, region);
        }

        // Is it necessary to set initial values for all of these?
        unlockedSetStat(AUDIOCHANNELS, 0);
        unlockedSetStat(BITRATE_DOWNLOAD, 0d);
        unlockedSetStat(BITRATE_UPLOAD, 0d);
        unlockedSetStat(CONFERENCES, 0);
        unlockedSetStat(CPU_USAGE, 0d);
        unlockedSetStat(NUMBEROFPARTICIPANTS, 0);
        unlockedSetStat(NUMBEROFTHREADS, 0);
        unlockedSetStat(RTP_LOSS, 0d);
        unlockedSetStat(TOTAL_MEMORY, 0);
        unlockedSetStat(USED_MEMORY, 0);
        unlockedSetStat(VIDEOCHANNELS, 0);
        unlockedSetStat(VIDEOSTREAMS, 0);
        unlockedSetStat(LOSS_RATE_DOWNLOAD, 0d);
        unlockedSetStat(LOSS_RATE_UPLOAD, 0d);
        unlockedSetStat(JITTER_AGGREGATE, 0d);
        unlockedSetStat(RTT_AGGREGATE, 0d);
        unlockedSetStat(LARGEST_CONFERENCE, 0);
        unlockedSetStat(CONFERENCE_SIZES, "[]");

        unlockedSetStat(TIMESTAMP, currentTimeMillis());
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
    protected void generate0()
    {
        BundleContext bundleContext
                = StatsManagerBundleActivator.getBundleContext();
        OctoRelayService relayService
                = ServiceUtils.getService(bundleContext, OctoRelayService.class);
        OctoRelay octoRelay
                = relayService == null ? null : relayService.getRelay();
        Videobridge videobridge
                = ServiceUtils.getService(bundleContext, Videobridge.class);
        Videobridge.Statistics jvbStats = videobridge.getStatistics();

        // TODO switch from channel counts to endpoint counts
        int audioChannels = 0, videoChannels = 0; // TODO verify
        int conferences = 0;
        int endpoints = 0;
        int videoStreams = 0; // TODO
        double fractionLostSum = 0d; // TODO verify
        int fractionLostCount = 0;  // TODO verify
        long packetsReceived = 0; // TODO verify (Transceiver)
        long packetsReceivedLost = 0; // TODO verify
        long bitrateDownloadBps = 0; // TODO verify (Transceiver)
        long bitrateUploadBps = 0; // TODO
        int packetRateUpload = 0; // TODO
        int packetRateDownload = 0; // TODO verify

        // Average jitter and RTT across MediaStreams which report a valid value.
        double jitterSumMs = 0; // TODO verify
        int jitterCount = 0; // TODO verify
        long rttSumMs = 0; // TODO verify (Transceiver)
        long rttCount = 0; // TODO verify
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];


        for (Conference conference : videobridge.getConferences())
        {
            ConferenceShim conferenceShim = conference.getShim();
            //TODO: can/should we do everything here via the shim only?
            if (!conference.includeInStatistics())
            {
                continue;
            }
            conferences++;
            int numConferenceEndpoints = conference.getEndpointCount();
            if (numConferenceEndpoints > largestConferenceSize)
            {
                largestConferenceSize = numConferenceEndpoints;
            }
            int conferenceSizeIndex
                    = numConferenceEndpoints < conferenceSizes.length
                    ? numConferenceEndpoints
                    : conferenceSizes.length - 1;
            conferenceSizes[conferenceSizeIndex]++;

            endpoints += numConferenceEndpoints;

            for (ContentShim contentShim : conferenceShim.getContents())
            {
                int contentChannelCount = contentShim.getChannelCount();
                MediaType mediaType = contentShim.getMediaType();
                if (MediaType.AUDIO.equals(mediaType))
                {
                    audioChannels += contentChannelCount;
                }
                else if (MediaType.VIDEO.equals(mediaType))
                {
                    videoChannels += contentChannelCount;
                }
            }
            for (AbstractEndpoint abstractEndpoint : conference.getEndpoints())
            {
                if (abstractEndpoint instanceof Endpoint)
                {
                    Endpoint endpoint = (Endpoint)abstractEndpoint;

                    TransceiverStats transceiverStats
                            = endpoint.transceiver.getTransceiverStats();
                    IncomingStatisticsSnapshot incomingStats
                            = transceiverStats.getIncomingStats();
                    bitrateDownloadBps += incomingStats.getBitrate();
                    packetRateDownload += incomingStats.getPacketRate();
                    for (IncomingSsrcStats.Snapshot ssrcStats
                            : incomingStats.getSsrcStats().values())
                    {
                        packetsReceived += ssrcStats.getNumRececivedPackets();

                        packetsReceivedLost += ssrcStats.getCumulativePacketsLost();

                        fractionLostCount++;
                        fractionLostSum += ssrcStats.getFractionLost() / 256;

                        Double ssrcJitter = ssrcStats.getJitter();
                        if (ssrcJitter != null && ssrcJitter != 0)
                        {
                            // We take the abs because otherwise the
                            // aggregate makes no sense.
                            jitterSumMs += Math.abs(ssrcJitter);
                            jitterCount++;
                        }

                    }

                    //for (OutgoingStreamStatistics.Snapshot ssrcStats
                    //    : transceiverStats.getOutgoingStreamStatistics().values())
                    //{
                        // packetRateUpload += sendStats.getPacketRate();
                        // bitrateUploadBps += sendStats.getBitrate();
                    //}

                    Double endpointRtt
                            = transceiverStats.getEndpointConnectionStats().getRtt();
                    if (endpointRtt != null && endpointRtt > 0)
                    {
                        rttSumMs += endpointRtt;
                        rttCount++;
                    }

                    // Assume we're receiving a video stream from the endpoint
                    int endpointStreams = 1;

                    // Assume we're sending one video stream to this endpoint
                    // for each other endpoint in the conference unless there's
                    // a limit imposed by lastN.
                    // TODO: can we get the actual numnber of streams that we're
                    // sending from the bitrate controller?
                    Integer lastN = endpoint.getLastN();
                    endpointStreams
                       += (lastN == null || lastN == -1)
                           ? (numConferenceEndpoints - 1)
                           : Math.min(lastN, numConferenceEndpoints - 1);

                   videoStreams += endpointStreams;
                }
            }
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
            ? ((double) rttSumMs) / rttCount
            : 0;

        // CONFERENCE_SIZES
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : conferenceSizes)
            conferenceSizesJson.add(size);

        // NUMBEROFTHREADS
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

        // OsStatistics
        OsStatistics osStatistics = OsStatistics.getOsStatistics();
        double cpuUsage = osStatistics.getCPUUsage();
        int totalMemory = osStatistics.getTotalMemory();
        int usedMemory = osStatistics.getUsedMemory();

        // TIMESTAMP
        String timestamp = currentTimeMillis();

        // Now that (the new values of) the statistics have been calculated and
        // the risks of the current thread hanging have been reduced as much as
        // possible, commit (the new values of) the statistics.
        Lock lock = this.lock.writeLock();

        lock.lock();
        try
        {
            unlockedSetStat(
                    BITRATE_DOWNLOAD,
                    bitrateDownloadBps / 1000 /* kbps */);
            // TODO (backend not implemented)
            unlockedSetStat(
                    BITRATE_UPLOAD,
                    bitrateUploadBps / 1000 /* kbps */);
            unlockedSetStat(PACKET_RATE_DOWNLOAD, packetRateDownload);
            // TODO (backend not implemenred)
            unlockedSetStat(PACKET_RATE_UPLOAD, packetRateUpload);
            // Keep for backward compatibility
            unlockedSetStat(
                    RTP_LOSS,
                    lossRateDownload + lossRateUpload);
            // TODO verify
            unlockedSetStat(LOSS_RATE_DOWNLOAD, lossRateDownload);
            // TODO verify
            unlockedSetStat(LOSS_RATE_UPLOAD, lossRateUpload);
            // TODO verify
            unlockedSetStat(JITTER_AGGREGATE, jitterAggregate);
            unlockedSetStat(RTT_AGGREGATE, rttAggregate);
            unlockedSetStat(AUDIOCHANNELS, audioChannels);
            // TODO
            unlockedSetStat(
                    TOTAL_FAILED_CONFERENCES,
                    jvbStats.totalFailedConferences.get());
            // TODO
            unlockedSetStat(
                    TOTAL_PARTIALLY_FAILED_CONFERENCES,
                    jvbStats.totalPartiallyFailedConferences.get());
            // TODO
            unlockedSetStat(
                    TOTAL_NO_PAYLOAD_CHANNELS,
                    jvbStats.totalNoPayloadChannels.get());
            // TODO
            unlockedSetStat(
                    TOTAL_NO_TRANSPORT_CHANNELS,
                    jvbStats.totalNoTransportChannels.get());
            unlockedSetStat(
                    TOTAL_CONFERENCES_CREATED,
                    jvbStats.totalConferencesCreated.get());
            unlockedSetStat(
                    TOTAL_CONFERENCES_COMPLETED,
                    jvbStats.totalConferencesCompleted.get());
            // TODO (backend not implemented)
            unlockedSetStat(
                    TOTAL_UDP_CONNECTIONS,
                    jvbStats.totalUdpTransportManagers.get());
            // TODO (backend not implemented)
            unlockedSetStat(
                    TOTAL_TCP_CONNECTIONS,
                    jvbStats.totalTcpTransportManagers.get());
            unlockedSetStat(
                    TOTAL_CONFERENCE_SECONDS,
                    jvbStats.totalConferenceSeconds.get());

            // TODO (backend not implemented)
            unlockedSetStat(
                    TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossControlledParticipantMs.get() / 1000);
            // TODO (backend not implemented)
            unlockedSetStat(
                    TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossLimitedParticipantMs.get() / 1000);
            // TODO (backend not implemented)
            unlockedSetStat(
                    TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS,
                   jvbStats.totalLossDegradedParticipantMs.get() / 1000);
            // TODO (Conference doesn't keep track).
            unlockedSetStat(TOTAL_CHANNELS, jvbStats.totalChannels.get());
            unlockedSetStat(CONFERENCES, conferences);
            unlockedSetStat(NUMBEROFPARTICIPANTS, endpoints);
            unlockedSetStat(VIDEOCHANNELS, videoChannels);
            unlockedSetStat(VIDEOSTREAMS, videoStreams);
            unlockedSetStat(LARGEST_CONFERENCE, largestConferenceSize);
            unlockedSetStat(CONFERENCE_SIZES, conferenceSizesJson);
            unlockedSetStat(NUMBEROFTHREADS, threadCount);
            unlockedSetStat(CPU_USAGE, Math.max(cpuUsage, 0));
            unlockedSetStat(TOTAL_MEMORY, Math.max(totalMemory, 0));
            unlockedSetStat(USED_MEMORY, Math.max(usedMemory, 0));
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
            // TODO (Conference doesn't keep track).
            unlockedSetStat(
                    TOTAL_BYTES_RECEIVED, jvbStats.totalBytesReceived.get());
            // TODO (Conference doesn't keep track).
            unlockedSetStat(TOTAL_BYTES_SENT, jvbStats.totalBytesSent.get());
            // TODO (Conference doesn't keep track).
            unlockedSetStat(
                    TOTAL_PACKETS_RECEIVED, jvbStats.totalPacketsReceived.get());
            // TODO (Conference doesn't keep track).
            unlockedSetStat(TOTAL_PACKETS_SENT, jvbStats.totalPacketsSent.get());

            unlockedSetStat(
                    TOTAL_BYTES_RECEIVED_OCTO,
                    octoRelay == null ? 0 : octoRelay.getBytesReceived());
            unlockedSetStat(
                    TOTAL_BYTES_SENT_OCTO,
                    octoRelay == null ? 0 : octoRelay.getBytesSent());
            unlockedSetStat(
                    TOTAL_PACKETS_RECEIVED_OCTO,
                    octoRelay == null ? 0 : octoRelay.getPacketsReceived());
            unlockedSetStat(
                    TOTAL_PACKETS_SENT_OCTO,
                    octoRelay == null ? 0 : octoRelay.getPacketsSent());
            unlockedSetStat(
                    TOTAL_PACKETS_DROPPED_OCTO,
                    octoRelay == null ? 0 : octoRelay.getPacketsDropped());

            unlockedSetStat(TIMESTAMP, timestamp);
            if (octoRelay != null)
            {
                unlockedSetStat(RELAY_ID, octoRelay.getId());
            }
            if (region != null)
            {
                unlockedSetStat(REGION, region);
            }
        }
        finally
        {
            lock.unlock();
        }
    }
}
