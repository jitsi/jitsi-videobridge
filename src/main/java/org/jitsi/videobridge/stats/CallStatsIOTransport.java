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

import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.stats.media.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import java.time.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

/**
 * Implements {@code StatsTransport} for
 * <a href="http://www.callstats.io">callstats.io</a>.
 *
 * @author Lyubomir Marinov
 */
public class CallStatsIOTransport
    extends StatsTransport
{
    /**
     * The callstats AppID.
     */
    private static final String PNAME_CALLSTATS_IO_APP_ID
        = "io.callstats.sdk.CallStats.appId";

    /**
     * Shared Secret for authentication on Callstats.io
     */
    private static final String PNAME_CALLSTATS_IO_APP_SECRET
        = "io.callstats.sdk.CallStats.appSecret";

    /**
     * ID of the key that was used to generate token.
     */
    private static final String PNAME_CALLSTATS_IO_KEY_ID
        = "io.callstats.sdk.CallStats.keyId";

    /**
     * The path to private key file.
     */
    private static final String PNAME_CALLSTATS_IO_KEY_PATH
        = "io.callstats.sdk.CallStats.keyPath";

    /**
     * The bridge id to report to callstats.io.
     */
    public static final String PNAME_CALLSTATS_IO_BRIDGE_ID
        = "io.callstats.sdk.CallStats.bridgeId";

    /**
     * The default bridge id to use if setting is missing.
     */
    public static final String DEFAULT_BRIDGE_ID = "jitsi";

    /**
     * The bridge conference prefix to report to callstats.io.
     */
    public static final String PNAME_CALLSTATS_IO_CONF_PREFIX
        = "io.callstats.sdk.CallStats.conferenceIDPrefix";

    public CallStatsIOTransport() {}

    public CallStatsIOTransport(Duration interval)
    {
        super(interval);
    }

    /**
     * The {@code BridgeStatistics} which initializes new
     * {@code BridgeStatusInfo} instances (to be sent by {@code jitsi-stats}).
     * Since reentrancy and thread-safety related issues are taken care of by
     * the invoker of {@link #publishStatistics(Statistics)}, the instance is
     * cached for the sake of performance.
     */
    private BridgeStatistics bridgeStatusInfoBuilder = new BridgeStatistics();

    /**
     * The entry point into the jitsi-stats library.
     */
    private StatsService statsService;

    /**
     * Stats service listener.
     */
    private StatsServiceListener serviceListener;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void bundleContextChanged(
            BundleContext oldValue,
            BundleContext newValue)
    {
        super.bundleContextChanged(oldValue, newValue);

        if (newValue == null)
            dispose(oldValue);
        else if (oldValue == null)
            init(newValue);
    }

    /**
     * Disposes of this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may not execute successfully any
     * longer.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code StatsTransport} is to be disposed
     */
    private void dispose(BundleContext bundleContext)
    {
        if (serviceListener != null)
        {
            bundleContext.removeServiceListener(serviceListener);
            serviceListener = null;
        }

        if (statsService != null)
        {
            StatsServiceFactory.getInstance()
                .stopStatsService(bundleContext, statsService.getId());
            statsService = null;
        }

        bridgeStatusInfoBuilder = null;
    }

    /**
     * Initializes this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may executed successfully.
     * Initializes {@link #statsService} i.e. the jitsi-stats library.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code StatsTransport} is to be initialized
     */
    private void init(BundleContext bundleContext)
    {
        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

        init(bundleContext, cfg);
    }

    /**
     * Initializes this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may executed successfully.
     * Initializes {@link #statsService} i.e. the jitsi-stats library.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code StatsTransport} is to be initialized
     * @param cfg the {@code ConfigurationService} registered in
     * {@code bundleContext} if any
     */
    private void init(BundleContext bundleContext, ConfigurationService cfg)
    {
        int appId = ConfigUtils.getInt(cfg, PNAME_CALLSTATS_IO_APP_ID, 0);
        String appSecret
            = ConfigUtils.getString(cfg, PNAME_CALLSTATS_IO_APP_SECRET, null);
        String keyId
            = ConfigUtils.getString(cfg, PNAME_CALLSTATS_IO_KEY_ID, null);
        String keyPath
            = ConfigUtils.getString(cfg, PNAME_CALLSTATS_IO_KEY_PATH, null);
        String bridgeId = ConfigUtils.getString(
            cfg, PNAME_CALLSTATS_IO_BRIDGE_ID, DEFAULT_BRIDGE_ID);

        // as we create only one instance of StatsService we will expect
        // it to register in OSGi as service, if it doesn't it means it failed
        serviceListener = new StatsServiceListener(appId, bundleContext);
        bundleContext.addServiceListener(serviceListener);

        StatsServiceFactory.getInstance().createStatsService(
            bundleContext, appId, appSecret, keyId, keyPath, bridgeId, false);
    }

    /**
     * Reads data from {@code statistics} and writes it into
     * {@code bridgeStatusInfoBuilder}.
     *
     * @param bsib the {@code BridgeStatistics} into which data read from
     * {@code statistics} is to be written
     * @param s the {@code Statistics} from which data is to be read and written
     * into {@code bridgeStatusInfoBuilder}
     * @param measurementInterval the interval of time in milliseconds covered
     * by the measurements carried by the specified {@code statistics}
     */
    private void populateBridgeStatusInfoBuilderWithStatistics(
            BridgeStatistics bsib,
            Statistics s,
            long measurementInterval)
    {
        bsib.audioFabricCount(s.getStatAsInt(PARTICIPANTS));
        bsib.avgIntervalJitter(s.getStatAsInt(JITTER_AGGREGATE));
        bsib.avgIntervalRtt(s.getStatAsInt(RTT_AGGREGATE));
        bsib.conferenceCount(s.getStatAsInt(CONFERENCES));
        bsib.cpuUsage((float) s.getStatAsDouble(CPU_USAGE));
        bsib.intervalDownloadBitRate(
            (int) Math.round(s.getStatAsDouble(BITRATE_DOWNLOAD)));
        // TODO intervalReceivedBytes
        // uses download loss rate, as the upload is not properly measured
        // currently and vary a lot, which also breaks RTP_LOSS value.
        bsib.intervalRtpFractionLoss(
            (float)s.getStatAsDouble(LOSS_RATE_DOWNLOAD));
        // TODO intervalSentBytes
        bsib.intervalUploadBitRate(
            (int) Math.round(s.getStatAsDouble(BITRATE_UPLOAD)));
        bsib.measurementInterval((int) measurementInterval);
        bsib.memoryUsage(s.getStatAsInt(USED_MEMORY));
        bsib.participantsCount(s.getStatAsInt(PARTICIPANTS));
        bsib.threadCount(s.getStatAsInt(THREADS));
        // TODO totalLoss
        bsib.totalMemory(s.getStatAsInt(TOTAL_MEMORY));
        bsib.videoFabricCount(s.getStatAsInt(VIDEO_CHANNELS));
    }

    /**
     * {@inheritDoc}
     *
     * {@code CallStatsIOTransport} overrides
     * {@link #publishStatistics(Statistics, long)} so it does not have to do
     * anything in its implementation of {@link #publishStatistics(Statistics)}.
     */
    @Override
    public void publishStatistics(Statistics statistics)
    {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(
            Statistics statistics,
            long measurementInterval)
    {
        // Queuing is not implemented by jitsi-stats(CallStats)
        // at the time of this writing.
        if (statsService != null)
        {
            BridgeStatistics bridgeStatusInfoBuilder
                = this.bridgeStatusInfoBuilder;

            populateBridgeStatusInfoBuilderWithStatistics(
                    bridgeStatusInfoBuilder,
                    statistics,
                    measurementInterval);
            statsService.sendBridgeStatusUpdate(bridgeStatusInfoBuilder);
        }
    }

    /**
     * Listens for registering StatsService with specific id.
     */
    private class StatsServiceListener
        implements ServiceListener
    {
        /**
         * The id of the StatsService we expect.
         */
        private final int id;

        /**
         * The bundle context.
         */
        private final BundleContext bundleContext;

        /**
         * Constructs StatsServiceListener.
         * @param id the id of the StatsService to expect.
         * @param bundleContext the bundle context.
         */
        StatsServiceListener(int id, BundleContext bundleContext)
        {
            this.id = id;
            this.bundleContext = bundleContext;
        }

        @Override
        public void serviceChanged(ServiceEvent serviceEvent)
        {
            Object service;

            try
            {
                service = bundleContext.getService(
                    serviceEvent.getServiceReference());
            }
            catch (IllegalArgumentException
                | IllegalStateException
                | SecurityException ex)
            {
                service = null;
            }

            if (service == null || !(service instanceof StatsService))
                return;

            if (((StatsService) service).getId() != id)
                return;

            switch (serviceEvent.getType())
            {
            case ServiceEvent.REGISTERED:
                statsService = (StatsService) service;
                break;
            case ServiceEvent.UNREGISTERING:
                statsService = null;
                break;
            }
        }
    }
}
