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
package org.jitsi.nlj.rtcp

import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.util.RtpUtils
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.SenderInfo
import unsigned.toULong
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RtcpSrGenerator(
    private val executor: ScheduledExecutorService,
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    private val outgoingStatisticsTracker: OutgoingStatisticsTracker
) {
    private val logger = getLogger(this::class.java)
    var running: Boolean = true

    init {
        doWork()
    }

    private fun doWork() {
        if (running) {
            val streamStats = outgoingStatisticsTracker.getCurrentStats()
            val now = System.currentTimeMillis()
            streamStats.forEach { ssrc, sendStats ->
                val statsSnapshot = sendStats.getSnapshot()
                val senderInfo = SenderInfo(
                    ntpTimestamp = RtpUtils.millisToNtpTimestamp(now),
                    //TODO: from what I can tell, the old code didn't generate an RTP timestamp to map to the current
                    // ntp timestamp, and instead used the most recent rtp timestamp we'd seen
                    rtpTimestamp = statsSnapshot.mostRecentRtpTimestamp,
                    sendersPacketCount = statsSnapshot.packetCount.toULong(),
                    sendersOctetCount = statsSnapshot.octetCount.toULong()
                )

                val srPacket = RtcpSrPacket(
                    header = RtcpHeader(packetType = RtcpSrPacket.PT, senderSsrc = ssrc),
                    senderInfo = senderInfo
                )
                logger.cdebug { "Sending SR packet $srPacket" }
                rtcpSender(srPacket)
            }

            executor.schedule(this::doWork, 1, TimeUnit.SECONDS)
        }
    }
}
