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
package org.jitsi.nlj

import org.jitsi.nlj.transform.node.incoming.RtcpListener
import org.jitsi.nlj.transform.node.incoming.StatisticsTracker
import org.jitsi.nlj.transform.node.incoming.StreamStatisticsSnapshot
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private data class SenderInfo(
    var lastSrCompactedTimestamp: Int = 0,
    var lastSrReceivedTime: Long = 0,
    var statsSnapshot: StreamStatisticsSnapshot = StreamStatisticsSnapshot()
)

/**
 * Retrieves statistics about incoming streams and creates RTCP RR packets
 */
class RtcpRrGenerator(
    private val executor: ScheduledExecutorService,
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    private val statisticsTracker: StatisticsTracker
) : RtcpListener {
    var running: Boolean = true

    private val senderInfos: MutableMap<Long, SenderInfo> = ConcurrentHashMap()

    init {
        doWork()
    }

    override fun onRtcpPacket(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        when (packet) {
            is RtcpSrPacket -> {
                senderInfos.computeIfAbsent(packet.header.senderSsrc) {
                    SenderInfo(
                        lastSrCompactedTimestamp = packet.senderInfo.compactedNtpTimestamp,
                        lastSrReceivedTime = packetInfo.receivedTime
                    )
                }
            }
        }
    }

    private fun doWork() {
        if (running) {
            val streamStats = statisticsTracker.getCurrentStats()
            val now = System.currentTimeMillis()
            val reportBlocks = mutableListOf<RtcpReportBlock>()
            streamStats.forEach { ssrc, stats ->
                val statsSnapshot = stats.getSnapshot()
                val senderInfo = senderInfos.computeIfAbsent(ssrc) {
                    SenderInfo()
                }
                val statsDelta = statsSnapshot.getDelta(senderInfo.statsSnapshot)
                reportBlocks.add(RtcpReportBlock(
                    ssrc,
                    (statsDelta.cumulativePacketsLost / statsDelta.numExpectedPackets) * 256,
                    statsDelta.cumulativePacketsLost,
                    statsDelta.seqNumCycles,
                    statsDelta.maxSeqNum,
                    statsDelta.jitter.toLong(),
                    senderInfo.lastSrCompactedTimestamp.toLong(),
                    ((now - senderInfo.lastSrReceivedTime) * 65.536).toLong()
                ))
            }
            if (reportBlocks.isNotEmpty()) {
                val rrPacket = RtcpRrPacket(
                    header = RtcpHeader(
                        reportCount = reportBlocks.size,
                        packetType = RtcpRrPacket.PT
                    ),
                    reportBlocks = reportBlocks
                )
                println("Generated RR: $rrPacket")
                rtcpSender(rrPacket)
            }
            executor.schedule(this::doWork, 1, TimeUnit.SECONDS)
        }
    }

}
