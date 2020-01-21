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
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;

import java.util.*;

/**
 * webrtc/modules/remote_bitrate_estimator/overuse_estimator.cc
 * webrtc/modules/remote_bitrate_estimator/overuse_estimator.h
 *
 * @author Lyubomir Marinov
 */
class OveruseEstimator
{
    /**
     * The <tt>Logger</tt> used by
     * <tt>OveruseEstimator</tt> instances for
     * logging output.
     */
    private final Logger logger;

    /**
     * The <tt>TimeSeriesLogger</tt> used for time series.
     */
    private final TimeSeriesLogger timeSeriesLogger;

    private static final int kDeltaCounterMax = 1000;

    private static final int kMinFramePeriodHistoryLength = 60;

    /**
     * Creates and returns a deep copy of a <tt>double</tt> two-dimensional
     * matrix.
     *
     * @param matrix the <tt>double</tt> two-dimensional matrix to create and
     * return a deep copy of
     * @return a deep copy of <tt>matrix</tt>
     */
    private static double[][] clone(double[][] matrix)
    {
        int length = matrix.length;
        double[][] clone;

        clone = new double[length][];
        for (int i = 0; i < length; i++)
            clone[i] = matrix[i].clone();
        return clone;
    }

    private double avgNoise;

    private final double[][] E;

    private DiagnosticContext diagnosticContext;

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@code update}.
     */
    private final double[] Eh = new double[2];

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@code update}.
     */
    private final double[] h = new double[2];

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@code update}.
     */
    private final double[][] IKh = new double[][] { { 0, 0 }, { 0, 0 } };

    /**
     * Reduces the effects of allocations and garbage collection of the method
     * {@code update}.
     */
    private final double[] K = new double[2];

    private int numOfDeltas;

    private double offset;

    private double prevOffset;

    private final double[] processNoise;

    private double slope;

    /**
     * Store the tsDelta history into a {@code double[]} used as a circular buffer
     * Original c++ code uses std::list<double> but in Java this translate
     * to {@code List<Double>} which causes a lot of autoboxing
     */
    private final double[] tsDeltaHist = new double[kMinFramePeriodHistoryLength];

    /**
     * Index to insert next value into {@link #tsDeltaHist}
     */
    private int tsDeltaHistInsIdx;

    private double varNoise;

    public OveruseEstimator(
            OverUseDetectorOptions options,
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull Logger parentLogger)
    {
        logger = parentLogger.createChildLogger(getClass().getName());
        timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(getClass());
        this.diagnosticContext = diagnosticContext;
        slope = options.initialSlope;
        offset = options.initialOffset;
        prevOffset = offset;
        avgNoise = options.initialAvgNoise;
        varNoise = options.initialVarNoise;
        E = clone(options.initialE);
        processNoise = options.initialProcessNoise.clone();
        /**
         * Initialize {@link tsDeltaHist} with {@code Double.MAX_VALUE}
         * to simplify {@link updateMinFramePeriod}
         */
        Arrays.fill(tsDeltaHist, Double.MAX_VALUE);
    }

    /**
     * Returns the number of deltas which the current over-use estimator state
     * is based on.
     *
     * @return
     */
    public int getNumOfDeltas()
    {
        return numOfDeltas;
    }

    /**
     * Returns the estimated inter-arrival time delta offset in ms.
     *
     * @return
     */
    public double getOffset()
    {
        return offset;
    }

    /**
     * Returns the estimated noise/jitter variance in ms^2.
     *
     * @return
     */
    public double getVarNoise()
    {
        return varNoise;
    }

    /**
     * Update the estimator with a new sample. The deltas should represent
     * deltas between timestamp groups as defined by the InterArrival class.
     * {@code currentHypothesis} should be the hypothesis of the over-use
     * detector at this time.
     *
     * @param tDelta
     * @param tsDelta
     * @param sizeDelta
     * @param currentHypothesis
     */
    public void update(
            long tDelta,
            double tsDelta,
            int sizeDelta,
            BandwidthUsage currentHypothesis, long systemTimeMs)
    {
        double minFramePeriod = updateMinFramePeriod(tsDelta);
        double tTsDelta = tDelta - tsDelta;

        ++numOfDeltas;
        if (numOfDeltas > kDeltaCounterMax)
            numOfDeltas = kDeltaCounterMax;

        // Update the Kalman filter
        E[0][0] += processNoise[0];
        E[1][1] += processNoise[1];

        if ((currentHypothesis == BandwidthUsage.kBwOverusing
                    && offset < prevOffset)
                || (currentHypothesis == BandwidthUsage.kBwUnderusing
                    && offset > prevOffset))
        {
            E[1][1] += 10D * processNoise[1];
        }

        double[] h = this.h;
        double[] Eh = this.Eh;

        h[0] = sizeDelta;
        h[1] = 1D;
        Eh[0] = E[0][0] * h[0] + E[0][1] * h[1];
        Eh[1] = E[1][0] * h[0] + E[1][1] * h[1];

        double residual = tTsDelta - slope * h[0] - offset;
        boolean inStableState = (currentHypothesis == BandwidthUsage.kBwNormal);

        // We try to filter out very late frames. For instance periodic key
        // frames doesn't fit the Gaussian model well.
        double maxResidual = 3D * Math.sqrt(varNoise);
        double residualForUpdateNoiseEstimate
            = (Math.abs(residual) < maxResidual)
                ? residual
                : (residual < 0 ? -maxResidual : maxResidual);

        updateNoiseEstimate(
                residualForUpdateNoiseEstimate,
                minFramePeriod,
                inStableState);

        double denom = varNoise + h[0]*Eh[0] + h[1]*Eh[1];
        double[] K = this.K;
        double[][] IKh = this.IKh;

        K[0] = Eh[0] / denom;
        K[1] = Eh[1] / denom;
        IKh[0][0] = 1D - K[0]*h[0];
        IKh[0][1] = -K[0]*h[1];
        IKh[1][0] = -K[1]*h[0];
        IKh[1][1] = 1D - K[1]*h[1];

        double e00 = E[0][0];
        double e01 = E[0][1];

        // Update state.
        E[0][0] = e00 * IKh[0][0] + E[1][0] * IKh[0][1];
        E[0][1] = e01 * IKh[0][0] + E[1][1] * IKh[0][1];
        E[1][0] = e00 * IKh[1][0] + E[1][0] * IKh[1][1];
        E[1][1] = e01 * IKh[1][0] + E[1][1] * IKh[1][1];

        // Covariance matrix, must be positive semi-definite.
        boolean positiveSemiDefinite
                = E[0][0] + E[1][1] >= 0
                    && E[0][0] * E[1][1] - E[0][1] * E[1][0] >= 0
                    && E[0][0] >= 0;

        if (!positiveSemiDefinite)
            throw new IllegalStateException("positiveSemiDefinite");

        slope = slope + K[0] * residual;
        prevOffset = offset;
        offset = offset + K[1] * residual;

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("delay_variation_estimation", systemTimeMs)
                .addField("estimator", hashCode())
                .addField("time_delta", tDelta)
                .addField("ts_delta", tsDelta)
                .addField("tts_delta", tTsDelta)
                .addField("offset", offset)
                .addField("hypothesis", currentHypothesis.getValue()));
        }
    }

    private double updateMinFramePeriod(double tsDelta)
    {
        /**
         * Change from C++ version:
         * We use {@link tsDeltaHist} as a circular buffer initialized
         * with {@code Double.MAX_VALUE}, so we insert new {@link tsDelta}
         * value at {@link tsDeltaHistInsIdx} and we search for the
         * minimum value in {@link tsDeltaHist}
         */
        tsDeltaHist[tsDeltaHistInsIdx] = tsDelta;
        tsDeltaHistInsIdx = (tsDeltaHistInsIdx + 1) % tsDeltaHist.length;

        double min = tsDelta;
        for (double d : tsDeltaHist)
        {
            if (d < min)
                min = d;
        }

        return min;
    }

    private void updateNoiseEstimate(
            double residual,
            double tsDelta,
            boolean stableState)
    {
        if (!stableState)
            return;

        // Faster filter during startup to faster adapt to the jitter level of
        // the network alpha is tuned for 30 frames per second, but is scaled
        // according to tsDelta.
        double alpha = 0.01D;

        if (numOfDeltas > 10 * 30)
            alpha = 0.002D;

        // Only update the noise estimate if we're not over-using beta is a
        // function of alpha and the time delta since the previous update.
        double beta = Math.pow(1 - alpha, tsDelta * 30D / 1000D);

        avgNoise = beta * avgNoise + (1 - beta) * residual;
        varNoise
            = beta * varNoise
                + (1 - beta) * (avgNoise - residual) * (avgNoise - residual);
        if (varNoise < 1)
            varNoise = 1;
    }
}
