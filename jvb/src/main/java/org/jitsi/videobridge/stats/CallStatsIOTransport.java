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

import org.jitsi.config.*;
import org.jitsi.service.configuration.*;
import org.jitsi.stats.media.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.stats.config.*;

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
     * The {@link Logger} used by the {@link CallStatsIOTransport} class to print debug information.
     */
    private static final Logger logger = new LoggerImpl(CallStatsIOTransport.class.getName());

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
     * Handles conference stats.
     */
    private CallStatsConferenceStatsHandler conferenceStatsHandler;

    public CallStatsIOTransport(Version jvbVersion)
    {
        init(jvbVersion, JitsiConfig.getSipCommunicatorProps());
    }

    /**
     * Initializes this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may executed successfully.
     * Initializes {@link #statsService} i.e. the jitsi-stats library.
     *
     * @param cfg the {@code ConfigurationService} registered in
     * {@code bundleContext} if any
     */
    private void init(Version jvbVersion, ConfigurationService cfg)
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

        // as we create only one instance of StatsService
        StatsServiceFactory.getInstance().createStatsService(
            jvbVersion,
            appId, appSecret, keyId, keyPath, bridgeId, false,
            new StatsServiceFactory.InitCallback()
            {
                @Override
                public void error(String reason, String message)
                {
                    logger.error("Jitsi-stats library failed to initialize with reason: "
                        + reason + " and error message: " + message);
                }

                @Override
                public void onInitialized(StatsService statsService, String message)
                {
                    logger.info("Stats service initialized:" + message);
                    initConferenceStatsHandler(statsService);
                }
            });
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
        bsib.intervalDownloadBitRate((int) Math.round(s.getStatAsDouble(BITRATE_DOWNLOAD)));
        bsib.intervalRtpFractionLoss((float)s.getStatAsDouble(VideobridgeStatistics.OVERALL_LOSS));
        // TODO intervalSentBytes
        bsib.intervalUploadBitRate((int) Math.round(s.getStatAsDouble(BITRATE_UPLOAD)));
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
     * Initializes the callstats media stats.
     * @param statsService the created stats service instance.
     */
    private void initConferenceStatsHandler(StatsService statsService)
    {
        this.statsService = statsService;

        ConfigurationService cfg = JitsiConfig.getSipCommunicatorProps();
        String bridgeId = ConfigUtils.getString(
            cfg,
            CallStatsIOTransport.PNAME_CALLSTATS_IO_BRIDGE_ID,
            CallStatsIOTransport.DEFAULT_BRIDGE_ID);

        // Update with per stats transport interval if available.
        Duration intervalDuration = StatsManager.config.getTransportConfigs().stream()
            .filter(tc -> tc instanceof StatsTransportConfig.CallStatsIoStatsTransportConfig)
            .map(StatsTransportConfig::getInterval)
            .findFirst()
            .orElse(StatsManager.config.getInterval());
        int interval = (int)intervalDuration.toMillis();
        String conferenceIDPrefix = ConfigUtils.getString(
            cfg,
            CallStatsIOTransport.PNAME_CALLSTATS_IO_CONF_PREFIX,
            null);

        conferenceStatsHandler = new CallStatsConferenceStatsHandler();
        conferenceStatsHandler.start(
            this.statsService,
            bridgeId,
            conferenceIDPrefix,
            interval);
    }

    /**
     * Notification that a conference was created.
     * @param conference the conference that is created.
     */
    @Override
    public void conferenceCreated(Conference conference)
    {
        if (this.conferenceStatsHandler != null)
        {
            conferenceStatsHandler.conferenceCreated(conference);
        }
    }

    /**
     * Notification that a conference has expired.
     * @param conference the conference that expired.
     */
    @Override
    public void conferenceExpired(Conference conference)
    {
        if (this.conferenceStatsHandler != null)
        {
            conferenceStatsHandler.conferenceExpired(conference);
        }
    }
}
