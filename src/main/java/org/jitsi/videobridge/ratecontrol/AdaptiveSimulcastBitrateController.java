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
package org.jitsi.videobridge.ratecontrol;

import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;
import org.jitsi.videobridge.transform.*;

/**
 * Monitors the available bandwidth and the sending bitrate to the owning
 * endpoint, and if it detects that we are oversending, disables the highest
 * simulcast layer.
 *
 * @author Boris Grozev
 */
public class AdaptiveSimulcastBitrateController
    extends BitrateController
    implements BandwidthEstimator.Listener,
               RecurringRunnable
{
    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    /**
     * The interval at which {@link #run()} should be called, in
     * milliseconds.
     */
    private static int PROCESS_INTERVAL_MS = 500;

    /**
     * The name of the property which controls the value of {@link
     * #PROCESS_INTERVAL_MS}.
     */
    private static final String PROCESS_INTERVAL_MS_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".PROCESS_INTERVAL_MS";

    /**
     * See {@link #CLIMB_MIN_RATIO}.
     * This should probably be kept <OVERSENDING_INTERVAL, otherwise we will not
     * detect the climb quickly enough after a drop in BWE and might trigger
     * prematurely.
     */
    private static int CLIMB_INTERVAL_MS = 4500;

    /**
     * The name of the property which controls the value of {@link
     * #CLIMB_INTERVAL_MS}.
     */
    private static final String CLIMB_INTERVAL_MS_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".CLIMB_INTERVAL_MS";

    /**
     * We consider that the bandwidth estimation is in a climb if the values
     * have not been decreasing in the last {@link #CLIMB_INTERVAL_MS} and the
     * max/min ratio is at least CLIMB_MIN_RATIO.
     */
    private static double CLIMB_MIN_RATIO = 1.05;

    /**
     * The name of the property which controls the value of {@link
     * #CLIMB_MIN_RATIO}.
     */
    private static final String CLIMB_MIN_RATIO_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".CLIMB_MIN_RATIO";

    /**
     * We consider that we are oversending if we are sending at least
     * OVERSENDING_COEF times the current bandwidth estimation.
     */
    private static double OVERSENDING_COEF = 1.3;

    /**
     * The name of the property which controls the value of {@link
     * #OVERSENDING_COEF}.
     */
    private static final String OVERSENDING_COEF_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".OVERSENDING_COEF_PNAME";

    /**
     * The length in milliseconds of the interval during which we have to be
     * oversending before we trigger.
     */
    private static int OVERSENDING_INTERVAL_MS = 5000;

    /**
     * The name of the property which controls the value of {@link
     * #OVERSENDING_INTERVAL_MS}.
     */
    private static final String OVERSENDING_INTERVAL_MS_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".OVERSENDING_INTERVAL_MS_PNAME";

    /**
     * The length of the interval (in milliseconds) after the first data point
     * during which triggering is disabled.
     * Should probably be kept >CLIMB_INTERVAL_MS, since in the first
     * CLIMB_INTERVAL_MS the climb detection may give false-negatives.
     */
    private static int INITIAL_PERIOD_MS = 8000;

    /**
     * The name of the property which controls the value of {@link
     * #INITIAL_PERIOD_MS}.
     */
    private static final String INITIAL_PERIOD_MS_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".INITIAL_PERIOD_MS";

    /**
     * The "target order" of the high quality layer.
     */
    private static final int TARGET_ORDER_HD = 2;

    /**
     * Whether or not to actually disable the HD layer if the right conditions
     * occur. Keeping it off while adaptive-simulcast is enabled allows for data
     * collection without disruptions.
     */
    private static boolean ENABLE_TRIGGER = true;

    /**
     * The name of the property which controls the value of {@link
     * #ENABLE_TRIGGER}.
     */
    private static final String ENABLE_TRIGGER_PNAME
        = AdaptiveSimulcastBitrateController.class.getName()
            + ".ENABLE_TRIGGER";

    /**
     * The <tt>Logger</tt> used by the {@link
     * AdaptiveSimulcastBitrateController} class and its instances to print
     * debug information.
     */
    private static final org.jitsi.util.Logger logger
        = org.jitsi.util.Logger.getLogger(
                AdaptiveSimulcastBitrateController.class);

    /**
     * The {@link RecurringRunnableExecutor} which will periodically call
     * {@link #run()} on active {@link AdaptiveSimulcastBitrateController}
     * instances.
     */
    private static RecurringRunnableExecutor recurringRunnablesExecutor;

    /**
     * Initializes the constants used by this class from the configuration.
     */
    private static void initializeConfiguration(ConfigurationService cfg)
    {
        synchronized (AdaptiveSimulcastBitrateController.class)
        {
            if (configurationInitialized)
                return;
            configurationInitialized = true;

            if (cfg != null)
            {
                PROCESS_INTERVAL_MS
                    = cfg.getInt(PROCESS_INTERVAL_MS_PNAME, PROCESS_INTERVAL_MS);
                CLIMB_INTERVAL_MS
                    = cfg.getInt(CLIMB_INTERVAL_MS_PNAME, CLIMB_INTERVAL_MS);
                CLIMB_MIN_RATIO
                    = cfg.getDouble(CLIMB_MIN_RATIO_PNAME, CLIMB_MIN_RATIO);
                OVERSENDING_COEF
                    = cfg.getDouble(OVERSENDING_COEF_PNAME, OVERSENDING_COEF);
                OVERSENDING_INTERVAL_MS
                    = cfg.getInt(
                        OVERSENDING_INTERVAL_MS_PNAME, OVERSENDING_INTERVAL_MS);
                INITIAL_PERIOD_MS
                    = cfg.getInt(INITIAL_PERIOD_MS_PNAME, INITIAL_PERIOD_MS);
                ENABLE_TRIGGER
                    = cfg.getBoolean(ENABLE_TRIGGER_PNAME, ENABLE_TRIGGER);
            }

            recurringRunnablesExecutor
                = new RecurringRunnableExecutor(
                    AdaptiveSimulcastBitrateController.class.getSimpleName());
        }
    }

    /**
     * The {@link VideoChannel} which owns this {@link
     * AdaptiveSimulcastBitrateController}.
     */
    private final VideoChannel channel;

    /**
     * The latest estimation of the available bandwidth as reported by our
     * {@link Channel}'s {@link MediaStream}'s {@link BandwidthEstimator}.
     */
    private long latestBwe = -1;

    /**
     * The time that {@link #run()} was last called.
     */
    private long lastUpdateTime = -1;

    /**
     * Whether this {@link AdaptiveSimulcastBitrateController} has triggered
     * suppression of the HD layer.
     */
    private boolean triggered = false;

    /**
     * A queue which holds recent data about the sending bitrate and available
     * bandwidth.
     */
    private History history = new History();

    /**
     * Initializes a new {@link AdaptiveSimulcastBitrateController} instance.
     *
     * @param channel the {@link VideoChannel} which owns this instance.
     * serve.
     */
    public AdaptiveSimulcastBitrateController(
                LastNController lastNController,
                VideoChannel channel)
    {
        this.channel = channel;

        initializeConfiguration(
                ServiceUtils.getService(
                        channel.getBundleContext(),
                        ConfigurationService.class));

        // Create a bandwidth estimator and hook us up to changes to the
        // estimation.
        BandwidthEstimator be
            = ((VideoMediaStream) channel.getStream())
                .getOrCreateBandwidthEstimator();
        be.addListener(this);

        recurringRunnablesExecutor.registerRecurringRunnable(this);
    }

    /**
     * Releases resources used by this instance and stops the periodic execution
     * of {@link #run()}.
     */
    @Override
    public void close()
    {
        recurringRunnablesExecutor.deRegisterRecurringRunnable(this);
    }

    /**
     * Notifies this instance that the estimation of the available bandwidth
     * has changed.
     *
     * @param bwe the new estimation of the available bandwidth in bits per second.
     */
    @Override
    public void bandwidthEstimationChanged(long bwe)
    {
        latestBwe = bwe;
    }

    /**
     * @return the current rate at which we are sending data to the remote
     * endpoint.
     */
    private long getSendingBitrate()
    {
        return channel.getStream().getMediaStreamStats().getSendingBitrate();
    }

    /**
     * Enables or disables the sending of the highest quality simulcast layer
     * (as defined by {@link #TARGET_ORDER_HD} to {@link #channel}.
     * @param enable whether to enable or disable it.
     */
    private void enableHQLayer(boolean enable)
    {
        SimulcastEngine simulcastEngine
                = channel.getTransformEngine().getSimulcastEngine();
        if (simulcastEngine != null)
        {
            SimulcastSenderManager ssm
                = simulcastEngine.getSimulcastSenderManager();
            if (ssm != null)
            {
                if (enable)
                {
                    ssm.setOverrideOrder(
                        SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE);

                    logger
                        .info(
                            "Enabling HQ layer for endpoint " + getEndpointID());
                }
                else
                {
                    ssm.setOverrideOrder(TARGET_ORDER_HD - 1);
                    logger
                        .info(
                            "Disabling HQ layer for endpoint " + getEndpointID());
                }
            }
        }
        else
        {
            logger.error("Failed to enable or disable theHQ layer, "
                         + "simulcastEngine is null. Maybe simulcast is not "
                         + "enabled for this channel?");
        }
    }

    /**
     * {@inheritDoc}
     * @return Zero.
     */
    @Override
    public void run()
    {
        long now = System.currentTimeMillis();
        lastUpdateTime = now;

        if (triggered)
        {
            // We may eventually want to return or even close() here. For the
            // time being, we go on in order to collect data.
            //return 0;
        }

        long bwe = latestBwe;
        long sbr = getSendingBitrate();
        if (bwe == -1 || sbr == -1)
        {
            return; //no data yet
        }

        history.add(bwe, sbr, now);

        // In the "initial period".
        boolean inInitial = (now - history.firstAdd < INITIAL_PERIOD_MS);

        // The bandwidth estimation is currently increasing.
        boolean climbing
            = history.isBweClimbing(CLIMB_INTERVAL_MS, CLIMB_MIN_RATIO);

        // We have been consistently (for over OVERSENDING_INTERVAL ms) sending
        // too much.
        boolean oversending = true;
        long lastTimeNotOversending
            = history.getLastTimeNotOversending(OVERSENDING_COEF);
        if (lastTimeNotOversending != -1
                && now - lastTimeNotOversending < OVERSENDING_INTERVAL_MS)
        {
            oversending = false;
        }

        // There is a sender with target order == hq && isStreaming().
        boolean hqLayerAvailable = isHqLayerAvailable();

        boolean trigger
            = oversending && !climbing && hqLayerAvailable && !inInitial;

        if (logger.isTraceEnabled())
        {
            // Temporary logs to facilitate analysis
            String c = climbing ? " 9000000" : " 9500000";
            String o = oversending ? " 10000000" : " 10500000";
            String h = hqLayerAvailable ? " 11000000" : " 11500000";
            String t = trigger ? " 1" : " -1";

            logger.trace("Endpoint " + getEndpointID() + " " + sbr + " "
                                 + bwe + c + o + h + t);
        }

        if (trigger && ENABLE_TRIGGER)
        {
            enableHQLayer(false);
        }
    }

    /**
     * Returns true if and only if our {@link VideoChannel} has a
     * {@link SimulcastSender} with "target order" at least
     * {@link #TARGET_ORDER_HD} (i.e. a "selected endpoint"), and such that its
     * corresponding {@link SimulcastReceiver} is currently receiving the
     * stream with the highest "target order".
     *
     * That is, checks if we are currently sending at least one high quality
     * layer to the remote endpoint.
     *
     * TODO: we may want to take lastN/forwardedEndpoints into account.
     */
    private boolean isHqLayerAvailable()
    {
        RtpChannelTransformEngine rtpChannelTransformEngine
            = channel.getTransformEngine();
        if (rtpChannelTransformEngine == null)
        {
            logger.warn("rtpChannelTransformEngine is null");
            return false;
        }

        SimulcastEngine simulcastEngine
            = rtpChannelTransformEngine.getSimulcastEngine();
        if (simulcastEngine == null)
        {
            logger.warn("simulcastEngine is null");
            return false;
        }

        SimulcastSenderManager ssm
            = simulcastEngine.getSimulcastSenderManager();
        if (ssm == null)
        {
            logger.warn("ssm is null");
            return false;
        }

        int highestStreamingTargetOrder = ssm.getHighestStreamingTargetOrder();
        return highestStreamingTargetOrder >= TARGET_ORDER_HD;
    }

    @Override
    public long getTimeUntilNextRun()
    {
        return
                (lastUpdateTime < 0L)
                        ? 0L
                        : lastUpdateTime + PROCESS_INTERVAL_MS
                        - System.currentTimeMillis();
    }

    /**
     * @return the ID of {@link #channel}'s endpoint.
     */
    private String getEndpointID()
    {
        Endpoint endpoint  = channel.getEndpoint();
        return endpoint == null ? "null" : endpoint.getID();
    }

    private static class History
    {
        int LENGTH_MS = Math.max(CLIMB_INTERVAL_MS, OVERSENDING_INTERVAL_MS);
        int LENGTH = 1 + LENGTH_MS / PROCESS_INTERVAL_MS;
        long[] bwe = new long[LENGTH];
        long[] sbr = new long[LENGTH];
        long[] ts = new long[LENGTH];
        int head = 0;
        int size = 0;
        long firstAdd = -1;

        private void add(long currentBwe, long currentSbr, long now)
        {
            if (firstAdd == -1)
                firstAdd = now;
            head = (head + 1) % LENGTH;
            size += 1;
            if (size > bwe.length)
                size -= 1;

            bwe[head] = currentBwe;
            sbr[head] = currentSbr;
            ts[head] = now;
        }

        private boolean isBweClimbing(int ms, double minRatio)
        {
            if (size <= 1)
                return true;

            long min = bwe[head];
            long now = System.currentTimeMillis();

            for (int i = 0; i < size - 1; i++)
            {
                int curIdx = (head - i + LENGTH) % LENGTH;
                int prevIdx = (curIdx - 1 + LENGTH) % LENGTH;

                if (ts[prevIdx] + ms < now)
                    break;

                if (bwe[prevIdx] < min)
                    min = bwe[prevIdx];

                // not monotonically increasing
                if (bwe[prevIdx] >= bwe[curIdx])
                    return false;
            }

            return ((double) bwe[head]) / min > minRatio;
        }

        //get the last time in ms that sbr < ratio * bwe
        //sbr
        private long getLastTimeNotOversending(double ratio)
        {

            for (int i = 0; i < size; i++)
            {
                int idx = (head - i + LENGTH) % LENGTH;
                if (sbr[idx] <= bwe[idx] * ratio)
                {
                    return ts[idx];
                }
            }

            return -1;
        }
    }
}
