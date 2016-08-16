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

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
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
     * The name of the bit rate statistic for download. Its runtime type is
     * {@code String} and the value represents a {@code double} value.
     */
    public static final String BITRATE_DOWNLOAD = "bit_rate_download";

    /**
     * The name of the bit rate statistic for upload. Its runtime type is
     * {@code String} and the value represents a {@code double} value.
     */
    public static final String BITRATE_UPLOAD = "bit_rate_upload";

    /**
     * The name of the number of conferences statistic. Its runtime type is
     * {@code Integer}.
     */
    public static final String CONFERENCES = "conferences";

    /**
     * The name of the CPU usage statistic. Its runtime type is {@code String}
     * and the value represents a {@code double} value.
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
     * The name of the RTP loss statistic. Its runtime type is {@code String}
     * and the value represents a {@code double} value.
     */
    public static final String RTP_LOSS = "rtp_loss";

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
    private static final int CONFERENCE_SIZE_BUCKETS = 15;

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
     * The name of the total number of conferences (failed + succeeded).
     */
    private static final String TOTAL_CONFERENCES
        = "total_conferences";

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

    static
    {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Returns bit rate value in Kb/s
     * @param bytes number of bytes
     * @param period the period of time in milliseconds
     * @return the bit rate
     */
    private static double calculateBitRate(long bytes, long period)
    {
        return (bytes * 8.0d) / period;
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
     * The time in milliseconds at which {@link #generate()} was invoked last.
     */
    private long lastGenerateTime;

    /**
     * Creates instance of <tt>VideobridgeStatistics</tt>.
     */
    public VideobridgeStatistics()
    {
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
        long packets = 0, packetsLost = 0;
        long bytesReceived = 0, bytesSent = 0;
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];
        boolean shutdownInProgress = false;

        int totalConferences = 0, totalFailedConferences = 0,
            totalPartiallyFailedConferences = 0,
            totalNoTransportChannels = 0,
            totalNoPayloadChannels = 0, totalChannels = 0;

        BundleContext bundleContext
            = StatsManagerBundleActivator.getBundleContext();

        if (bundleContext != null)
        {
            for (Videobridge videobridge
                    : Videobridge.getVideobridges(bundleContext))
            {
                Videobridge.Statistics jvbStats = videobridge.getStatistics();
                totalConferences += jvbStats.totalConferences.intValue();
                totalFailedConferences
                    += jvbStats.totalFailedConferences.intValue();
                totalPartiallyFailedConferences
                    += jvbStats.totalPartiallyFailedConferences.intValue();
                totalNoTransportChannels
                    += jvbStats.totalNoTransportChannels.intValue();
                totalNoPayloadChannels
                    += jvbStats.totalNoPayloadChannels.intValue();
                totalChannels
                    += jvbStats.totalChannels.intValue();

                for (Conference conference : videobridge.getConferences())
                {
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

                        if(MediaType.AUDIO.equals(mediaType))
                            audioChannels += contentChannelCount;
                        else if(MediaType.VIDEO.equals(mediaType))
                            videoChannels += content.getChannelCount();

                        for(Channel channel : content.getChannels())
                        {
                            if(channel instanceof RtpChannel)
                            {
                                RtpChannel rtpChannel = (RtpChannel) channel;

                                packets += rtpChannel.getLastPacketsNB();
                                packetsLost
                                    += rtpChannel.getLastPacketsLostNB();
                                bytesReceived
                                    += rtpChannel.getNBReceivedBytes();
                                bytesSent += rtpChannel.getNBSentBytes();

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
                                                    lastN,
                                                    contentChannelCount - 1);

                                    videoStreams += channelStreams;
                                }
                            }
                        }
                    }
                    conferences++;
                }
                if (videobridge.isShutdownInProgress())
                {
                    shutdownInProgress = true;
                }
            }
        }

        // BITRATE_DOWNLOAD, BITRATE_UPLOAD
        long now = System.currentTimeMillis();

        // RTP_LOSS
        double rtpLoss
            = ((packetsLost > 0) && (packets > 0))
                ? ((double) packetsLost) / packets
                : 0.0d;

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
            double bitrateDownload = 0.0d;
            double bitrateUpload = 0.0d;

            if (lastGenerateTime != 0)
            {
                long period = now - lastGenerateTime;

                if (period > 0)
                {
                    bitrateDownload = calculateBitRate(bytesReceived, period);
                    bitrateUpload = calculateBitRate(bytesSent, period);
                }
            }
            lastGenerateTime = now;

            unlockedSetStat(BITRATE_DOWNLOAD, bitrateDownload);
            unlockedSetStat(BITRATE_UPLOAD, bitrateUpload);

            unlockedSetStat(RTP_LOSS, rtpLoss);

            unlockedSetStat(AUDIOCHANNELS, audioChannels);
            unlockedSetStat(TOTAL_FAILED_CONFERENCES, totalFailedConferences);
            unlockedSetStat(TOTAL_PARTIALLY_FAILED_CONFERENCES,
                totalPartiallyFailedConferences);
            unlockedSetStat(TOTAL_NO_PAYLOAD_CHANNELS,
                totalNoPayloadChannels);
            unlockedSetStat(TOTAL_NO_TRANSPORT_CHANNELS,
                totalNoTransportChannels);
            unlockedSetStat(TOTAL_CONFERENCES, totalConferences);
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

            unlockedSetStat(TIMESTAMP, timestamp);
        }
        finally
        {
            lock.unlock();
        }
    }
}
