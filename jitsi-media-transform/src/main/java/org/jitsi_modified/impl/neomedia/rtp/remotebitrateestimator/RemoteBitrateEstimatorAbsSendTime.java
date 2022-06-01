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
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.*;

import java.time.*;

/**
 * webrtc.org abs_send_time implementation as of June 26, 2017.
 * commit ID: 23fbd2aa2c81d065b84d17b09b747e75672e1159
 *
 * @author Julian Chukwu
 * @author George Politis
 */
public class RemoteBitrateEstimatorAbsSendTime
{
    /**
     * webrtc/modules/remote_bitrate_estimator/include/bwe_defines.h
     */
    static final private int kBitrateWindowMs = 1000;

    static final int kDefaultMinBitrateBps = 30000;

    static final private int kStreamTimeOutMs = 2000;

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(
                RemoteBitrateEstimatorAbsSendTime.class);

    /**
     * Defines the number of digits in the AST representation (24 bits, 6.18
     * fixed point) after the radix.
     */
    private final static int kAbsSendTimeFraction = 18;

    /**
     * Defines the upshift (left bit-shift) to apply to AST (24 bits, 6.18 fixed
     * point) to make it inter-arrival compatible (expanded AST, 32 bits, 6.26
     * fixed point).
     */
    private final static int kAbsSendTimeInterArrivalUpshift = 8;

    /**
     * This is used in the {@link InterArrival} computations. In this estimator
     * a timestamp group is defined as all packets with a timestamp which are at
     * most 5ms older than the first timestamp in that group.
     */
    private final static int kTimestampGroupLengthMs = 5;

    /**
     * Defines the number of digits in the expanded AST representation (32 bits,
     * 6.26 fixed point) after the radix.
     */
    private final static int kInterArrivalShift
        = kAbsSendTimeFraction + kAbsSendTimeInterArrivalUpshift;

    /**
     * Converts the {@link #kTimestampGroupLengthMs} into "ticks" for use with
     * the {@link InterArrival}.
     */
    private static final long kTimestampGroupLengthTicks
        = (kTimestampGroupLengthMs << kInterArrivalShift) / 1000;

    /**
     * Defines the expanded AST (32 bits) to millis conversion rate. Units are
     * ms per timestamp
     */
    private static final double
        kTimestampToMs = (double) 1000 / (1 << kInterArrivalShift);

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@code incomingPacket}.
     */
    private final long[] deltas = new long[3];

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@link #incomingPacketInfo(long, long, long, int)}} by promoting the
     * {@code RateControlInput} instance from a local variable to a field and
     * reusing the same instance across method invocations. (Consequently, the
     * default values used to initialize the field are of no importance because
     * they will be overwritten before they are actually used.)
     */
    private final RateControlInput input
        = new RateControlInput(BandwidthUsage.kBwNormal, 0L, 0D);

    private long lastPacketTimeMs;

    /**
     * The time (in millis) when we saw the first packet. Useful to determine
     * the probing period.
     */
    private long firstPacketTimeMs;

    /**
     * Keeps track of the last time (in millis) that we updated the bitrate
     * estimate.
     */
    private long lastUpdateMs;

    /**
     * The rate control implementation based on additive increases of bitrate
     * when no over-use is detected and multiplicative decreases when over-uses
     * are detected.
     */
    private final AimdRateControl remoteRate;

    /**
     * Holds the {@link InterArrival}, {@link OveruseEstimator} and
     * {@link OveruseDetector} instances of this RBE.
     */
    private Detector detector;

    /**
     * Keeps track of how much data we're receiving.
     */
    private BitrateTracker incomingBitrate;

    /**
     * Determines whether or not the incoming bitrate is initialized or not.
     */
    private boolean incomingBitrateInitialized;

    /**
     * The {@link DiagnosticContext} of this instance.
     */
    private final DiagnosticContext diagnosticContext;

     /**
      * The instance logger.
      */
    private final Logger logger;

    /**
     * The number of expirations of the initial estimate of the underlying AIMD.
     *
     * @return the number of expirations of the initial estimate of the
     * underlying AIMD.
     */
    public int getIncomingEstimateExpirations()
    {
        return remoteRate.getIncomingEstimateExpirations();
    }

    /**
     * Ctor.
     *
     * @param diagnosticContext the {@link DiagnosticContext} of this instance.
     */
    public RemoteBitrateEstimatorAbsSendTime(
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;
        this.remoteRate = new AimdRateControl(diagnosticContext);
        this.incomingBitrate = new BitrateTracker(Duration.ofMillis(kBitrateWindowMs));
        this.incomingBitrateInitialized = false;
        this.firstPacketTimeMs = -1;
        this.lastPacketTimeMs = -1;
        this.lastUpdateMs = -1;
    }

    /**
     * Reset back to the state immediately after construction.
     */
    public synchronized void reset()
    {
        remoteRate.reset();
        this.incomingBitrate = new BitrateTracker(Duration.ofMillis(kBitrateWindowMs));
        this.incomingBitrateInitialized = false;
        this.firstPacketTimeMs = -1;
        this.lastPacketTimeMs = -1;
        this.lastUpdateMs = -1;

        this.detector = null;
    }

    /**
     * Notifies this instance of an incoming packet.
     *
     * @param nowMs the current time when this method is called
     * @param arrivalTimeMs the arrival time of the packet in millis.
     * @param sendTimeMs the send time of the packet in millis
     * @param payloadSize the payload size of the packet.
     */
    public synchronized void incomingPacketInfo(
        long nowMs,
        long sendTimeMs,
        long arrivalTimeMs,
        int payloadSize)
    {

        long sendTime24bits = convertMsTo24Bits(sendTimeMs);
        // Shift up send time to use the full 32 bits that inter_arrival
        // works with, so wrapping works properly.
        long timestamp = sendTime24bits << kAbsSendTimeInterArrivalUpshift;

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("in_pkt", nowMs)
                .addField("rbe_id", hashCode())
                .addField("recv_ts_ms", arrivalTimeMs)
                .addField("timestamp", timestamp)
                .addField("pkt_sz_bytes", payloadSize));
        }

        // should be broken out from  here.
        // Check if incoming bitrate estimate is valid, and if it
        // needs to be reset.
        long incomingBitrate_ = incomingBitrate.getRateBps(arrivalTimeMs);
        if (incomingBitrate_ != 0)
        {
            incomingBitrateInitialized = true;
        }
        else if (incomingBitrateInitialized)
        {
            // Incoming bitrate had a previous valid value, but now not
            // enough data point are left within the current window.
            // Reset incoming bitrate estimator so that the window
            // size will only contain new data points.
            incomingBitrate = new BitrateTracker(Duration.ofMillis(kBitrateWindowMs));
            incomingBitrateInitialized = false;
        }

        incomingBitrate.update(DataSizeKt.getBytes(payloadSize), arrivalTimeMs);

        if (firstPacketTimeMs == -1)
        {
            firstPacketTimeMs = nowMs;
        }

        boolean updateEstimate = false;

        checkTimeouts(nowMs);
        lastPacketTimeMs = nowMs;

        long[] deltas = this.deltas;

        /* long timestampDelta */ deltas[0] = 0;
        /* long timeDelta */ deltas[1] = 0;
        /* int sizeDelta */ deltas[2] = 0;

        if (detector == null)
        {
            detector = new Detector(new OverUseDetectorOptions(), true, logger);
        }

        if (detector.interArrival.computeDeltas(
            timestamp, arrivalTimeMs, payloadSize, deltas, nowMs))
        {
            double tsDeltaMs = deltas[0] * kTimestampToMs;

            detector.estimator.update(
                /* timeDelta */ deltas[1],
                /* timestampDelta */ tsDeltaMs,
                /* sizeDelta */ (int) deltas[2],
                detector.detector.getState(), nowMs);

            detector.detector.detect(
                detector.estimator.getOffset(), tsDeltaMs,
                detector.estimator.getNumOfDeltas(), arrivalTimeMs);
        }

        // Check if it's time for a periodic update or if we
        // should update because of an over-use.
        if (lastUpdateMs == -1
            || nowMs - lastUpdateMs > remoteRate.getFeedBackInterval())
        {
            updateEstimate = true;
        }
        else if (
            detector.detector.getState() == BandwidthUsage.kBwOverusing)
        {
            long incomingRate_ = incomingBitrate.getRateBps(arrivalTimeMs);

            if (incomingRate_ > 0 && remoteRate
                .isTimeToReduceFurther(nowMs, incomingBitrate_))
            {
                updateEstimate = true;
            }
        }

        if (updateEstimate)
        {
            // The first overuse should immediately trigger a new estimate.
            // We also have to update the estimate immediately if we are
            // overusing and the target bitrate is too high compared to
            // what we are receiving.
            input.bwState = detector.detector.getState();
            input.incomingBitRate = incomingBitrate.getRateBps(arrivalTimeMs);
            input.noiseVar = detector.estimator.getVarNoise();

            remoteRate.update(input, nowMs);
            remoteRate.updateBandwidthEstimate(nowMs);
            updateEstimate = remoteRate.isValidEstimate();
        }

        if (updateEstimate)
        {
            lastUpdateMs = nowMs;
        }
    }

    /**
     * Timeouts SSRCs that have not received any data for
     * kTimestampGroupLengthMs millis.
     *
     * @param nowMs the current time in millis.
     */
    private synchronized void checkTimeouts(long nowMs)
    {
        if (nowMs - lastPacketTimeMs > kStreamTimeOutMs)
        {
            detector = null;
            // We deliberately don't reset the first_packet_time_ms_
            // here for now since we only probe for bandwidth in the
            // beginning of a call right now.
        }
    }

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    public synchronized void onRttUpdate(long nowMs, long avgRttMs)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("new_rtt", nowMs)
                .addField("avg_ms", avgRttMs));
        }

        remoteRate.setRtt(nowMs, avgRttMs);
    }

    public synchronized long getLatestEstimate()
    {
        long bitrateBps;
        if (!remoteRate.isValidEstimate())
        {
            return -1;
        }
        if (detector == null)
        {
            bitrateBps = 0;
        }
        else
        {
            bitrateBps = remoteRate.getLatestEstimate();
        }
        return bitrateBps;
    }

    /**
     * Sets the minimum bitrate for this instance.
     *
     * @param minBitrateBps the minimum bitrate in bps.
     */
    public synchronized void setMinBitrate(int minBitrateBps)
    {
        // Called from both the configuration thread and the network thread.
        // Shouldn't be called from the network thread in the future.
        remoteRate.setMinBitrate(minBitrateBps);
    }

    /**
     * Holds the {@link InterArrival}, {@link OveruseEstimator} and
     * {@link OveruseDetector} instances that estimate the remote bitrate of a
     * stream.
     */
    private class Detector
    {
        /**
         * Computes the send-time and recv-time deltas to feed to the estimator.
         */
        private final InterArrival interArrival;

        /**
         * The Kalman filter implementation that estimates the jitter.
         */
        private final OveruseEstimator estimator;

        /**
         * The overuse detector that compares the jitter to an adaptive threshold.
         */
        private final OveruseDetector detector;

        /**
         * Ctor.
         *
         * @param options the over-use detector options.
         * @param enableBurstGrouping true to activate burst detection, false
         * otherwise
         */
        Detector(OverUseDetectorOptions options,
                 boolean enableBurstGrouping,
                 Logger parentLogger)
        {
            this.interArrival = new InterArrival(
                kTimestampGroupLengthTicks, kTimestampToMs,
                enableBurstGrouping, diagnosticContext, parentLogger);
            this.estimator = new OveruseEstimator(options, diagnosticContext, logger);
            this.detector = new OveruseDetector(options, diagnosticContext);
        }
    }

    /**
     * Converts rtp timestamps to 24bit timestamp equivalence
     * @param timeMs is the RTP timestamp e.g System.currentTimeMillis().
     * @return time stamp representation in 24 bit representation.
     */
    public static long convertMsTo24Bits(long timeMs)
    {
        return (((timeMs << kAbsSendTimeFraction) + 500) / 1000) & 0x00FFFFFF;
    }
}
