/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.VideoPayloadType
import org.jitsi.nlj.rtp.PaddingVideoPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.PacketCache
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * [ProbingDataSender] currently supports probing via 2 methods:
 * 1) retransmitting previous packets via RTX via [sendRedundantDataOverRtx].
 * 2) If RTX is not available, or, not enough packets to retransmit are available, we
 * can send empty media packets using the bridge's ssrc
 *
 */
class ProbingDataSender(
    private val packetCache: PacketCache,
    private val rtxDataSender: PacketHandler,
    private val garbageDataSender: PacketHandler,
    private val diagnosticContext: DiagnosticContext,
    streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : EventHandler, NodeStatsProducer {

    private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.javaClass)
    private val logger = createChildLogger(parentLogger)

    private var rtxSupported = false
    private val videoPayloadTypes: MutableSet<VideoPayloadType> = ConcurrentHashMap.newKeySet()
    private var localVideoSsrc: Long? = null

    // Stats
    private var numProbingBytesSentRtx: Long = 0
    private var numProbingBytesSentDummyData: Long = 0

    init {
        streamInformationStore.onRtpPayloadTypesChanged { currentRtpPayloadTypes ->
            if (currentRtpPayloadTypes.isEmpty()) {
                videoPayloadTypes.clear()
                rtxSupported = false
            } else {
                currentRtpPayloadTypes.values.forEach { pt ->
                    if (!rtxSupported && pt is RtxPayloadType) {
                        rtxSupported = true
                        logger.cdebug { "RTX payload type signaled, enabling RTX probing" }
                    }
                    if (pt is VideoPayloadType) {
                        videoPayloadTypes.add(pt)
                    }
                }
            }
        }
    }

    fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int {
        var totalBytesSent = 0

        if (rtxSupported) {
            for (mediaSsrc in mediaSsrcs) {
                if (totalBytesSent >= numBytes) {
                    break
                }
                val rtxBytesSent = sendRedundantDataOverRtx(mediaSsrc, numBytes - totalBytesSent)
                numProbingBytesSentRtx += rtxBytesSent
                totalBytesSent += rtxBytesSent
                if (timeSeriesLogger.isTraceEnabled()) {
                    timeSeriesLogger.trace(
                        diagnosticContext
                            .makeTimeSeriesPoint("rtx_probing_bytes")
                            .addField("ssrc", mediaSsrc)
                            .addField("bytes", rtxBytesSent)
                    )
                }
            }
        }
        if (totalBytesSent < numBytes) {
            val dummyBytesSent = sendDummyData(numBytes - totalBytesSent)
            numProbingBytesSentDummyData += dummyBytesSent
            totalBytesSent += dummyBytesSent
            if (timeSeriesLogger.isTraceEnabled()) {
                timeSeriesLogger.trace(
                    diagnosticContext
                        .makeTimeSeriesPoint("dummy_probing_bytes")
                        .addField("bytes", dummyBytesSent)
                )
            }
        }

        return totalBytesSent
    }

    /**
     * Using the RTX stream associated with [mediaSsrc], send [numBytes] of data
     * by re-transmitting previously sent packets from the outgoing packet cache.
     * Returns the number of bytes transmitted
     */
    private fun sendRedundantDataOverRtx(mediaSsrc: Long, numBytes: Int): Int {
        var bytesSent = 0
        // TODO(brian): we're in a thread context mess here.  we'll be sending these out from the bandwidthprobing
        // context (or whoever calls this) which i don't think we want.  Need look at getting all the pipeline
        // work posted to one thread so we don't have to worry about concurrency nightmares

        // Get the most recent packets whose length add up to no more than numBytes.
        packetCache.getMany(mediaSsrc, numBytes).forEach {
            bytesSent += it.length
            rtxDataSender.processPacket(PacketInfo(it))
        }
        return bytesSent
    }

    private var currDummyTimestamp = random.nextLong() and 0xFFFFFFFF
    private var currDummySeqNum = random.nextInt(0xFFFF)

    private fun sendDummyData(numBytes: Int): Int {
        var bytesSent = 0
        val pt = videoPayloadTypes.firstOrNull() ?: return bytesSent
        val senderSsrc = localVideoSsrc ?: return bytesSent

        while (bytesSent < numBytes) {
            val remainingBytes = numBytes - bytesSent
            if (remainingBytes < RtpHeader.FIXED_HEADER_SIZE_BYTES) {
                break
            }
            val paddingSize = (remainingBytes - RtpHeader.FIXED_HEADER_SIZE_BYTES).coerceAtMost(0xFF)
            val packetLength = RtpHeader.FIXED_HEADER_SIZE_BYTES + paddingSize

            val paddingPacket = PaddingVideoPacket.create(packetLength.toInt())
            paddingPacket.payloadType = pt.pt.toPositiveInt()
            paddingPacket.ssrc = senderSsrc
            paddingPacket.timestamp = currDummyTimestamp
            paddingPacket.sequenceNumber = currDummySeqNum
            garbageDataSender.processPacket(PacketInfo(paddingPacket))

            currDummySeqNum++
            bytesSent += packetLength
        }
        currDummyTimestamp += 3000

        return bytesSent
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                if (MediaType.VIDEO == event.mediaType) {
                    logger.cdebug { "Setting video ssrc to ${event.ssrc}" }
                    localVideoSsrc = event.ssrc
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Probing data sender").apply {
            addNumber("num_bytes_of_probing_data_sent_as_rtx", numProbingBytesSentRtx)
            addNumber("num_bytes_of_probing_data_sent_as_dummy", numProbingBytesSentDummyData)
            addBoolean("rtx_supported", rtxSupported)
            addString("local_video_ssrc", localVideoSsrc.toString())
            addString("curr_dummy_timestamp", currDummyTimestamp.toString())
            addString("curr_dummy_seq_num", currDummySeqNum.toString())
            addString("video_payload_types", videoPayloadTypes.toString())
        }
    }

    companion object {
        val random = Random()
    }
}
