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
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.stats.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.utils.*;
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
        unlockedSetStat(BITRATE_DOWNLOAD, 0);
        unlockedSetStat(BITRATE_UPLOAD, 0);
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
        int audioChannels = 0, videoChannels = 0;
        int conferences = 0;
        int endpoints = 0;
        int videoStreams = 0;
        double fractionLostSum = 0d;
        int fractionLostCount = 0;
        long packetsReceived = 0, packetsReceivedLost = 0;
        long bitrateDownloadBps = 0, bitrateUploadBps = 0;
        // Average jitter and RTT across MediaStreams which report a valid value.
        double jitterSumMs = 0;
        long rttSumMs = 0;
        int jitterCount = 0, rttCount = 0;
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];
        int packetRateUpload = 0, packetRateDownload = 0;

        boolean shutdownInProgress = false;

        int totalConferencesCreated = 0, totalConferencesCompleted = 0,
            totalFailedConferences = 0, totalPartiallyFailedConferences = 0,
            totalNoTransportChannels = 0, totalNoPayloadChannels = 0,
            totalChannels = 0;
        long totalConferenceSeconds = 0,
            totalLossControlledParticipantSeconds = 0,
            totalLossLimitedParticipantSeconds = 0,
            totalLossDegradedParticipantSeconds = 0;
        int totalUdpConnections = 0, totalTcpConnections = 0;
        long totalDataChannelMessagesReceived = 0;
        long totalDataChannelMessagesSent = 0;
        long totalColibriWebSocketMessagesReceived = 0;
        long totalColibriWebSocketMessagesSent = 0;
        long totalBytesReceived = 0;
        long totalBytesSent = 0;
        long totalPacketsReceived = 0;
        long totalPacketsSent = 0;
        long totalBytesReceivedOcto = 0;
        long totalBytesSentOcto = 0;
        long totalPacketsReceivedOcto = 0;
        long totalPacketsSentOcto = 0;

        BundleContext bundleContext
            = StatsManagerBundleActivator.getBundleContext();

        OctoRelayService relayService
            = ServiceUtils.getService(bundleContext, OctoRelayService.class);
        String relayId = relayService == null ? null : relayService.getRelayId();

        for (Videobridge videobridge
                : Videobridge.getVideobridges(bundleContext))
        {
            Videobridge.Statistics jvbStats = videobridge.getStatistics();
            totalConferencesCreated += jvbStats.totalConferencesCreated.get();
            totalConferencesCompleted
                += jvbStats.totalConferencesCompleted.get();
            totalConferenceSeconds += jvbStats.totalConferenceSeconds.get();
            totalLossControlledParticipantSeconds
                += jvbStats.totalLossControlledParticipantMs.get() / 1000;
            totalLossLimitedParticipantSeconds
                += jvbStats.totalLossLimitedParticipantMs.get() / 1000;
            totalLossDegradedParticipantSeconds
                += jvbStats.totalLossDegradedParticipantMs.get() / 1000;
            totalFailedConferences += jvbStats.totalFailedConferences.get();
            totalPartiallyFailedConferences
                += jvbStats.totalPartiallyFailedConferences.get();
            totalNoTransportChannels += jvbStats.totalNoTransportChannels.get();
            totalNoPayloadChannels += jvbStats.totalNoPayloadChannels.get();
            totalChannels += jvbStats.totalChannels.get();
            totalUdpConnections += jvbStats.totalUdpTransportManagers.get();
            totalTcpConnections += jvbStats.totalTcpTransportManagers.get();
            totalDataChannelMessagesReceived
                += jvbStats.totalDataChannelMessagesReceived.get();
            totalDataChannelMessagesSent
                += jvbStats.totalDataChannelMessagesSent.get();
            totalColibriWebSocketMessagesReceived
                += jvbStats.totalColibriWebSocketMessagesReceived.get();
            totalColibriWebSocketMessagesSent
                += jvbStats.totalColibriWebSocketMessagesSent.get();
            totalBytesReceived += jvbStats.totalBytesReceived.get();
            totalBytesSent += jvbStats.totalBytesSent.get();
            totalPacketsReceived += jvbStats.totalPacketsReceived.get();
            totalPacketsSent += jvbStats.totalPacketsSent.get();
            totalBytesReceivedOcto += jvbStats.totalBytesReceivedOcto.get();
            totalBytesSentOcto += jvbStats.totalBytesSentOcto.get();
            totalPacketsReceivedOcto += jvbStats.totalPacketsReceivedOcto.get();
            totalPacketsSentOcto += jvbStats.totalPacketsSentOcto.get();


            for (Conference conference : videobridge.getConferences())
            {
                if (!conference.includeInStatistics())
                {
                    continue;
                }

                conferences++;
                int conferenceEndpoints = conference.getEndpointCount();
                endpoints += conference.getEndpointCount();
                if (conferenceEndpoints > largestConferenceSize)
                {
                    largestConferenceSize = conferenceEndpoints;
                }

                int idx
                    = conferenceEndpoints < conferenceSizes.length
                    ? conferenceEndpoints
                    : conferenceSizes.length - 1;
                conferenceSizes[idx]++;

                for (Content content : conference.getContents())
                {
                    MediaType mediaType = content.getMediaType();
                    int contentChannelCount = content.getChannelCount();

                    if (MediaType.AUDIO.equals(mediaType))
                        audioChannels += contentChannelCount;
                    else if (MediaType.VIDEO.equals(mediaType))
                        videoChannels += content.getChannelCount();

                    for (Channel channel : content.getChannels())
                    {
                        if (channel instanceof RtpChannel)
                        {
                            RtpChannel rtpChannel = (RtpChannel) channel;
                            MediaStream stream = rtpChannel.getStream();
                            if (stream == null)
                            {
                                continue;
                            }
                            MediaStreamStats2 stats
                                = stream.getMediaStreamStats();
                            ReceiveTrackStats receiveStats
                                = stats.getReceiveStats();
                            SendTrackStats sendStats = stats.getSendStats();

                            packetsReceived += receiveStats.getCurrentPackets();
                            packetsReceivedLost
                                += receiveStats.getCurrentPacketsLost();
                            fractionLostCount += 1;
                            fractionLostSum += sendStats.getLossRate();
                            packetRateDownload += receiveStats.getPacketRate();
                            packetRateUpload += sendStats.getPacketRate();

                            bitrateDownloadBps += receiveStats.getBitrate();
                            bitrateUploadBps += sendStats.getBitrate();

                            double jitter = sendStats.getJitter();
                            if (jitter != TrackStats.JITTER_UNSET)
                            {
                                // We take the abs because otherwise the
                                // aggregate makes no sense.
                                jitterSumMs += Math.abs(jitter);
                                jitterCount++;
                            }
                            jitter = receiveStats.getJitter();
                            if (jitter != TrackStats.JITTER_UNSET)
                            {
                                // We take the abs because otherwise the
                                // aggregate makes no sense.
                                jitterSumMs += Math.abs(jitter);
                                jitterCount++;
                            }

                            long rtt = sendStats.getRtt();
                            if (rtt > 0)
                            {
                                rttSumMs += rtt;
                                rttCount++;
                            }

                            if (channel instanceof VideoChannel)
                            {
                                VideoChannel videoChannel
                                    = (VideoChannel) channel;

                                //assume we're receiving a stream
                                int channelStreams = 1;
                                int lastN = videoChannel.getLastN();
                                channelStreams
                                    += (lastN == -1)
                                        ? (contentChannelCount - 1)
                                        : Math.min(
                                                lastN, contentChannelCount - 1);

                                videoStreams += channelStreams;
                            }
                        }
                    }
                }
            }

            if (videobridge.isShutdownInProgress())
            {
                shutdownInProgress = true;
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
            unlockedSetStat(LOSS_RATE_DOWNLOAD, lossRateDownload);
            unlockedSetStat(LOSS_RATE_UPLOAD, lossRateUpload);
            unlockedSetStat(JITTER_AGGREGATE, jitterAggregate);
            unlockedSetStat(RTT_AGGREGATE, rttAggregate);
            unlockedSetStat(AUDIOCHANNELS, audioChannels);
            unlockedSetStat(TOTAL_FAILED_CONFERENCES, totalFailedConferences);
            unlockedSetStat(
                    TOTAL_PARTIALLY_FAILED_CONFERENCES,
                    totalPartiallyFailedConferences);
            unlockedSetStat(
                    TOTAL_NO_PAYLOAD_CHANNELS,
                    totalNoPayloadChannels);
            unlockedSetStat(
                    TOTAL_NO_TRANSPORT_CHANNELS,
                    totalNoTransportChannels);
            unlockedSetStat(
                    TOTAL_CONFERENCES_CREATED,
                    totalConferencesCreated);
            unlockedSetStat(
                    TOTAL_CONFERENCES_COMPLETED,
                    totalConferencesCompleted);
            unlockedSetStat(TOTAL_UDP_CONNECTIONS, totalUdpConnections);
            unlockedSetStat(TOTAL_TCP_CONNECTIONS, totalTcpConnections);
            unlockedSetStat(TOTAL_CONFERENCE_SECONDS, totalConferenceSeconds);
            unlockedSetStat(TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS,
                totalLossControlledParticipantSeconds);
            unlockedSetStat(TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS,
                    totalLossLimitedParticipantSeconds);
            unlockedSetStat(TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS,
                    totalLossDegradedParticipantSeconds);
            unlockedSetStat(TOTAL_CHANNELS, totalChannels);
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
            unlockedSetStat(SHUTDOWN_IN_PROGRESS, shutdownInProgress);
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED,
                            totalDataChannelMessagesReceived);
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_SENT,
                            totalDataChannelMessagesSent);
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED,
                            totalColibriWebSocketMessagesReceived);
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT,
                            totalColibriWebSocketMessagesSent);
            unlockedSetStat(TOTAL_BYTES_RECEIVED, totalBytesReceived);
            unlockedSetStat(TOTAL_BYTES_SENT, totalBytesSent);
            unlockedSetStat(TOTAL_PACKETS_RECEIVED, totalPacketsReceived);
            unlockedSetStat(TOTAL_PACKETS_SENT, totalPacketsSent);
            unlockedSetStat(TOTAL_BYTES_RECEIVED_OCTO, totalBytesReceivedOcto);
            unlockedSetStat(TOTAL_BYTES_SENT_OCTO, totalBytesSentOcto);
            unlockedSetStat(TOTAL_PACKETS_RECEIVED_OCTO,
                            totalPacketsReceivedOcto);
            unlockedSetStat(TOTAL_PACKETS_SENT_OCTO, totalPacketsSentOcto);

            unlockedSetStat(TIMESTAMP, timestamp);
            if (relayId != null)
            {
                unlockedSetStat(RELAY_ID, relayId);
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
