/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

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
     * Returns the instance of <tt>VideobridgeStatistics</tt>.
     * @return the instance of <tt>VideobridgeStatistics</tt>.
     */
    public static Statistics getStatistics()
    {
        if (instance == null)
            instance = new VideobridgeStatistics();
        return instance;
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
    }
}
