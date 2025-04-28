/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import java.util.concurrent.ConcurrentHashMap

class OutgoingStatisticsTracker(
    private val diagnosticContext: DiagnosticContext,
) : ObserverNode("Outgoing statistics tracker") {
    /**
     * Per-SSRC statistics
     */
    private val ssrcStats: MutableMap<Long, OutgoingSsrcStats> = ConcurrentHashMap()

    private var numAudioPackets = 0
    private var numVideoPackets = 0

    private val videoBitrate = BitrateCalculator.createBitrateTracker()

    private val videoBitratesByOrigin = mutableMapOf<PacketOrigin, BitrateTracker>()

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()

        when (rtpPacket) {
            is AudioRtpPacket -> numAudioPackets++
            is VideoRtpPacket -> {
                numVideoPackets++

                /* These bitrate measurements are only used in the timeseries log below, so only calculate them
                 * if the timeseries is enabled. */
                if (timeseriesLogger.isTraceEnabled) {
                    videoBitrate.update(rtpPacket.length.bytes)
                    videoBitratesByOrigin.getOrPut(packetInfo.packetOrigin) {
                        BitrateCalculator.createBitrateTracker()
                    }.update(rtpPacket.length.bytes)
                }
            }
        }

        val stats = ssrcStats.computeIfAbsent(rtpPacket.ssrc) {
            OutgoingSsrcStats(rtpPacket.ssrc)
        }
        stats.packetSent(rtpPacket.length, rtpPacket.timestamp)
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        val stats = getSnapshot()
        stats.ssrcStats.forEach { (ssrc, streamStats) ->
            addJson(ssrc.toString(), streamStats.toJson())
        }
    }

    /**
     * Don't aggregate the per-SSRC stats.
     */
    override fun getNodeStatsToAggregate(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_audio_packets", numAudioPackets)
        addNumber("num_video_packets", numVideoPackets)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    fun getSnapshot(): OutgoingStatisticsSnapshot {
        return OutgoingStatisticsSnapshot(
            ssrcStats.map { (ssrc, stats) ->
                Pair(ssrc, stats.getSnapshot())
            }.toMap()
        ).also {
            if (timeseriesLogger.isTraceEnabled) {
                val point = diagnosticContext.makeTimeSeriesPoint("sent_video_stream_stats")
                    .addField("bitrate_bps", videoBitrate.rate.bps)
                videoBitratesByOrigin.forEach { (origin, tracker) ->
                    point.addField("video_${origin}_bitrate", tracker.rate.bps)
                }

                timeseriesLogger.trace(point)
            }
        }
    }

    fun getSsrcSnapshot(ssrc: Long): OutgoingSsrcStats.Snapshot? {
        return ssrcStats[ssrc]?.getSnapshot()
    }

    companion object {
        private val timeseriesLogger = TimeSeriesLogger.getTimeSeriesLogger(OutgoingStatisticsTracker::class.java)
    }
}

class OutgoingStatisticsSnapshot(
    /**
     * Per-ssrc stats.
     */
    val ssrcStats: Map<Long, OutgoingSsrcStats.Snapshot>
) {
    fun toJson() = OrderedJsonObject().apply {
        ssrcStats.forEach { (ssrc, snapshot) ->
            put(ssrc, snapshot.toJson())
        }
    }
}

class OutgoingSsrcStats(
    private val ssrc: Long
) {
    private var statsLock = Any()

    // Start variables protected by statsLock
    private var packetCount: Int = 0
    private var octetCount: Int = 0
    private var mostRecentRtpTimestamp: Long = 0
    // End variables protected by statsLock

    fun packetSent(packetSizeOctets: Int, rtpTimestamp: Long) {
        synchronized(statsLock) {
            packetCount++
            octetCount += packetSizeOctets
            mostRecentRtpTimestamp = rtpTimestamp
        }
    }

    fun getSnapshot(): Snapshot {
        synchronized(statsLock) {
            return Snapshot(packetCount, octetCount, mostRecentRtpTimestamp)
        }
    }

    data class Snapshot(
        val packetCount: Int,
        val octetCount: Int,
        val mostRecentRtpTimestamp: Long
    ) {
        fun toJson() = OrderedJsonObject().apply {
            put("packet_count", packetCount)
            put("octet_count", octetCount)
            put("most_recent_rtp_timestamp", mostRecentRtpTimestamp)
        }
    }
}
