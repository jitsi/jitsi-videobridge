/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.utils.stats.RateStatistics
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.rtp.rtp.RtpPacket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class OutgoingStatisticsTracker : ObserverNode("Outgoing statistics tracker") {
    private val ssrcStats: MutableMap<Long, OutgoingSsrcStats> = ConcurrentHashMap()

    /**
     * The bitrate in bits per seconds.
     */
    private val bitrate = RateStatistics(Duration.ofSeconds(1).toMillis().toInt())
    /**
     * The packet rate in packets per second.
     */
    private val packetRate = RateStatistics(Duration.ofSeconds(1).toMillis().toInt(), 1000f)

    private var bytesSent: Long = 0
    private var packetsSent: Long = 0

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val stats = ssrcStats.computeIfAbsent(rtpPacket.ssrc) {
            OutgoingSsrcStats(rtpPacket.ssrc)
        }
        stats.packetSent(rtpPacket.length, rtpPacket.timestamp)

        val now = System.currentTimeMillis()
        val bytes = rtpPacket.length
        bitrate.update(bytes, now)
        packetRate.update(1, now)
        bytesSent += bytes
        packetsSent++
    }

    fun getSnapshot(): OutgoingStatisticsSnapshot {
        val now = System.currentTimeMillis()
        return OutgoingStatisticsSnapshot(
            bitrate.getRate(now),
            packetRate.getRate(now),
            bytesSent,
            packetsSent,
            ssrcStats.map { (ssrc, stats) ->
                Pair(ssrc, stats.getSnapshot())
            }.toMap()
        )
    }
}

class OutgoingStatisticsSnapshot(
    /**
     * Bitrate in bits per second.
     */
    val bitrate: Long,
    /**
     * Packet rate in packets per second.
     */
    val packetRate: Long,
    /**
     * Number of bytes sent in RTP packets.
     */
    val bytesSent: Long,
    /**
     * Number of RTP packets sent.
     */
    val packetsSent: Long,
    /**
     * Per-ssrc stats.
     */
    val ssrcStats: Map<Long, OutgoingSsrcStats.Snapshot>
)

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
        synchronized (statsLock) {
            packetCount++
            octetCount += packetSizeOctets
            mostRecentRtpTimestamp = rtpTimestamp
        }
    }

    fun getSnapshot(): Snapshot {
        synchronized (statsLock) {
            return Snapshot(packetCount, octetCount, mostRecentRtpTimestamp)
        }
    }

    data class Snapshot(
        val packetCount: Int,
        val octetCount: Int,
        val mostRecentRtpTimestamp: Long
    )
}
