/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.text.*;
import java.util.*;

import org.jitsi.videobridge.stats.*;

/**
 * Implements statistics that are collected by the Videobridge.
 *
 * @author Hristo Terezov
 */
public class VideobridgeStatistics extends Statistics
{
    /**
     * The instance of <tt>VideobridgeStatistics</tt>.
     */
    private static VideobridgeStatistics instance;

    /**
     * Date formatter.
     */
    private static DateFormat dateFormat = null;

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_AUDIOCHANNELS = "audiochannels";

    /**
     * The name of the bit rate statistic for download.
     */
    public static final String VIDEOBRIDGESTATS_BITRATE_DOWNLOAD
        = "bit_rate_download";

    /**
     * The name of the bit rate statistic for upload.
     */
    public static final String VIDEOBRIDGESTATS_BITRATE_UPLOAD
        = "bit_rate_upload";

    /**
     * The name of the CPU usage statistic.
     */
    public static String VIDEOBRIDGESTATS_CPU_USAGE = "cpu_usage";

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_CONFERENCES = "conferences";

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_NUMBEROFPARTICIPANTS = "participants";

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_NUMBEROFTHREADS = "threads";

    /**
     * The name of the RTP loss statistic.
     */
    public static final String VIDEOBRIDGESTATS_RTP_LOSS = "rtp_loss";

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_TIMESTAMP = "current_timestamp";

    /**
     * The name of total memory statistic.
     */
    public static String VIDEOBRIDGESTATS_TOTAL_MEMORY = "total_memory";

    /**
     * The name of used memory statistic.
     */
    public static String VIDEOBRIDGESTATS_USED_MEMORY = "used_memory";

    /**
     * The name of the number of conferences statistic.
     */
    public static String VIDEOBRIDGESTATS_VIDEOCHANNELS = "videochannels";

    /**
     * Returns bit rate value in Kb/s
     * @param bytes number of bytes
     * @param period the period of time in milliseconds
     * @return the bit rate
     */
    public static double calculateBitRate(long bytes, int period)
    {
        return (bytes * 8.0) / period;
    }

    /**
     * Returns current time stamp as  formatted<tt>String</tt>.
     * @return current time stamp as  formatted<tt>String</tt>.
     */
    public static String getCurrentTimestampValue()
    {
        if(dateFormat == null)
        {
            dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
        return dateFormat.format(new Date());
    }

    /**
     * Returns the instance of <tt>VideobridgeStatistics</tt>.
     * @return the instance of <tt>VideobridgeStatistics</tt>.
     */
    public static VideobridgeStatistics getStatistics()
    {
        if (instance == null)
            instance = new VideobridgeStatistics();
        return instance;
    }

    /**
     * Disables statistic.
     * @param stat the statistic to be disabled.
     */
    private void disableStat(String stat)
    {
        stats.remove(stat);
    }

    /**
     * Enables statistic.
     * @param stat the statistic to be enabled.
     */
    private void enableStat(String stat)
    {
        if(!stats.containsKey(stat))
            stats.put(stat, new Object());
    }

    @Override
    public void setStat(String stat, Object value)
    {
        if(stat == null)
            return;

        if(VideobridgeStatistics.VIDEOBRIDGESTATS_TOTAL_MEMORY.equals(stat)
            || VideobridgeStatistics.VIDEOBRIDGESTATS_USED_MEMORY.equals(stat))
        {
            if((Integer) value == -1)
            {
                disableStat(stat);
                return;
            }
        }

        if(VideobridgeStatistics.VIDEOBRIDGESTATS_CPU_USAGE.equals(stat))
        {
            if("-1".equals(value))
            {
                disableStat(stat);
                return;
            }

            enableStat(stat);
        }

        super.setStat(stat, value);
    }

    /**
     * Creates instance of <tt>VideobridgeStatistics</tt>.
     */
    private VideobridgeStatistics()
    {
        stats = new HashMap<String, Object>();
        stats.put(VIDEOBRIDGESTATS_CONFERENCES, 0);
        stats.put(VIDEOBRIDGESTATS_AUDIOCHANNELS, 0);
        stats.put(VIDEOBRIDGESTATS_VIDEOCHANNELS, 0);
        stats.put(VIDEOBRIDGESTATS_NUMBEROFTHREADS, 0);
        stats.put(VIDEOBRIDGESTATS_NUMBEROFPARTICIPANTS, 0);
        stats.put(VIDEOBRIDGESTATS_RTP_LOSS, 0);
        stats.put(VIDEOBRIDGESTATS_BITRATE_DOWNLOAD, 0);
        stats.put(VIDEOBRIDGESTATS_BITRATE_UPLOAD, 0);
        stats.put(VIDEOBRIDGESTATS_CPU_USAGE, 0);
        stats.put(VIDEOBRIDGESTATS_TIMESTAMP, getCurrentTimestampValue());
        stats.put(VIDEOBRIDGESTATS_TOTAL_MEMORY, 0);
        stats.put(VIDEOBRIDGESTATS_USED_MEMORY, 0);
    }
}
