/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.lang.management.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.locks.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
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
     * The name of the number of conferences statistic.
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
     * The name of the number of conferences statistic.
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

    private static final DecimalFormat decimalFormat
        = new DecimalFormat("#.#####");

    /**
     * The name of the number of conferences statistic.
     */
    public static final String NUMBEROFPARTICIPANTS = "participants";

    /**
     * The name of the number of conferences statistic.
     */
    public static final String NUMBEROFTHREADS = "threads";

    /**
     * The name of the RTP loss statistic.
     */
    public static final String RTP_LOSS = "rtp_loss";

    /**
     * The name of the number of conferences statistic.
     */
    public static final String TIMESTAMP = "current_timestamp";

    /**
     * The name of total memory statistic.
     */
    public static final String TOTAL_MEMORY = "total_memory";

    /**
     * The name of used memory statistic.
     */
    public static final String USED_MEMORY = "used_memory";

    /**
     * The name of the number of conferences statistic.
     */
    public static final String VIDEOCHANNELS = "videochannels";

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
        unlockedSetStat(BITRATE_DOWNLOAD, decimalFormat.format(0.0d));
        unlockedSetStat(BITRATE_UPLOAD, decimalFormat.format(0.0d));
        unlockedSetStat(CONFERENCES, 0);
        unlockedSetStat(CPU_USAGE, decimalFormat.format(0.0d));
        unlockedSetStat(NUMBEROFPARTICIPANTS, 0);
        unlockedSetStat(NUMBEROFTHREADS, 0);
        unlockedSetStat(RTP_LOSS, decimalFormat.format(0.0d));
        unlockedSetStat(TOTAL_MEMORY, 0);
        unlockedSetStat(USED_MEMORY, 0);
        unlockedSetStat(VIDEOCHANNELS, 0);

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
        long packets = 0, packetsLost = 0;
        long bytesReceived = 0, bytesSent = 0;

        BundleContext bundleContext
            = StatsManagerBundleActivator.getBundleContext();

        if (bundleContext != null)
        {
            for (Videobridge videobridge
                    : Videobridge.getVideobridges(bundleContext))
            {
                for (Conference conference : videobridge.getConferences())
                {
                    for (Content content : conference.getContents())
                    {
                        MediaType mediaType = content.getMediaType();

                        if(MediaType.AUDIO.equals(mediaType))
                            audioChannels += content.getChannelCount();
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
                            }
                        }
                    }
                    conferences++;
                    endpoints += conference.getEndpointsCount();
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

            unlockedSetStat(
                    BITRATE_DOWNLOAD,
                    decimalFormat.format(bitrateDownload));
            unlockedSetStat(
                    BITRATE_UPLOAD,
                    decimalFormat.format(bitrateUpload));

            unlockedSetStat(RTP_LOSS, decimalFormat.format(rtpLoss));

            unlockedSetStat(AUDIOCHANNELS, audioChannels);
            unlockedSetStat(CONFERENCES, conferences);
            unlockedSetStat(NUMBEROFPARTICIPANTS, endpoints);
            unlockedSetStat(VIDEOCHANNELS, videoChannels);

            unlockedSetStat(NUMBEROFTHREADS, threadCount);

            unlockedSetStat(
                    CPU_USAGE,
                    (cpuUsage < 0) ? null : decimalFormat.format(cpuUsage));
            unlockedSetStat(
                    TOTAL_MEMORY,
                    (totalMemory < 0) ? null : totalMemory);
            unlockedSetStat(USED_MEMORY, (usedMemory < 0) ? null : usedMemory);

            unlockedSetStat(TIMESTAMP, timestamp);
        }
        finally
        {
            lock.unlock();
        }
    }
}
