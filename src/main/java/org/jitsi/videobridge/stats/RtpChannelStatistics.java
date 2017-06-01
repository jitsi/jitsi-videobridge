package org.jitsi.videobridge.stats;

import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.neomedia.MediaStreamStats;
import org.jitsi.videobridge.RtpChannel;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;

/**
 * Created by brian on 3/15/2016.
 */
public class RtpChannelStatistics
    extends Statistics
{
    /**
     * The <tt>DateFormat</tt> to be utilized by <tt>VideobridgeStatistics</tt>
     * in order to represent time and date as <tt>String</tt>.
     */
    private static final DateFormat dateFormat;

    private static final DecimalFormat decimalFormat
            = new DecimalFormat("#.#####");

    /**
     * The indicator which determines whether {@link #generate()} is executing
     * on this <tt>VideobridgeStatistics</tt>. If <tt>true</tt>, invocations of
     * <tt>generate()</tt> will do nothing. Introduced in order to mitigate an
     * issue in which a blocking in <tt>generate()</tt> will cause a multiple of
     * threads to be initialized and blocked.
     */
    private boolean inGenerate = false;

    private String channelId;
    private WeakReference<RtpChannel> weakChannel;
    private WeakReference<StatsManager> weakStatsManager;

    static
    {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public RtpChannelStatistics(String channelId, RtpChannel channel)
    {
        this.channelId = channelId;
        this.weakChannel = new WeakReference<>(channel);
        this.weakStatsManager = new WeakReference<>(ServiceUtils2.getService(StatsManagerBundleActivator.getBundleContext(), StatsManager.class));

        weakStatsManager.get().addStatistics(this, 1000);
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
    static int dummyValue = 0;
    protected void generate0()
    {
        MediaStreamStats streamStats = this.weakChannel.get().getStream().getMediaStreamStats();
        streamStats.updateStats();
        long numIncomingPacketsLost = streamStats.getNbPacketsLost();
        long numIncomingPackets = streamStats.getNbPackets();


        // Now that (the new values of) the statistics have been calculated and
        // the risks of the current thread hanging have been reduced as much as
        // possible, commit (the new values of) the statistics.
        Lock lock = this.lock.writeLock();
        lock.lock();
        try
        {
            unlockedSetStat(this.channelId + ".incomingPackets", numIncomingPackets);
            unlockedSetStat(this.channelId + ".incomingPacketsLost", numIncomingPacketsLost);
        }
        finally
        {
            lock.unlock();
        }
    }
}
