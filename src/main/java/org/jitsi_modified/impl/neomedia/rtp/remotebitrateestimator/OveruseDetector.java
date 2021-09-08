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
package org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator;

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging.*;
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.config.OveruseDetectorConfig;

import java.util.*;

/**
 * webrtc/modules/remote_bitrate_estimator/overuse_detector.cc
 * webrtc/modules/remote_bitrate_estimator/overuse_detector.h
 *
 * @author Lyubomir Marinov
 */
@SuppressFBWarnings(
        value = {"FE_FLOATING_POINT_EQUALITY", "DB_DUPLICATE_BRANCHES"},
        justification = "We only compare timeOverUsing with the special value -1")
class OveruseDetector
{
    private final TimeSeriesLogger timeSeriesLogger;

    private static final double kMaxAdaptOffsetMs = 15.0;

    private BandwidthUsage hypothesis = BandwidthUsage.kBwNormal;

    private static final double kDown = 0D;

    private static final double kUp = 0D;

    private long lastUpdateMs = -1L;

    private int overuseCounter;

    private static final double overusingTimeThreshold = OveruseDetectorConfig.Companion.getOverusingTimeThreshold();

    private double prevOffset;

    private double threshold;

    private double timeOverUsing = -1D;

    private final DiagnosticContext diagnosticContext;

    public OveruseDetector(
            OverUseDetectorOptions options,
            @NotNull DiagnosticContext diagnosticContext)
    {
        Objects.requireNonNull(options, "options");

        threshold = options.initialThreshold;
        timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(getClass());

        this.diagnosticContext = diagnosticContext;
    }

    /**
     * Update the detection state based on the estimated inter-arrival time
     * delta offset. {@code timestampDelta} is the delta between the last
     * timestamp which the estimated offset is based on and the last timestamp
     * on which the last offset was based on, representing the time between
     * detector updates. {@code numOfDeltas} is the number of deltas the offset
     * estimate is based on. Returns the state after the detection update.
     *
     * @param offset
     * @param tsDelta
     * @param numOfDeltas
     * @param nowMs
     * @return
     */
    public BandwidthUsage detect(
            double offset,
            double tsDelta,
            int numOfDeltas,
            long nowMs)
    {
        if (numOfDeltas < 2)
            return BandwidthUsage.kBwNormal;

        double prev_offset = this.prevOffset;

        this.prevOffset = offset;

        double T = Math.min(numOfDeltas, 60) * offset;

        boolean newHypothesis = false;

        if (T > threshold)
        {
            if (timeOverUsing == -1)
            {
                // Initialize the timer. Assume that we've been
                // over-using half of the time since the previous
                // sample.
                timeOverUsing = tsDelta / 2;
            }
            else
            {
                // Increment timer
                timeOverUsing += tsDelta;
            }
            overuseCounter++;
            if (timeOverUsing > overusingTimeThreshold && overuseCounter > 1)
            {
                if (offset >= prev_offset)
                {
                    timeOverUsing = 0;
                    overuseCounter = 0;
                    hypothesis = BandwidthUsage.kBwOverusing;
                    newHypothesis = true;
                }
            }
        }
        else if (T < -threshold)
        {
            timeOverUsing = -1;
            overuseCounter = 0;
            hypothesis = BandwidthUsage.kBwUnderusing;
            newHypothesis = true;
        }
        else
        {
            timeOverUsing = -1;
            overuseCounter = 0;
            hypothesis = BandwidthUsage.kBwNormal;
            newHypothesis = true;
        }

        if (newHypothesis && timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("utilization_hypothesis", nowMs)
                .addField("detector", hashCode())
                .addField("offset", offset)
                .addField("prev_offset", prev_offset)
                .addField("T", T)
                .addField("threshold", threshold)
                .addField("hypothesis", hypothesis.getValue()));
        }

        updateThreshold(T, nowMs);

        return hypothesis;
    }

    /**
     * Returns the current detector state.
     *
     * @return
     */
    public BandwidthUsage getState()
    {
        return hypothesis;
    }

    private void updateThreshold(double modifiedOffset, long nowMs)
    {
        if (lastUpdateMs == -1)
            lastUpdateMs = nowMs;

        if (Math.abs(modifiedOffset) > threshold + kMaxAdaptOffsetMs)
        {
            // Avoid adapting the threshold to big latency spikes, caused e.g.,
            // by a sudden capacity drop.
            lastUpdateMs = nowMs;
            return;
        }

        double k = Math.abs(modifiedOffset) < threshold ? kDown : kUp;

        threshold
            += k
                * (Math.abs(modifiedOffset) - threshold)
                * (nowMs - lastUpdateMs);

        final double kMinThreshold = 6;
        final double kMaxThreshold = 600;

        threshold = Math.min(Math.max(threshold, kMinThreshold), kMaxThreshold);

        lastUpdateMs = nowMs;
    }
}
