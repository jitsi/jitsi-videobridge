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

import io.callstats.sdk.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;
import org.jitsi.service.configuration.*;
import org.jitsi.service.version.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

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
     * The {@code Logger} used by the {@code CallStatsIOTransport} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(CallStatsIOTransport.class);

    private static final String PNAME_CALLSTATS_IO_APP_ID
        = "io.callstats.sdk.CallStats.appId";

    private static final String PNAME_CALLSTATS_IO_APP_SECRET
        = "io.callstats.sdk.CallStats.appSecret";

    private static final String PNAME_CALLSTATS_IO_BRIDGE_ID
        = "io.callstats.sdk.CallStats.bridgeId";

    /**
     * The {@code BridgeStatusInfoBuilder} which initializes new
     * {@code BridgeStatusInfo} instances (to be sent by {@code CallStats}).
     * Since reentrancy and thread-safety related issues are taken care of by
     * the invoker of {@link #publishStatistics(Statistics)}, the instance is
     * cached for the sake of performance.
     */
    private BridgeStatusInfoBuilder bridgeStatusInfoBuilder;

    /**
     * The entry point into the callstats.io (Java) library.
     */
    private CallStats callStats;

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
     * Notifies this {@code CallStatsIOTransport} that a specific
     * {@code CallStats} failed to initialize.
     *
     * @param callStats the {@code CallStats} which failed to initialize
     * @param error the error
     * @param errMsg the error message
     */
    private void callStatsOnError(
            CallStats callStats,
            CallStatsErrors error,
            String errMsg)
    {
        logger.error(
                "callstats.io Java library failed to initialize with error: "
                    + error + " and error message: " + errMsg);
    }

    /**
     * Notifies this {@code CallStatsIOTransport} that a specific
     * {@code CallStats} initialized.
     *
     * @param callStats the {@code CallStats} which initialized
     * @param msg the message sent by {@code callStats} upon the successful
     * initialization
     */
    private void callStatsOnInitialized(CallStats callStats, String msg)
    {
        bridgeStatusInfoBuilder = new BridgeStatusInfoBuilder();

        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "callstats.io Java library initialized successfully"
                        + " with message: " + msg);
        }
    }

    /**
     * Initializes a new {@code ServerInfo} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the method is
     * invoked
     * @return a new {@code ServerInfo} instance
     */
    private ServerInfo createServerInfo(BundleContext bundleContext)
    {
        ServerInfo serverInfo = new ServerInfo();

        // os
        serverInfo.setOs(System.getProperty("os.name"));

        // name & ver
        VersionService versionService
            = ServiceUtils.getService(bundleContext, VersionService.class);

        if (versionService != null)
        {
            org.jitsi.service.version.Version version
                = versionService.getCurrentVersion();

            // name
            serverInfo.setName(version.getApplicationName());
            // ver
            serverInfo.setVer(version.toString());
        }

        return serverInfo;
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
        bridgeStatusInfoBuilder = null;
        callStats = null;
    }

    /**
     * Initializes this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may executed successfully.
     * Initializes {@link #callStats} i.e. the callstats.io (Java) library.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code StatsTransport} is to be initialized
     */
    private void init(BundleContext bundleContext)
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);

        init(bundleContext, cfg);
    }

    /**
     * Initializes this {@code StatsTransport} so that
     * {@link #publishStatistics(Statistics)} may executed successfully.
     * Initializes {@link #callStats} i.e. the callstats.io (Java) library.
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
        String bridgeId
            = ConfigUtils.getString(cfg, PNAME_CALLSTATS_IO_BRIDGE_ID, null);
        ServerInfo serverInfo = createServerInfo(bundleContext);

        final CallStats callStats = new CallStats();

        // The method CallStats.initialize() will (likely) return asynchronously
        // so it may be better to make the new CallStats instance available to
        // the rest of CallStatsIOTransport before the method in question
        // returns even if it may fail.
        this.callStats = callStats;

        callStats.initialize(
                appId,
                appSecret,
                bridgeId,
                serverInfo,
                new CallStatsInitListener()
                {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onError(CallStatsErrors error, String errMsg)
                    {
                        callStatsOnError(callStats, error, errMsg);
                    }

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onInitialized(String msg)
                    {
                        callStatsOnInitialized(callStats, msg);
                    }
                });
    }

    /**
     * Reads data from {@code statistics} and writes it into
     * {@code bridgeStatusInfoBuilder}.
     *
     * @param bsib the {@code BridgeStatusInfoBuilder} into which data read from
     * {@code statistics} is to be written
     * @param s the {@code Statistics} from which data is to be read and written
     * into {@code bridgeStatusInfoBuilder}
     */
    private void populateBridgeStatusInfoBuilderWithStatistics(
            BridgeStatusInfoBuilder bsib,
            Statistics s)
    {
        bsib.audioChannelsCount(
                s.getStatAsInt(VideobridgeStatistics.AUDIOCHANNELS));
        // TODO avgIntervalJitter
        // TODO avgIntervalRtt
        bsib.conferenceCount(s.getStatAsInt(VideobridgeStatistics.CONFERENCES));
        bsib.cpuUsage(s.getStatAsFloat(VideobridgeStatistics.CPU_USAGE));
        bsib.downloadBitRate(
                s.getStatAsInt(VideobridgeStatistics.BITRATE_DOWNLOAD));
        // TODO intervalLoss
        bsib.memoryUsage(s.getStatAsFloat(VideobridgeStatistics.USED_MEMORY));
        bsib.participantsCount(
                s.getStatAsInt(VideobridgeStatistics.NUMBEROFPARTICIPANTS));
        // TODO receivedBytes
        bsib.rtpLoss(s.getStatAsFloat(VideobridgeStatistics.RTP_LOSS));
        // TODO sentBytes
        bsib.threadCount(s.getStatAsInt(VideobridgeStatistics.NUMBEROFTHREADS));
        // TODO totalLoss
        bsib.totalMemory(s.getStatAsFloat(VideobridgeStatistics.TOTAL_MEMORY));
        bsib.uploadBitRate(
                s.getStatAsInt(VideobridgeStatistics.BITRATE_UPLOAD));
        bsib.videoChannelsCount(
                s.getStatAsInt(VideobridgeStatistics.VIDEOCHANNELS));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(Statistics statistics)
    {
        // Queuing is not implemented by CallStats at the time of this writing.
        if (callStats.isInitialized())
        {
            BridgeStatusInfoBuilder bridgeStatusInfoBuilder
                = this.bridgeStatusInfoBuilder;

            populateBridgeStatusInfoBuilderWithStatistics(
                    bridgeStatusInfoBuilder,
                    statistics);
            callStats.sendCallStatsBridgeEvent(bridgeStatusInfoBuilder.build());
        }
    }
}
