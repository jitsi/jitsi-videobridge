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

import org.apache.commons.lang3.StringUtils;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.shim.*;
import org.json.simple.*;
import org.osgi.framework.*;

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
    private static final DateFormat dateFormat;

    /**
     * The number of buckets to use for conference sizes.
     */
    private static final int CONFERENCE_SIZE_BUCKETS = 22;

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

        // Is it necessary to set initial values for all of these?
        unlockedSetStat(BITRATE_DOWNLOAD, 0);
        unlockedSetStat(BITRATE_UPLOAD, 0);
        unlockedSetStat(CONFERENCES, 0);
        unlockedSetStat(CPU_USAGE, 0d);
        unlockedSetStat(PARTICIPANTS, 0);
        unlockedSetStat(THREADS, 0);
        unlockedSetStat(RTP_LOSS, 0d);
        unlockedSetStat(TOTAL_MEMORY, 0);
        unlockedSetStat(USED_MEMORY, 0);
        unlockedSetStat(VIDEO_CHANNELS, 0);
        unlockedSetStat(VIDEO_STREAMS, 0);
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
                = ServiceUtils2.getService(bundleContext, OctoRelayService.class);
        OctoRelay octoRelay
                = relayService == null ? null : relayService.getRelay();
        Videobridge videobridge
                = ServiceUtils2.getService(bundleContext, Videobridge.class);
        Videobridge.Statistics jvbStats = videobridge.getStatistics();

        int videoChannels = 0;
        int conferences = 0;
        int endpoints = 0;
        int videoStreams = 0;
        double fractionLostSum = 0d; // TODO verify
        int fractionLostCount = 0;
        long packetsReceived = 0; // TODO verify (Transceiver)
        long packetsReceivedLost = 0; // TODO verify
        long bitrateDownloadBps = 0;
        long bitrateUploadBps = 0;
        int packetRateUpload = 0;
        int packetRateDownload = 0;

        // Average jitter and RTT across MediaStreams which report a valid value.
        double jitterSumMs = 0; // TODO verify
        int jitterCount = 0;
        long rttSumMs = 0; // TODO verify (Transceiver)
        long rttCount = 0;
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
                if (MediaType.VIDEO.equals(contentShim.getMediaType()))
                {
                    videoChannels += contentShim.getChannelCount();
                }
            }
            for (Endpoint endpoint : conference.getLocalEndpoints())
            {
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

                Double endpointRtt
                        = transceiverStats.getEndpointConnectionStats().getRtt();
                if (endpointRtt > 0)
                {
                    rttSumMs += endpointRtt;
                    rttCount++;
                }

                // Assume we're receiving a video stream from the endpoint
                int endpointStreams = 1;

                // Assume we're sending one video stream to this endpoint
                // for each other endpoint in the conference unless there's
                // a limit imposed by lastN.
                Integer lastN = endpoint.getLastN();
                endpointStreams
                   += lastN == -1
                       ? numConferenceEndpoints - 1
                       : Math.min(lastN, numConferenceEndpoints - 1);

               videoStreams += endpointStreams;
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

        // THREADS
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
            unlockedSetStat(CONFERENCES, conferences);
            unlockedSetStat(PARTICIPANTS, endpoints);
            unlockedSetStat(VIDEO_CHANNELS, videoChannels);
            unlockedSetStat(VIDEO_STREAMS, videoStreams);
            unlockedSetStat(LARGEST_CONFERENCE, largestConferenceSize);
            unlockedSetStat(CONFERENCE_SIZES, conferenceSizesJson);
            unlockedSetStat(THREADS, threadCount);
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
            unlockedSetStat(
                    TOTAL_BYTES_RECEIVED, jvbStats.totalBytesReceived.get());
            unlockedSetStat(TOTAL_BYTES_SENT, jvbStats.totalBytesSent.get());
            unlockedSetStat(
                    TOTAL_PACKETS_RECEIVED, jvbStats.totalPacketsReceived.get());
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
            unlockedSetStat(
                    OCTO_RECEIVE_BITRATE,
                    octoRelay == null
                            ? 0 : (octoRelay.getReceiveBitrate() + 500) / 1000);
            unlockedSetStat(
                    OCTO_SEND_BITRATE,
                    octoRelay == null
                            ? 0 : (octoRelay.getSendBitrate() + 500) / 1000);

            unlockedSetStat(TIMESTAMP, timestamp);
            if (octoRelay != null)
            {
                unlockedSetStat(RELAY_ID, octoRelay.getId());
            }
            if (OctoConfig.region.get() != null)
            {
                unlockedSetStat(REGION, OctoConfig.region.get());
            }
            unlockedSetStat(VERSION, videobridge.getVersion().toString());
        }
        finally
        {
            lock.unlock();
        }
    }
}
