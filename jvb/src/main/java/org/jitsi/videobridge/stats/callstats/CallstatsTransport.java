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
package org.jitsi.videobridge.stats.callstats;

import org.jetbrains.annotations.*;
import org.jitsi.stats.media.*;
import org.jitsi.videobridge.stats.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

/**
 * Implements {@link StatsTransport} for <a href="http://www.callstats.io">callstats.io</a>.
 *
 * @author Lyubomir Marinov
 */
class CallstatsTransport implements StatsTransport
{
    /**
     * The {@code BridgeStatistics} which initializes new {@code BridgeStatusInfo} instances.
     *
     * Since reentrancy and thread-safety related issues are taken care of by the invoker of
     * {@link #publishStatistics(Statistics, long)}, the instance is cached for the sake of performance.
     */
    private final BridgeStatistics bridgeStatusInfoBuilder = new BridgeStatistics();

    /**
     * The entry point into the jitsi-stats library.
     */
    @NotNull private final StatsService statsService;

    public CallstatsTransport(@NotNull StatsService statsService)
    {
        this.statsService = statsService;
    }

    /**
     * Reads data from a {@link Statistics} instance and populates a {@link BridgeStatistics} instance.
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
     */
    @Override
    public void publishStatistics(Statistics statistics, long measurementInterval)
    {
        BridgeStatistics bridgeStatusInfoBuilder = this.bridgeStatusInfoBuilder;

        populateBridgeStatusInfoBuilderWithStatistics(
                bridgeStatusInfoBuilder,
                statistics,
                measurementInterval);
        statsService.sendBridgeStatusUpdate(bridgeStatusInfoBuilder);
    }
}
