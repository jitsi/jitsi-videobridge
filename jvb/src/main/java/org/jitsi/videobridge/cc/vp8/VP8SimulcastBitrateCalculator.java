/*
 * Copyright @ 2020
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
package org.jitsi.videobridge.cc.vp8;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * This class is responsible for simple bitrate calculation of received
 * simulcast streams before upscaling to avoid continuous switchings
 * between the spatial layers when a client has bad uplink channel
 *
 * @author Daniil Meitis
 */
class VP8SimulcastBitrateCalculator
{
    /**
     * Internal structure containing receive time
     * and size of a particular stream packet
     */
    private class StreamPacketInfo
    {
        /**
         * Packet receive time in ms
         */
        long receivedMs;

        /**
         * Size of a received packet in bytes
         */
        int size;

        public StreamPacketInfo(long receivedMs, int size)
        {
            this.receivedMs = receivedMs;
            this.size = size;
        }
    }

    /**
     * Internal class that keeps StreamPacketInfo
     * for the last packets of stream and calculate its bitrate
     */
    private class StreamInfo
    {
        /**
         * Back window size to calculate stream's bitrate
         */
        private static final int STREAM_INFO_QUEUE_SIZE = 10;

        /**
         * Last {@link STREAM_INFO_QUEUE_SIZE} packets of the stream
         */
        private LinkedList<StreamPacketInfo> packets = new LinkedList<>();

        /**
         * Total bytes received in back window period
         */
        private int totalBytes = 0;

        public StreamInfo()
        {
        }

        /**
         * Updates internal structure of the calculator
         *
         * @param size the size of the incoming VP8 packet.
         * @param receivedMs receive time of the incoming packet in ms.
         */
        public synchronized void update(int size, long receivedMs)
        {
            while (packets.size() >= 10)
            {
                totalBytes -= packets.remove().size;
                assert totalBytes >= 0;
            }
            packets.add(new StreamPacketInfo(receivedMs, size));
            totalBytes += size;
        }

        /**
         * Calculate current bitrate of the stream
         *
         * @return current stream's bitrate.
         */
        public long getBitrate()
        {
            if (packets.isEmpty())
            {
                return Long.MAX_VALUE;
            }

            long timeDelta = clock.instant().toEpochMilli()
                - packets.peek().receivedMs;

            return timeDelta == 0 ? Long.MAX_VALUE
                : totalBytes * 8 * 1000 / timeDelta;
        }
    }

    /**
     * Minimal bitrates for the spatial layers according to WebRTC simulcast.cc
     * for 180p, 360p and 720p respectively.
     *
     * TODO: add support of 1080p, 540p, 270p
     */
    private static final int[] SPATIAL_LAYER_MINIMAL_BITRATE = {0, 150000, 600000};

    /**
     * The {@link StreamInfo} instances mapped to the spatial layers.
     */
    private final Map<Integer, StreamInfo> streamInfos = new HashMap<>();

    private Clock clock;

    public VP8SimulcastBitrateCalculator(@NotNull Clock clock)
    {
        this.clock = clock;
    }

    /**
     * Updates internal structure of the calculator.
     *
     * @param spatialIndex the quality index of the incoming VP8 packet.
     * @param size the size of the incoming VP8 packet.
     * @param receivedMs receive time of the incoming packet in ms.
     */
    public synchronized void update(int spatialIndex, int size, long receivedMs)
    {
        /**
         * We want to calculate bitrate only for "middle" and "high"
         * spatial layer streams and always project "low"
         */
        if (spatialIndex > 0 && spatialIndex <= 2)
        {
            StreamInfo streamInfo = streamInfos.computeIfAbsent(spatialIndex,
                index -> new StreamInfo());

            streamInfo.update(size, receivedMs);
        }
    }

    public boolean isStreamStable(int spatialIndex)
    {
        /**
         * Calculate bitrate only for "middle" and "high"
         */
        if (spatialIndex < 1 || spatialIndex > 2)
        {
            return true;
        }

        StreamInfo stream = streamInfos.get(spatialIndex);
        return stream == null ? false
            : stream.getBitrate() >= SPATIAL_LAYER_MINIMAL_BITRATE[spatialIndex];
    }
}