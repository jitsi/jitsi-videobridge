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

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

/**
 * A rate control implementation based on additive increases of bitrate when no
 * over-use is detected and multiplicative decreases when over-uses are
 * detected. When we think the available bandwidth has changes or is unknown, we
 * will switch to a "slow-start mode" where we increase multiplicatively.
 *
 * webrtc/modules/remote_bitrate_estimator/aimd_rate_control.cc
 * webrtc/modules/remote_bitrate_estimator/aimd_rate_control.h
 *
 * @author Lyubomir Marinov
 */
class AimdRateControl
{
    /**
     * The <tt>TimeSeriesLogger</tt> used by the
     * <tt>RemoteBitrateEstimatorAbsSendTime</tt> class and its instances for
     * logging output.
     */
    private static final TimeSeriesLogger logger
        = TimeSeriesLogger.getTimeSeriesLogger(AimdRateControl.class);

    private static final int kDefaultRttMs = 200;

    private static final long kInitializationTimeMs = 5000;

    private static final long kFirstIncomingEstimateExpirationMs = 2 * kInitializationTimeMs;

    private static final long kLogIntervalMs = 1000;

    private static final long kMaxFeedbackIntervalMs = 1000;

    private static final long kMinFeedbackIntervalMs = 200;

    private static final int kRtcpSize = 80;

    private static final double kWithinIncomingBitrateHysteresis = 1.05;

    private final DiagnosticContext diagnosticContext;

    private float avgMaxBitrateKbps;

    private float beta;

    private boolean bitrateIsInitialized;

    /**
     * the number of time we've expired the initial incoming estimate.
     */
    private int incomingBitrateExpirations = 0;

    private long currentBitrateBps;

    private final RateControlInput currentInput
        = new RateControlInput(BandwidthUsage.kBwNormal, 0L, 0D);

    private boolean inExperiment;

    private long minConfiguredBitrateBps;

    private RateControlRegion rateControlRegion;

    private RateControlState rateControlState;

    private long rtt;

    private long timeFirstIncomingEstimate;

    private long timeLastBitrateChange;

    private long timeOfLastLog;

    private boolean updated;

    private float varMaxBitrateKbps;

    public AimdRateControl(@NotNull DiagnosticContext diagnosticContext)
    {
        reset();
        this.diagnosticContext = diagnosticContext;
    }

    private long additiveRateIncrease(
            long nowMs,
            long lastMs,
            long responseTimeMs)
    {
        if (responseTimeMs <= 0)
            throw new IllegalArgumentException("responseTimeMs");

        double beta = 0.0;

        if (lastMs > 0) {
            beta
                = Math.min((nowMs - lastMs) / (double) responseTimeMs, 1.0);
            if (inExperiment)
                beta /= 2.0;
        }

        double bitsPerFrame = (double) currentBitrateBps / 30.0;
        double packetsPerFrame = Math.ceil(bitsPerFrame / (8.0 * 1200.0));
        double avgPacketSizeBits = bitsPerFrame / packetsPerFrame;

        return (long) Math.max(1000.0, beta * avgPacketSizeBits);
    }

    private long changeBitrate(
            long currentBitrateBps,
            long incomingBitrateBps,
            long nowMs)
    {
        if (!updated)
            return this.currentBitrateBps;
        // An over-use should always trigger us to reduce the bitrate, even though
        // we have not yet established our first estimate. By acting on the over-use,
        // we will end up with a valid estimate.
        if (!bitrateIsInitialized
                && currentInput.bwState != BandwidthUsage.kBwOverusing)
        {
            return this.currentBitrateBps;
        }

        updated = false;
        changeState(currentInput, nowMs);

        // Calculated here because it's used in multiple places.
        float incomingBitrateKbps = incomingBitrateBps / 1000.0F;
        // Calculate the max bit rate std dev given the normalized variance and
        // the current incoming bit rate.
        float stdMaxBitRate
            = (float) Math.sqrt(varMaxBitrateKbps * avgMaxBitrateKbps);

        switch (rateControlState)
        {
        case kRcHold:
            break;

        case kRcIncrease:
        {
            if (avgMaxBitrateKbps >= 0F
                    && incomingBitrateKbps
                        > avgMaxBitrateKbps + 3F * stdMaxBitRate)
            {
                changeRegion(RateControlRegion.kRcMaxUnknown, nowMs);
                avgMaxBitrateKbps = -1F;
            }
            if (rateControlRegion == RateControlRegion.kRcNearMax)
            {
                // Approximate the over-use estimator delay to 100 ms.
                long responseTime = rtt + 100;
                long additiveIncreaseBps
                    = additiveRateIncrease(
                            nowMs,
                            timeLastBitrateChange,
                            responseTime);

                currentBitrateBps += additiveIncreaseBps;
            }
            else // kRcMaxUnknown || kRcAboveMax
            {
                long multiplicativeIncreaseBps
                    = multiplicativeRateIncrease(
                            nowMs,
                            timeLastBitrateChange,
                            currentBitrateBps);

                currentBitrateBps += multiplicativeIncreaseBps;
            }

            timeLastBitrateChange = nowMs;
            break;
        }
        case kRcDecrease:
        {
            bitrateIsInitialized = true;
            if (incomingBitrateBps < minConfiguredBitrateBps)
            {
                currentBitrateBps = minConfiguredBitrateBps;
            }
            else
            {
                // Set bit rate to something slightly lower than max to get rid
                // of any self-induced delay.
                currentBitrateBps = (long) (beta * incomingBitrateBps + 0.5);
                if (currentBitrateBps > this.currentBitrateBps)
                {
                    // Avoid increasing the rate when over-using.
                    if (rateControlRegion != RateControlRegion.kRcMaxUnknown)
                    {
                        currentBitrateBps
                            = (long) (beta * avgMaxBitrateKbps * 1000F + 0.5F);
                    }
                    currentBitrateBps
                        = Math.min(currentBitrateBps, this.currentBitrateBps);
                }
                changeRegion(RateControlRegion.kRcNearMax, nowMs);

                if (incomingBitrateKbps
                        < avgMaxBitrateKbps - 3F * stdMaxBitRate)
                {
                    avgMaxBitrateKbps = -1F;
                }

                updateMaxBitRateEstimate(incomingBitrateKbps);
            }
            // Stay on hold until the pipes are cleared.
            changeState(RateControlState.kRcHold, nowMs);
            timeLastBitrateChange = nowMs;
            break;
        }

        default:
            throw new IllegalStateException("rateControlState");
        }
        if ((incomingBitrateBps > 100000L || currentBitrateBps > 150000L)
                && currentBitrateBps > 1.5 * incomingBitrateBps)
        {
            // Allow changing the bit rate if we are operating at very low rates
            // Don't change the bit rate if the send side is too far off
            currentBitrateBps = this.currentBitrateBps;
            timeLastBitrateChange = nowMs;
        }
        return currentBitrateBps;
    }

    private void changeRegion(RateControlRegion region, long nowMs)
    {
        if (rateControlRegion == region)
        {
            return;
        }

        rateControlRegion = region;

        if (logger.isTraceEnabled())
        {
            logger.trace(diagnosticContext
                    .makeTimeSeriesPoint("aimd_region", nowMs)
                    .addField("aimd_id", hashCode())
                    .addField("region", region));
        }
    }

    private void changeState(RateControlInput input, long nowMs)
    {
        switch (currentInput.bwState)
        {
        case kBwNormal:
            if (rateControlState == RateControlState.kRcHold)
            {
                timeLastBitrateChange = nowMs;
                changeState(RateControlState.kRcIncrease, nowMs);
            }
            break;
        case kBwOverusing:
            if (rateControlState != RateControlState.kRcDecrease)
            {
                changeState(RateControlState.kRcDecrease, nowMs);
            }
            break;
        case kBwUnderusing:
            changeState(RateControlState.kRcHold, nowMs);
            break;
        default:
            throw new IllegalStateException("currentInput.bwState");
        }
    }

    private void changeState(RateControlState newState, long nowMs)
    {
        if (rateControlState == newState)
        {
            return;
        }

        rateControlState = newState;

        if (logger.isTraceEnabled())
        {
            logger.trace(diagnosticContext
                    .makeTimeSeriesPoint("aimd_state", nowMs)
                    .addField("aimd_id", hashCode())
                    .addField("state", rateControlState));
        }
    }

    public long getFeedBackInterval()
    {
        // Estimate how often we can send RTCP if we allocate up to 5% of
        // bandwidth to feedback.
        long interval
            = (long)
                (kRtcpSize * 8.0 * 1000.0 / (0.05 * currentBitrateBps) + 0.5);

        return
            Math.min(
                    Math.max(interval, kMinFeedbackIntervalMs),
                    kMaxFeedbackIntervalMs);
    }

    public long getLatestEstimate()
    {
        return currentBitrateBps;
    }

    /**
     * Returns <tt>true</tt> if the bitrate estimate hasn't been changed for
     * more than an RTT, or if the <tt>incomingBitrate</tt> is more than 5%
     * above the current estimate. Should be used to decide if we should reduce
     * the rate further when over-using.
     *
     * @param timeNow
     * @param incomingBitrateBps
     * @return
     */
    public boolean isTimeToReduceFurther(long timeNow, long incomingBitrateBps)
    {
        long bitrateReductionInterval = Math.max(Math.min(rtt, 200L), 10L);

        if (timeNow - timeLastBitrateChange >= bitrateReductionInterval)
            return true;
        if (isValidEstimate())
        {
          long threshold
              = (long) (kWithinIncomingBitrateHysteresis * incomingBitrateBps);
          long bitrateDifference = getLatestEstimate() - incomingBitrateBps;

          return bitrateDifference > threshold;
        }
        return false;
    }

    /**
     * @return the number of time we've expired the initial incoming estimate.
     */
    public int getIncomingEstimateExpirations()
    {
        return incomingBitrateExpirations;
    }

    /**
     * Returns <tt>true</tt> if there is a valid estimate of the incoming
     * bitrate, <tt>false</tt> otherwise.
     *
     * @return
     */
    public boolean isValidEstimate()
    {
        return bitrateIsInitialized;
    }

    private long multiplicativeRateIncrease(
            long nowMs,
            long lastMs,
            long currentBitrateBps)
    {
        double alpha = 1.08;

        if (lastMs > -1) {
            long timeSinceLastUpdateMs = Math.min(nowMs - lastMs, 1000);

            alpha = Math.pow(alpha,  timeSinceLastUpdateMs / 1000.0);
        }
        return (long) Math.max(currentBitrateBps * (alpha - 1.0), 1000.0);
    }

    public void reset()
    {
        reset(RemoteBitrateEstimatorAbsSendTime.kDefaultMinBitrateBps);
    }

    private void reset(long minBitrateBps)
    {
        minConfiguredBitrateBps = minBitrateBps;
        currentBitrateBps = /* maxConfiguredBitrateBps */ 30000000L;
        avgMaxBitrateKbps = -1F;
        varMaxBitrateKbps = 0.4F;
        rateControlState = RateControlState.kRcHold;
        rateControlRegion = RateControlRegion.kRcMaxUnknown;
        timeLastBitrateChange = -1L;
        currentInput.bwState = BandwidthUsage.kBwNormal;
        currentInput.incomingBitRate = 0L;
        currentInput.noiseVar = 1D;
        updated = false;
        timeFirstIncomingEstimate = -1L;
        bitrateIsInitialized = false;
        beta = 0.85F;
        rtt = kDefaultRttMs;
        timeOfLastLog = -1L;
        inExperiment = false;
    }

    public void setEstimate(long bitrateBps, long nowMs)
    {
        updated = true;
        bitrateIsInitialized = true;
        currentBitrateBps = changeBitrate(bitrateBps, bitrateBps, nowMs);
    }

    public void setMinBitrate(long minBitrateBps)
    {
        minConfiguredBitrateBps = minBitrateBps;
        currentBitrateBps = Math.max(minBitrateBps, currentBitrateBps);
    }

    public void setRtt(long nowMs, long rtt)
    {
        if (logger.isTraceEnabled())
        {
            logger.trace(diagnosticContext
                    .makeTimeSeriesPoint("aimd_rtt", nowMs)
                    .addField("aimd_id", hashCode())
                    .addField("rtt", rtt));
        }

        this.rtt = rtt;
    }

    public void update(RateControlInput input, long nowMs)
    {
        if (input == null)
            throw new NullPointerException("input");

        // Set the initial bit rate value to what we're receiving the first half
        // second.
        if (!bitrateIsInitialized)
        {
            if (timeFirstIncomingEstimate < 0L)
            {
                if (input.incomingBitRate > 0L)
                    timeFirstIncomingEstimate = nowMs;
            }
            else
            {
                long timeSinceFirstIncomingEstimate = nowMs - timeFirstIncomingEstimate;
                if (timeSinceFirstIncomingEstimate > kFirstIncomingEstimateExpirationMs)
                {
                    if (input.incomingBitRate > 0L)
                    {
                        timeFirstIncomingEstimate = nowMs;
                    }
                    else
                    {
                        timeFirstIncomingEstimate = -1L;
                    }

                    incomingBitrateExpirations++;
                }
                else if (timeSinceFirstIncomingEstimate > kInitializationTimeMs
                    && input.incomingBitRate > 0L)
                {
                    currentBitrateBps = input.incomingBitRate;
                    bitrateIsInitialized = true;
                }
            }
        }

        if (updated && currentInput.bwState == BandwidthUsage.kBwOverusing)
        {
            // Only update delay factor and incoming bit rate. We always want to
            // react on an over-use.
            currentInput.noiseVar = input.noiseVar;
            currentInput.incomingBitRate = input.incomingBitRate;
        }
        else
        {
            updated = true;
            currentInput.copy(input);
        }
    }

    public long updateBandwidthEstimate(long nowMs)
    {
        currentBitrateBps
            = changeBitrate(
                    currentBitrateBps,
                    currentInput.incomingBitRate,
                    nowMs);

        if (logger.isTraceEnabled() && isValidEstimate())
        {
            logger.trace(diagnosticContext
                .makeTimeSeriesPoint("aimd_estimate", nowMs)
                .addField("aimd_id", hashCode())
                .addField("estimate_bps", currentBitrateBps)
                .addField("incoming_bps", currentInput.incomingBitRate));
        }

        if (nowMs - timeOfLastLog > kLogIntervalMs)
            timeOfLastLog = nowMs;
        return currentBitrateBps;
    }

    private void updateMaxBitRateEstimate(float incomingBitrateKbps)
    {
        float alpha = 0.05F;

        if (avgMaxBitrateKbps == -1F)
        {
            avgMaxBitrateKbps = incomingBitrateKbps;
        }
        else
        {
            avgMaxBitrateKbps
                = (1 - alpha) * avgMaxBitrateKbps + alpha * incomingBitrateKbps;
        }

        // Estimate the max bit rate variance and normalize the variance with
        // the average max bit rate.
        float norm = Math.max(avgMaxBitrateKbps, 1F);

        varMaxBitrateKbps
            = (1 - alpha) * varMaxBitrateKbps
                + alpha
                    * (avgMaxBitrateKbps - incomingBitrateKbps)
                    * (avgMaxBitrateKbps - incomingBitrateKbps)
                    / norm;
        // 0.4 ~= 14 kbit/s at 500 kbit/s
        if (varMaxBitrateKbps < 0.4F)
            varMaxBitrateKbps = 0.4F;
        // 2.5f ~= 35 kbit/s at 500 kbit/s
        if (varMaxBitrateKbps > 2.5f)
            varMaxBitrateKbps = 2.5f;
    }
}
