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
import org.jitsi_modified.service.neomedia.rtp.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.*;

import org.jitsi.utils.stats.*;

import java.util.*;

/**
 * webrtc.org abs_send_time implementation as of June 26, 2017.
 * commit ID: 23fbd2aa2c81d065b84d17b09b747e75672e1159
 *
 * @author Julian Chukwu
 * @author George Politis
 */
public class RemoteBitrateEstimatorAbsSendTime
    implements RemoteBitrateEstimator
{
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
     * {@link #incomingPacketInfo(long, long, int, long)}} by promoting the
     * {@code RateControlInput} instance from a local variable to a field and
     * reusing the same instance across method invocations. (Consequently, the
     * default values used to initialize the field are of no importance because
     * they will be overwritten before they are actually used.)
     */
    private final RateControlInput input
        = new RateControlInput(BandwidthUsage.kBwNormal, 0L, 0D);

    /**
     * The set of synchronization source identifiers (SSRCs) currently being
     * received. Represents an unmodifiable copy/snapshot of the current keys of
     * {@link #ssrcsMap} suitable for public access and introduced for
     * the purposes of reducing the number of allocations and the effects of
     * garbage collection.
     */
    private Collection<Long> ssrcs
        = Collections.unmodifiableList(Collections.EMPTY_LIST);

    /**
     * A map of SSRCs -> time first seen (in millis).
     */
    private final Map<Long, Long> ssrcsMap = new TreeMap<>();

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
     * The observer to notify on bitrate estimation changes.
     */
    private final RemoteBitrateObserver observer;

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
    private RateStatistics incomingBitrate;

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
     * Ctor.
     *
     * @param observer the observer to notify on bitrate estimation changes.
     * @param diagnosticContext the {@link DiagnosticContext} of this instance.
     */
    public RemoteBitrateEstimatorAbsSendTime(
            RemoteBitrateObserver observer,
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.observer = observer;
        this.diagnosticContext = diagnosticContext;
        this.remoteRate = new AimdRateControl(diagnosticContext);
        this.incomingBitrate = new RateStatistics(kBitrateWindowMs, kBitrateScale);
        this.incomingBitrateInitialized = false;
        this.firstPacketTimeMs = -1;
        this.lastUpdateMs = -1;
    }

    /**
     * Notifies this instance of an incoming packet.
     *
     * @param arrivalTimeMs the arrival time of the packet in millis.
     * @param sendTime24bits the send time of the packet in AST format
     * (24 bits, 6.18 fixed point).
     * @param payloadSize the payload size of the packet.
     * @param ssrc the SSRC of the packet.
     */
    @Override
    public void incomingPacketInfo(
        long arrivalTimeMs,
        long sendTime24bits,
        int payloadSize,
        long ssrc)
    {
        // Shift up send time to use the full 32 bits that inter_arrival
        // works with, so wrapping works properly.
        long timestamp = sendTime24bits << kAbsSendTimeInterArrivalUpshift;

        // Convert the expanded AST (32 bits, 6.26 fixed point) to millis.
        long sendTimeMs = (long) (timestamp * kTimestampToMs);

        // XXX The arrival time should be the earliest we've seen this packet,
        // not now. In our code however, we don't have access to the arrival
        // time.
        long nowMs = System.currentTimeMillis();

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("in_pkt", nowMs)
                .addField("rbe_id", hashCode())
                .addField("recv_ts_ms", arrivalTimeMs)
                .addField("send_ts_ms", sendTimeMs)
                .addField("pkt_sz_bytes", payloadSize)
                .addField("ssrc", ssrc));
        }

        // should be broken out from  here.
        // Check if incoming bitrate estimate is valid, and if it
        // needs to be reset.
        long incomingBitrate_ = incomingBitrate.getRate(arrivalTimeMs);
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
            incomingBitrate = new RateStatistics(kBitrateWindowMs, kBitrateScale);
            incomingBitrateInitialized = false;
        }

        incomingBitrate.update(payloadSize, arrivalTimeMs);

        if (firstPacketTimeMs == -1)
        {
            firstPacketTimeMs = nowMs;
        }

        boolean updateEstimate = false;
        long targetBitrateBps = 0;

        synchronized (this)
        {
            timeoutStreams(nowMs);
            ssrcsMap.put(ssrc, nowMs);
            if (!ssrcs.contains(ssrc))
            {
                ssrcs = Collections.unmodifiableCollection(ssrcsMap.keySet());
            }

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
                long incomingRate_ = incomingBitrate.getRate(arrivalTimeMs);

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
                input.incomingBitRate = incomingBitrate.getRate(arrivalTimeMs);
                input.noiseVar = detector.estimator.getVarNoise();

                remoteRate.update(input, nowMs);
                targetBitrateBps = remoteRate.updateBandwidthEstimate(nowMs);
                updateEstimate = remoteRate.isValidEstimate();
            }
        }

        if (updateEstimate)
        {
            lastUpdateMs = nowMs;
            if (observer != null)
            {
                observer.onReceiveBitrateChanged(getSsrcs(), targetBitrateBps);
            }
        }
    }

    /**
     * Timeouts SSRCs that have not received any data for
     * kTimestampGroupLengthMs millis.
     *
     * @param nowMs the current time in millis.
     */
    private synchronized void timeoutStreams(long nowMs)
    {
        boolean removed = false;
        Iterator<Map.Entry<Long, Long>> itr = ssrcsMap.entrySet().iterator();
        while (itr.hasNext())
        {
            Map.Entry<Long, Long> entry = itr.next();
            if ((nowMs - entry.getValue() > kStreamTimeOutMs))
            {
                removed = true;
                itr.remove();
            }
        }

        if (removed)
        {
            ssrcs = Collections.unmodifiableCollection(ssrcsMap.keySet());
        }

        if (detector != null && ssrcsMap.isEmpty())
        {
            // We can't update the estimate if we don't have any active streams.
            detector = null;
            // We deliberately don't reset the first_packet_time_ms_
            // here for now since we only probe for bandwidth in the
            // beginning of a call right now.
        }
    }

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    public synchronized void onRttUpdate(long avgRttMs, long maxRttMs)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("new_rtt", System.currentTimeMillis())
                .addField("avg_ms", avgRttMs)
                .addField("max_ms", maxRttMs));
        }

        remoteRate.setRtt(avgRttMs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized long getLatestEstimate()
    {
        long bitrateBps;
        if (!remoteRate.isValidEstimate())
        {
            return -1;
        }
        if (ssrcsMap.isEmpty())
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
     * {@inheritDoc}
     */
    @Override
    public Collection<Long> getSsrcs()
    {
        return ssrcs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeStream(long ssrc)
    {
        if (ssrcsMap.remove(ssrc) != null)
        {
            ssrcs = Collections.unmodifiableCollection(ssrcsMap.keySet());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
        private InterArrival interArrival;

        /**
         * The Kalman filter implementation that estimates the jitter.
         */
        private OveruseEstimator estimator;

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
            this.detector = new OveruseDetector(options, diagnosticContext, logger);
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
