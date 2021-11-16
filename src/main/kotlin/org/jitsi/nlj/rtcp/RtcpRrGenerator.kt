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
package org.jitsi.nlj.rtcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import org.jitsi.nlj.transform.node.incoming.IncomingSsrcStats
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.util.schedule
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacketBuilder
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.ms

/**
 * Information about a sender that is used in the generation of RTCP report blocks.  NOTE that this does NOT correspond
 * to the Sender Info block of an SR
 * TODO: rename to not be confused with Sender Info in SR?
 */
private data class SenderInfo(
    var lastSrCompactedTimestamp: Long = 0,
    var lastSrReceivedTime: Long = 0,
    // The media type doesn't affect RTCP RR/SR generation. Initialize with a dummy value.
    var statsSnapshot: IncomingSsrcStats.Snapshot = IncomingSsrcStats.Snapshot(mediaType = MediaType.VIDEO)
) {
    private fun hasReceivedSr(): Boolean = lastSrReceivedTime != 0L

    fun getDelaySinceLastSr(now: Long): Long {
        return if (hasReceivedSr()) {
            ((now - lastSrReceivedTime) * 65.536).toLong()
        } else {
            0
        }
    }
}

/**
 * Retrieves statistics about incoming streams and creates RTCP RR packets.  Since RR packets are created based on
 * time (and not on a number of incoming packets received, etc.) it does not live within the packet pipelines.
 *
 * @param additionalPacketSupplier A function which supplies additional RTCP packets (such as REMB) to be sent together
 * with RRs.
 */
class RtcpRrGenerator(
    private val backgroundExecutor: ScheduledExecutorService,
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    private val incomingStatisticsTracker: IncomingStatisticsTracker,
    private val additionalPacketSupplier: () -> List<RtcpPacket>
) : RtcpListener {
    var running: Boolean = true

    private val senderInfos: MutableMap<Long, SenderInfo> = ConcurrentHashMap()

    init {
        doWork()
    }

    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Long) {
        when (packet) {
            is RtcpSrPacket -> {
                // Note the time we received an SR so that it can be used when creating RtcpReportBlocks
                // TODO: we have a concurrency issue here: we could be halfway through updating the senderinfo when
                // the doWork context thread runs
                val senderInfo = senderInfos.computeIfAbsent(packet.senderSsrc) { SenderInfo() }
                senderInfo.lastSrCompactedTimestamp = packet.senderInfo.compactedNtpTimestamp
                senderInfo.lastSrReceivedTime = receivedTime
            }
        }
    }

    private fun doWork() {
        if (running) {
            val streamStats = incomingStatisticsTracker.getSnapshotOfActiveSsrcs()
            val now = System.currentTimeMillis()
            val reportBlocks = mutableListOf<RtcpReportBlock>()
            streamStats.ssrcStats.forEach { (ssrc, statsSnapshot) ->
                val senderInfo = senderInfos.computeIfAbsent(ssrc) {
                    SenderInfo()
                }
                val fractionLost = statsSnapshot.computeFractionLost(senderInfo.statsSnapshot)
                senderInfo.statsSnapshot = statsSnapshot

                reportBlocks.add(
                    RtcpReportBlock(
                        ssrc,
                        fractionLost,
                        statsSnapshot.cumulativePacketsLost,
                        statsSnapshot.seqNumCycles,
                        statsSnapshot.maxSeqNum,
                        statsSnapshot.jitter.toLong(),
                        senderInfo.lastSrCompactedTimestamp,
                        senderInfo.getDelaySinceLastSr(now)
                    )
                )
            }

            val packets = mutableListOf<RtcpPacket>()
            if (reportBlocks.isNotEmpty()) {
                for (blocks in reportBlocks.chunked(31)) {
                    packets.add(RtcpRrPacketBuilder(reportBlocks = blocks).build())
                }
            }
            packets.addAll(additionalPacketSupplier())

            when (packets.size) {
                0 -> {}
                1 -> rtcpSender(packets.first())
                else -> for (packet in CompoundRtcpPacket.createWithMtu(packets)) { rtcpSender(packet) }
            }
            backgroundExecutor.schedule(this::doWork, reportingInterval)
        }
    }

    companion object {
        /**
         * The interval at which RRs and REMBs will be sent.
         */
        val reportingInterval = 500.ms
    }
}
