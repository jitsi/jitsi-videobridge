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

import org.jitsi.utils.TimestampUtils;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;

/**
 * Helper class to compute the inter-arrival time delta and the size delta
 * between two timestamp groups. A timestamp is a 32 bit unsigned number with a
 * client defined rate.
 *
 * webrtc/modules/remote_bitrate_estimator/inter_arrival.cc
 * webrtc/modules/remote_bitrate_estimator/inter_arrival.h
 *
 * @author Lyubomir Marinov
 * @author Julian Chukwu
 * @author George Politis
 */
class InterArrival
{
    private static final int kBurstDeltaThresholdMs  = 5;

    private final Logger logger;
    private final TimeSeriesLogger timeSeriesLogger;

    /**
     * webrtc/modules/include/module_common_types.h
     *
     * @param timestamp1
     * @param timestamp2
     * @return
     */
    private static long latestTimestamp(long timestamp1, long timestamp2)
    {
        return TimestampUtils.latestTimestamp(timestamp1, timestamp2);
    }

    private boolean burstGrouping;

    private TimestampGroup currentTimestampGroup = new TimestampGroup();

    private final long kTimestampGroupLengthTicks;

    private final DiagnosticContext diagnosticContext;

    private TimestampGroup prevTimestampGroup = new TimestampGroup();

    private double timestampToMsCoeff;

    private int numConsecutiveReorderedPackets;

    // After this many packet groups received out of order InterArrival will
    // reset, assuming that clocks have made a jump.
    private static final int kReorderedResetThreshold = 3;
    private static final int kArrivalTimeOffsetThresholdMs = 3000;

    /**
     * A timestamp group is defined as all packets with a timestamp which are at
     * most {@code timestampGroupLengthTicks} older than the first timestamp in
     * that group.
     *
     * @param timestampGroupLengthTicks
     * @param timestampToMsCoeff
     * @param enableBurstGrouping
     */
    public InterArrival(
            long timestampGroupLengthTicks,
            double timestampToMsCoeff,
            boolean enableBurstGrouping,
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull Logger parentLogger)
    {
        kTimestampGroupLengthTicks = timestampGroupLengthTicks;
        this.timestampToMsCoeff = timestampToMsCoeff;
        burstGrouping = enableBurstGrouping;
        numConsecutiveReorderedPackets = 0;
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(getClass());
    }

    private boolean belongsToBurst(long arrivalTimeMs, long timestamp)
    {
        if (!burstGrouping)
            return false;

        if (currentTimestampGroup.completeTimeMs < 0)
        {
            throw new IllegalStateException(
                    "currentTimestampGroup.completeTimeMs");
        }

        long arrivalTimeDeltaMs
            = arrivalTimeMs - currentTimestampGroup.completeTimeMs;

        long timestampDiff = TimestampUtils
            .subtractAsUnsignedInt32(timestamp, currentTimestampGroup.timestamp);

        long tsDeltaMs = (long) (timestampToMsCoeff * timestampDiff + 0.5);

        if (tsDeltaMs == 0)
            return true;

        long propagationDeltaMs = arrivalTimeDeltaMs - tsDeltaMs;

        return
            propagationDeltaMs < 0
                && arrivalTimeDeltaMs <= kBurstDeltaThresholdMs;
    }

    /**
     * Returns {@code true} if a delta was computed, or {@code false} if the
     * current group is still incomplete or if only one group has been
     * completed.
     *
     * @param timestamp is the timestamp.
     * @param arrivalTimeMs is the local time at which the packet arrived.
     * @param packetSize is the size of the packet.
     * @param deltas {@code timestampDelta} is the computed timestamp delta,
     * {@code arrivalTimeDeltaMs} is the computed arrival-time delta,
     * {@code packetSizeDelta} is the computed size delta.
     * @return
     *
     * @Note: We have two {@code computeDeltas}.
     * One with a valid {@code systemTimeMs} according to webrtc
     * implementation as of June 12,2017 and a previous one
     * with a default systemTimeMs (-1L). the later may be removed or
     * deprecated.
     */
    public boolean computeDeltas(
            long timestamp,
            long arrivalTimeMs,
            int packetSize,
            long[] deltas){

                return computeDeltas(timestamp,arrivalTimeMs,
                        packetSize,deltas,-1L);
    }

    public boolean computeDeltas(
            long timestamp,
            long arrivalTimeMs,
            int packetSize,
            long[] deltas,
            long systemTimeMs)
    {
        if (deltas == null)
            throw new NullPointerException("deltas");
        if (deltas.length != 3)
            throw new IllegalArgumentException("deltas.length");

        boolean calculatedDeltas = false;

        if (currentTimestampGroup.isFirstPacket())
        {
            // We don't have enough data to update the filter, so we store it
            // until we have two frames of data to process.
            currentTimestampGroup.timestamp = timestamp;
            currentTimestampGroup.firstTimestamp = timestamp;
        }
        else if (!isPacketInOrder(timestamp))
        {
            return false;
        }
        else if (isNewTimestampGroup(arrivalTimeMs, timestamp))
        {
            // First packet of a later frame, the previous frame sample is
            // ready.
            if (prevTimestampGroup.completeTimeMs >= 0)
            {
                /* long timestampDelta */ deltas[0]
                    = TimestampUtils.subtractAsUnsignedInt32(
                        currentTimestampGroup.timestamp,
                        prevTimestampGroup.timestamp);

                long arrivalTimeDeltaMs
                    = deltas[1]
                    = currentTimestampGroup.completeTimeMs
                        - prevTimestampGroup.completeTimeMs;

                // Check system time differences to see if we have an unproportional jump
                // in arrival time. In that case reset the inter-arrival computations.
                long systemTimeDeltaMs =
                        currentTimestampGroup.lastSystemTimeMs -
                                prevTimestampGroup.lastSystemTimeMs;
                if (prevTimestampGroup.lastSystemTimeMs != -1L &&
                        currentTimestampGroup.lastSystemTimeMs != -1L &&
                        arrivalTimeDeltaMs - systemTimeDeltaMs >=
                    kArrivalTimeOffsetThresholdMs) {
                    logger.warn( "The arrival time clock offset has changed (diff = "
                            + (arrivalTimeDeltaMs - systemTimeDeltaMs)
                            +  " ms), resetting.");
                    Reset();
                    return false;
                }

                if (arrivalTimeDeltaMs < 0)
                {
                    ++numConsecutiveReorderedPackets;
                    if (numConsecutiveReorderedPackets >= kReorderedResetThreshold) {
                        // The group of packets has been reordered since receiving
                        // its local arrival timestamp.
                        logger.warn(
                                "Packets are being reordered on the path from the "
                                    + "socket to the bandwidth estimator. Ignoring "
                                    + "this packet for bandwidth estimation.");
                        Reset();
                    }
                    return false;
                }
                else
                {
                    numConsecutiveReorderedPackets = 0;
                }
                /* int packetSizeDelta */ deltas[2]
                    = (int)
                        (currentTimestampGroup.size - prevTimestampGroup.size);

                if (timeSeriesLogger.isTraceEnabled())
                {
                    timeSeriesLogger.trace(diagnosticContext
                        .makeTimeSeriesPoint("computed_deltas")
                        .addField("inter_arrival", hashCode())
                        .addField("arrival_time_ms", arrivalTimeMs)
                        .addField("timestamp_delta", deltas[0])
                        .addField("arrival_time_ms_delta", deltas[1])
                        .addField("payload_size_delta", deltas[2]));
                }

                calculatedDeltas = true;
            }
            prevTimestampGroup.copy(currentTimestampGroup);
            // The new timestamp is now the current frame.
            currentTimestampGroup.firstTimestamp = timestamp;
            currentTimestampGroup.timestamp = timestamp;
            currentTimestampGroup.size = 0;

        }
        else
        {
            currentTimestampGroup.timestamp = latestTimestamp(
                    currentTimestampGroup.timestamp, timestamp);
        }
        // Accumulate the frame size.
        currentTimestampGroup.size += packetSize;
        currentTimestampGroup.completeTimeMs = arrivalTimeMs;
        currentTimestampGroup.lastSystemTimeMs = systemTimeMs;

        return calculatedDeltas;
    }

    /**
     * Returns {@code true} if the last packet was the end of the current batch
     * and the packet with {@code timestamp} is the first of a new batch.
     *
     * @param arrivalTimeMs
     * @param timestamp
     * @return
     */
    private boolean isNewTimestampGroup(long arrivalTimeMs, long timestamp)
    {
        if (currentTimestampGroup.isFirstPacket())
        {
            return false;
        }
        else if (belongsToBurst(arrivalTimeMs, timestamp))
        {
            return false;
        }
        else
        {
            long timestampDiff
                = TimestampUtils.subtractAsUnsignedInt32(
                    timestamp, currentTimestampGroup.firstTimestamp);

            return timestampDiff > kTimestampGroupLengthTicks;
        }
    }

    /**
     * Returns {@code true} if the packet with timestamp {@code timestamp}
     * arrived in order.
     *
     * @param timestamp
     * @return
     */
    private boolean isPacketInOrder(long timestamp)
    {
        if (currentTimestampGroup.isFirstPacket())
        {
            return true;
        }
        else
        {
            // Assume that a diff which is bigger than half the timestamp
            // interval (32 bits) must be due to reordering. This code is almost
            // identical to that in isNewerTimestamp() in module_common_types.h.
            long timestampDiff
                = TimestampUtils.subtractAsUnsignedInt32(
                    timestamp, currentTimestampGroup.firstTimestamp);

            long tsDeltaMs = (long) (timestampToMsCoeff * timestampDiff + 0.5);
            boolean inOrder = timestampDiff < 0x80000000L;

            if (!inOrder && timeSeriesLogger.isTraceEnabled())
            {
                timeSeriesLogger.trace(diagnosticContext
                        .makeTimeSeriesPoint("reordered_packet")
                        .addField("inter_arrival", hashCode())
                        .addField("ts_delta_ms", tsDeltaMs));
            }

            return inOrder;
        }
    }

    private static class TimestampGroup
    {
        public long completeTimeMs = -1L;

        public long size = 0L;

        public long firstTimestamp = 0L;

        public long timestamp = 0L;

        public long lastSystemTimeMs = -1L;

        /**
         * Assigns the values of the fields of <tt>source</tt> to the respective
         * fields of this {@code TimestampGroup}.
         *
         * @param source the {@code TimestampGroup} the values of the fields of
         * which are to be assigned to the respective fields of this
         * {@code TimestampGroup}
         */
        public void copy(TimestampGroup source)
        {
            completeTimeMs = source.completeTimeMs;
            firstTimestamp = source.firstTimestamp;
            size = source.size;
            timestamp = source.timestamp;
        }

        public boolean isFirstPacket()
        {
            return completeTimeMs == -1L;
        }
    }

    public void Reset() {
        numConsecutiveReorderedPackets = 0;
        currentTimestampGroup = new TimestampGroup();
        prevTimestampGroup = new TimestampGroup();

    }
}
