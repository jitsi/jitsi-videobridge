/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation;

import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.utils.logging.*;
import org.jitsi_modified.service.neomedia.rtp.BandwidthEstimator;

import java.util.*;

/**
 * Implements the send-side bandwidth estimation described in
 * https://tools.ietf.org/html/draft-ietf-rmcat-gcc-01
 * Heavily based on code from webrtc.org (send_side_bandwidth_estimation.cc,
 * commit ID 7ad9e661f8a035d49d049ccdb87c77ae8ecdfa35).
 *
 * @author Boris Grozev
 */
class SendSideBandwidthEstimation
    implements BandwidthEstimator
{
    /**
     * The name of the property that specifies the low-loss threshold
     * (expressed as a proportion of lost packets).
     * See {@link #low_loss_threshold_}.
     */
    public final static String LOW_LOSS_THRESHOLD_PNAME
        = SendSideBandwidthEstimation.class.getName() + ".lowLossThreshold";

    /**
     * The name of the property that specifies the high-loss threshold
     * (expressed as a proportion of lost packets).
     * See {@link #high_loss_threshold_}.
     */
    public final static String HIGH_LOSS_THRESHOLD_PNAME
        = SendSideBandwidthEstimation.class.getName() + ".highLossThreshold";

    /**
     * The name of the property that specifies the bitrate threshold (in kbps).
     * See {@link #bitrate_threshold_bps_}.
     */
    public final static String BITRATE_THRESHOLD_KBPS_PNAME
        = SendSideBandwidthEstimation.class.getName() + ".bitrateThresholdKbps";

    /**
     * The name of the property that specifies the probability of enabling the
     * loss-based experiment.
     */
    public final static String LOSS_EXPERIMENT_PROBABILITY_PNAME
        = SendSideBandwidthEstimation.class.getName()
        + ".lossExperimentProbability";

    /**
     * The name of the property that specifies the probability of enabling the
     * timeout experiment.
     */
    public final static String TIMEOUT_EXPERIMENT_PROBABILITY_PNAME
        = SendSideBandwidthEstimation.class.getName()
        + ".timeoutExperimentProbability";

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kBweIncreaseIntervalMs = 1000;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final long kBweDecreaseIntervalMs = 300;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kDefaultMinBitrateBps = 10000;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kDefaultMaxBitrateBps = 1000000000;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kStartPhaseMs = 2000;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kLimitNumPackets = 20;

    /**
     * send_side_bandwidth_estimation.cc
     *
     * Expecting that RTCP feedback is sent uniformly within [0.5, 1.5]s
     * intervals.
     */
    private static final long kFeedbackIntervalMs = 1500;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final double kPacketReportTimeoutIntervals = 1.2;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final long kFeedbackTimeoutIntervals = 3;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final long kTimeoutIntervalMs = 1000;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final float kDefaultLowLossThreshold = 0.02f;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final float kDefaultHighLossThreshold = 0.1f;

    /**
     * send_side_bandwidth_estimation.cc
     */
    private static final int kDefaultBitrateThresholdKbps = 0;

    /**
     * Disable the loss experiment by default.
     */
    private static final float kDefaultLossExperimentProbability = 0;

    /**
     * Disable the timeout experiment by default.
     */
    private static final float kDefaultTimeoutExperimentProbability = 0;

    /**
     * The random number generator for all instances of this class.
     */
    private static final Random kRandom = new Random();

    /**
     * The <tt>Logger</tt> used by the {@link SendSideBandwidthEstimation} class
     * and its instances for logging output.
     */
    private static final Logger logger
            = Logger.getLogger(SendSideBandwidthEstimation.class);

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
            = TimeSeriesLogger.getTimeSeriesLogger(
                    SendSideBandwidthEstimation.class);

    /**
     * send_side_bandwidth_estimation.h
     */
    private final float low_loss_threshold_;

    /**
     * send_side_bandwidth_estimation.h
     */
    private final float high_loss_threshold_;

    /**
     * send_side_bandwidth_estimation.h
     */
    private final int bitrate_threshold_bps_;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long first_report_time_ms_ = -1;

    /**
     * send_side_bandwidth_estimation.h
     */
    private int lost_packets_since_last_loss_update_Q8_ = 0;

    /**
     * send_side_bandwidth_estimation.h
     */
    private int expected_packets_since_last_loss_update_ = 0;

    /**
     * send_side_bandwidth_estimation.h
     */
    private boolean has_decreased_since_last_fraction_loss_ = false;

    /**
     * send_side_bandwidth_estimation.h
     *
     * uint8_t last_fraction_loss_;
     */
    private int last_fraction_loss_ = 0;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long last_feedback_ms_ = -1;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long last_packet_report_ms_ = -1;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long last_timeout_ms_ = -1;

    /**
     * send_side_bandwidth_estimation.h
     */
    private final boolean in_timeout_experiment_;

    /**
     * send_side_bandwidth_estimation.h
     */
    private int min_bitrate_configured_ = kDefaultMinBitrateBps;

    /**
     * send_side_bandwidth_estimation.h
     */
    private int max_bitrate_configured_ = kDefaultMaxBitrateBps;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long time_last_decrease_ms_= 0;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long bwe_incoming_ = 0;

    /**
     * send_side_bandwidth_estimation.h
     */
    private long bitrate_;

    /**
     * send_side_bandwidth_estimation.h
     */
    private Deque<Pair<Long>> min_bitrate_history_ = new LinkedList<>();

    /**
     * The {@link DiagnosticContext} of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    private final List<Listener> listeners
        = new LinkedList<>();

    /**
     * The most recent RTT calculation we've received for our connection with the remote endpoint
     */
    private long rtt;

    /**
     * The instance that holds stats for this instance.
     */
    private final StatisticsImpl statistics = new StatisticsImpl();

    SendSideBandwidthEstimation(DiagnosticContext diagnosticContext, long startBitrate)
    {
        this.diagnosticContext = diagnosticContext;

        float lossExperimentProbability = (float) cfg.getDouble(
            LOSS_EXPERIMENT_PROBABILITY_PNAME,
            kDefaultLossExperimentProbability);

        if (kRandom.nextFloat() < lossExperimentProbability)
        {
            low_loss_threshold_ = (float) cfg.getDouble(
                LOW_LOSS_THRESHOLD_PNAME, kDefaultLowLossThreshold);
            high_loss_threshold_ = (float) cfg.getDouble(
                HIGH_LOSS_THRESHOLD_PNAME, kDefaultHighLossThreshold);
            bitrate_threshold_bps_ = 1000 * cfg.getInt(
                BITRATE_THRESHOLD_KBPS_PNAME, kDefaultBitrateThresholdKbps);
        }
        else
        {
            low_loss_threshold_ = kDefaultLowLossThreshold;
            high_loss_threshold_ = kDefaultHighLossThreshold;
            bitrate_threshold_bps_ = 1000 * kDefaultBitrateThresholdKbps;
        }


        float timeoutExperimentProbability = (float) cfg.getDouble(
            TIMEOUT_EXPERIMENT_PROBABILITY_PNAME,
            kDefaultTimeoutExperimentProbability);

        in_timeout_experiment_
            = kRandom.nextFloat() < timeoutExperimentProbability;

        setBitrate(startBitrate);
    }

    /**
     * bool SendSideBandwidthEstimation::IsInStartPhase(int64_t now_ms)
     */
    private synchronized boolean isInStartPhase(long now)
    {
        return first_report_time_ms_ == -1 ||
                now - first_report_time_ms_ < kStartPhaseMs;
    }

    /**
     * int SendSideBandwidthEstimation::CapBitrateToThresholds
     */
    private synchronized long capBitrateToThresholds(long bitrate)
    {
        if (bwe_incoming_ > 0 && bitrate > bwe_incoming_)
        {
            bitrate = bwe_incoming_;
        }
        if (bitrate > max_bitrate_configured_)
        {
            bitrate = max_bitrate_configured_;
        }
        if (bitrate < min_bitrate_configured_)
        {
            bitrate = min_bitrate_configured_;
        }
        return bitrate;
    }

    /**
     * void SendSideBandwidthEstimation::UpdateEstimate(int64_t now_ms)
     */
    protected synchronized void updateEstimate(long now)
    {
        long bitrate = bitrate_;

        // We trust the REMB during the first 2 seconds if we haven't had any
        // packet loss reported, to allow startup bitrate probing.
        if (last_fraction_loss_ == 0 && isInStartPhase(now) &&
                bwe_incoming_ > bitrate)
        {
            setBitrate(capBitrateToThresholds(bwe_incoming_));
            min_bitrate_history_.clear();
            min_bitrate_history_.addLast(new Pair<>(now, bitrate_));
            return;
        }
        updateMinHistory(now);

        if (last_packet_report_ms_ == -1)
        {
            // No feedback received.
            bitrate_ = capBitrateToThresholds(bitrate_);
            return;
        }

        long time_since_packet_report_ms = now - last_packet_report_ms_;
        long time_since_feedback_ms = now - last_feedback_ms_;

        if (time_since_packet_report_ms
            < kPacketReportTimeoutIntervals * kFeedbackIntervalMs)
        {
            // We only care about loss above a given bitrate threshold.
            float loss = last_fraction_loss_ / 256.0f;
            // We only make decisions based on loss when the bitrate is above a
            // threshold. This is a crude way of handling loss which is
            // uncorrelated to congestion.
            if (bitrate_ < bitrate_threshold_bps_ || loss <= low_loss_threshold_)
            {
                // Loss < 2%: Increase rate by 8% of the min bitrate in the last
                // kBweIncreaseIntervalMs.
                // Note that by remembering the bitrate over the last second one can
                // rampup up one second faster than if only allowed to start ramping
                // at 8% per second rate now. E.g.:
                //   If sending a constant 100kbps it can rampup immediatly to 108kbps
                //   whenever a receiver report is received with lower packet loss.
                //   If instead one would do: bitrate_ *= 1.08^(delta time), it would
                //   take over one second since the lower packet loss to achieve 108kbps.
                bitrate = (long) (min_bitrate_history_.getFirst().second * 1.08 + 0.5);

                // Add 1 kbps extra, just to make sure that we do not get stuck
                // (gives a little extra increase at low rates, negligible at higher
                // rates).
                bitrate += 1000;

                statistics.update(now, false, LossRegion.LossFree);

            }
            else if (bitrate_ > bitrate_threshold_bps_)
            {
                if (loss <= high_loss_threshold_)
                {
                    // Loss between 2% - 10%: Do nothing.

                    statistics.update(now, false, LossRegion.LossLimited);
                }
                else
                {
                    // Loss > 10%: Limit the rate decreases to once a kBweDecreaseIntervalMs +
                    // rtt.
                    if (!has_decreased_since_last_fraction_loss_ &&
                        (now - time_last_decrease_ms_) >=
                            (kBweDecreaseIntervalMs + getRtt()))
                    {
                        time_last_decrease_ms_ = now;

                        // Reduce rate:
                        //   newRate = rate * (1 - 0.5*lossRate);
                        //   where packetLoss = 256*lossRate;
                        bitrate = (long) (
                            (bitrate * (512 - last_fraction_loss_)) / 512.0);
                        has_decreased_since_last_fraction_loss_ = true;

                        statistics.update(now, false, LossRegion.LossDegraded);
                    }
                }
            }
        }
        else
        {
            statistics.update(now, true, null);

            if (time_since_feedback_ms >
                kFeedbackTimeoutIntervals * kFeedbackIntervalMs
                && (last_timeout_ms_ == -1
                || now - last_timeout_ms_ > kTimeoutIntervalMs))
            {
                if (in_timeout_experiment_)
                {
                    bitrate_ *= 0.8;
                    // Reset accumulators since we've already acted on missing
                    // feedback and shouldn't to act again on these old lost
                    // packets.
                    lost_packets_since_last_loss_update_Q8_ = 0;
                    expected_packets_since_last_loss_update_ = 0;
                    last_timeout_ms_ = now;
                }
            }
        }

        setBitrate(capBitrateToThresholds(bitrate));
    }

    /**
     * void SendSideBandwidthEstimation::UpdateReceiverBlock
     */
    synchronized void updateReceiverBlock(
            long fraction_lost, long number_of_packets, long now)
    {
        last_feedback_ms_ = now;
        if (first_report_time_ms_ == -1)
        {
            first_report_time_ms_ = now;
        }

        // Check sequence number diff and weight loss report
        if (number_of_packets > 0)
        {
            // Calculate number of lost packets.
            long num_lost_packets_Q8 = fraction_lost * number_of_packets;
            // Accumulate reports.
            lost_packets_since_last_loss_update_Q8_ += num_lost_packets_Q8;
            expected_packets_since_last_loss_update_ += number_of_packets;

            // Don't generate a loss rate until it can be based on enough packets.
            if (expected_packets_since_last_loss_update_ < kLimitNumPackets)
                return;

            has_decreased_since_last_fraction_loss_ = false;
            last_fraction_loss_ =
                    lost_packets_since_last_loss_update_Q8_ /
                    expected_packets_since_last_loss_update_;

            // Reset accumulators.
            lost_packets_since_last_loss_update_Q8_ = 0;
            expected_packets_since_last_loss_update_ = 0;

            last_packet_report_ms_ = now;
            updateEstimate(now);
        }
    }

    /**
     * void SendSideBandwidthEstimation::UpdateMinHistory(int64_t now_ms)
     */
    private synchronized void updateMinHistory(long now_ms)
    {
        // Remove old data points from history.
        // Since history precision is in ms, add one so it is able to increase
        // bitrate if it is off by as little as 0.5ms.
        while (!min_bitrate_history_.isEmpty() &&
                now_ms - min_bitrate_history_.getFirst().first + 1 >
                        kBweIncreaseIntervalMs)
        {
            min_bitrate_history_.removeFirst();
        }

        // Typical minimum sliding-window algorithm: Pop values higher than current
        // bitrate before pushing it.
        while (!min_bitrate_history_.isEmpty() &&
                bitrate_ <= min_bitrate_history_.getLast().second)
        {
            min_bitrate_history_.removeLast();
        }

        min_bitrate_history_.addLast(new Pair<>(now_ms, bitrate_));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void updateReceiverEstimate(long bandwidth)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("bwe_incoming")
                .addField("bitrate_bps", bandwidth));
        }
        bwe_incoming_ = bandwidth;
        setBitrate(capBitrateToThresholds(bitrate_));
    }

    /**
     * void SendSideBandwidthEstimation::SetMinMaxBitrate
     */
    synchronized void setMinMaxBitrate(int min_bitrate, int max_bitrate)
    {
        min_bitrate_configured_ = Math.max(min_bitrate, kDefaultMinBitrateBps);
        if (max_bitrate > 0)
        {
            max_bitrate_configured_ =
                    Math.max(min_bitrate_configured_, max_bitrate);
        }
        else
        {
            max_bitrate_configured_ = kDefaultMaxBitrateBps;
        }
    }

    /**
     * Sets the value of {@link #bitrate_}.
     * @param newValue the value to set
     */
    private synchronized void setBitrate(long newValue)
    {
        long oldValue = bitrate_;
        bitrate_ = newValue;
        if (oldValue != bitrate_)
        {
            fireBandwidthEstimationChanged(oldValue, newValue);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLatestEstimate()
    {
        return bitrate_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLatestREMB()
    {
        return bwe_incoming_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLatestFractionLoss()
    {
        return last_fraction_loss_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public StatisticsImpl getStatistics()
    {
        return statistics;
    }

    @Override
    public void onRttUpdate(double newRtt)
    {
        //TODO(brian): does it make sense to use rtt as a long in here? which is more approriate, double or long?
        // we should make all types for rtt consistent
        this.rtt = (long)newRtt;
    }

    /**
     * Returns the last calculated RTT to the endpoint.
     * @return the last calculated RTT to the endpoint.
     */
    private synchronized long getRtt()
    {
        if (rtt < 0 || rtt > 1000)
        {
            logger.warn("RTT not calculated, or has a suspiciously high value ("
                + rtt + "). Using the default of 100ms.");
            rtt = 100;
        }

        return rtt;
    }

    /**
     * Notifies registered listeners that the estimation of the available
     * bandwidth has changed.
     * @param oldValue the old value (in bps).
     * @param newValue the new value (in bps).
     */
    private synchronized void fireBandwidthEstimationChanged(
            long oldValue, long newValue)
    {
        for (BandwidthEstimator.Listener listener : listeners)
        {
            listener.bandwidthEstimationChanged(newValue);
        }
    }

    private class Pair<T>
    {
        T first;
        T second;
        Pair(T a, T b)
        {
            first = a;
            second = b;
        }
    }

    /**
     * This class records statistics information about how much time we spend
     * in different loss-states (loss-free, loss-limited and loss-degraded).
     */
    public class StatisticsImpl implements Statistics
    {
        /**
         * The current state {@link LossRegion}.
         */
        private LossRegion currentState = null;

        /**
         * Keeps the time (in millis) of the last transition (including a loop).
         */
        private long lastTransitionTimestampMs = -1;

        /**
         * The cumulative duration (in millis) of the current state
         * {@link #currentState} after having looped
         * {@link #currentStateConsecutiveVisits} times.
         */
        private long currentStateCumulativeDurationMs;

        /**
         * The number of loops over the current state {@link #currentState}.
         */
        private int currentStateConsecutiveVisits;

        /**
         * The bitrate when we entered the current state {@link #currentState}.
         */
        private long currentStateStartBitrateBps;

        /**
         * Computes the min/max/avg/sd of the bitrate while in
         * {@link #currentState}.
         */
        private LongSummaryStatistics currentStateBitrateStatistics
            = new LongSummaryStatistics();

        /**
         * Computes the min/max/avg/sd of the loss while in
         * {@link #currentState}.
         */
        private IntSummaryStatistics currentStateLossStatistics
            = new IntSummaryStatistics();

        /**
         * True when the fields of this class have changed from their default
         * value. The purpose is to avoid creating new IntSummaryStatistics and
         * LongSummaryStatistics when it's not needed.
         */
        private boolean isDirty = false;

        /**
         * Computes the sum of the duration of the different states.
         */
        private final LongSummaryStatistics
            lossFreeMsStats = new LongSummaryStatistics(),
            lossDegradedMsStats = new LongSummaryStatistics(),
            lossLimitedMsStats = new LongSummaryStatistics();

        @Override
        public void update(long nowMs)
        {
            synchronized (SendSideBandwidthEstimation.this)
            {
                long time_since_packet_report_ms
                    = nowMs - last_packet_report_ms_;

                boolean currentStateHasTimedOut = time_since_packet_report_ms
                    < kPacketReportTimeoutIntervals * kFeedbackIntervalMs;

                update(nowMs, currentStateHasTimedOut, null);
            }
        }

        /**
         * Records a state transition and updates the statistics information.
         *
         * @param nowMs the time (in millis) of the transition.
         * @param currentStateHasTimedOut true if the current state has timed
         * out, i.e. we haven't received receiver reports "in a while".
         * @param nextState the that the bwe is transitioning to.
         */
        void update(
            long nowMs, boolean currentStateHasTimedOut, LossRegion nextState)
        {
            synchronized (SendSideBandwidthEstimation.this)
            {
                if (lastTransitionTimestampMs > -1 && !currentStateHasTimedOut)
                {
                    isDirty = true;
                    currentStateCumulativeDurationMs
                        += nowMs - lastTransitionTimestampMs;
                }

                lastTransitionTimestampMs = nowMs;
                if (!currentStateHasTimedOut)
                {
                    isDirty = true;
                    // If the current state has not timed out, then update the
                    // stats that we gather.
                    currentStateLossStatistics.accept(last_fraction_loss_);
                    currentStateConsecutiveVisits++; // we start counting from 0.
                    if (this.currentState == nextState)
                    {
                        currentStateBitrateStatistics.accept(bitrate_);
                        return;
                    }
                }

                if (this.currentState != null)
                {
                    // This is not a loop, we're transitioning to another state.
                    // Record how much time we've spent on this state, how many
                    // times we've looped through it and what was the impact on
                    // the bitrate.
                    switch (this.currentState)
                    {
                    case LossDegraded:
                        lossDegradedMsStats.accept(
                            currentStateCumulativeDurationMs);
                        break;
                    case LossFree:
                        lossFreeMsStats.accept(currentStateCumulativeDurationMs);
                        break;
                    case LossLimited:
                        lossLimitedMsStats.accept(
                            currentStateCumulativeDurationMs);
                        break;
                    }

                    if (timeSeriesLogger.isTraceEnabled())
                    {
                        timeSeriesLogger.trace(diagnosticContext
                            .makeTimeSeriesPoint("loss_estimate")
                            .addField("state", currentState.name())
                            .addField("max_loss",
                                currentStateLossStatistics.getMax() / 256.0f)
                            .addField("min_loss",
                                currentStateLossStatistics.getMin() / 256.0f)
                            .addField("avg_loss",
                                currentStateLossStatistics.getAverage()/256.0f)
                            .addField("max_bps",
                                currentStateBitrateStatistics.getMax())
                            .addField("min_bps",
                                currentStateBitrateStatistics.getMin())
                            .addField("avg_bps",
                                currentStateBitrateStatistics.getAverage())
                            .addField("duration_ms",
                                currentStateCumulativeDurationMs)
                            .addField("consecutive_visits",
                                currentStateConsecutiveVisits)
                            .addField("bitrate_threshold",
                                bitrate_threshold_bps_)
                            .addField("low_loss_threshold",
                                low_loss_threshold_)
                            .addField("high_loss_threshold",
                                high_loss_threshold_)
                            .addField("delta_bps",
                                bitrate_ - currentStateStartBitrateBps));
                    }
                }

                currentState = nextState;
                currentStateStartBitrateBps = bitrate_;

                if (isDirty)
                {
                    currentStateLossStatistics = new IntSummaryStatistics();
                    currentStateBitrateStatistics = new LongSummaryStatistics();
                    currentStateConsecutiveVisits = 0;
                    currentStateCumulativeDurationMs = 0;
                    isDirty = false;
                }

                currentStateBitrateStatistics.accept(bitrate_);
            }
        }

        @Override
        public long getLossLimitedMs()
        {
            synchronized (SendSideBandwidthEstimation.this)
            {
                return lossLimitedMsStats.getSum();
            }
        }

        @Override
        public long getLossDegradedMs()
        {
            synchronized (SendSideBandwidthEstimation.this)
            {
                return lossDegradedMsStats.getSum();
            }
        }

        @Override
        public long getLossFreeMs()
        {
            synchronized (SendSideBandwidthEstimation.this)
            {
                return lossFreeMsStats.getSum();
            }
        }
    }

    /**
     * Represents the loss-based controller states.
     */
    private enum LossRegion
    {
        /**
         * Loss is between 2% and 10%.
         */
        LossLimited,

        /**
         * Loss is above 10%.
         */
        LossDegraded,

        /**
         * Loss is bellow 2%.
         */
        LossFree
    }
}
