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

package org.jitsi.nlj.rtcp

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * [KeyframeRequester] handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the [KeyframeRequester#requestKeyframe]
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
class KeyframeRequester(
    private val streamInformationStore: StreamInformationStore
) : TransformerNode("Keyframe Requester") {

    // Map a SSRC to the timestamp (in ms) of when we last requested a keyframe for it
    private val keyframeRequests = mutableMapOf<Long, Long>()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private val keyframeRequestsSyncRoot = Any()
    private var localSsrc: Long? = null
    // Support for FIR and PLI is declared per-payload type, but currently
    // our code which requests FIR and PLI is not payload-type aware. So
    // until this changes we will just check if any of the PTs supports
    // FIR and PLI. This means that we effectively always assume support for FIR.
    private val hasFirSupport: Boolean = true
    private var waitIntervalMs = DEFAULT_WAIT_INTERVAL_MS

    // Stats

    // Number of PLI/FIRs received and forwarded to the endpoint.
    private var numPlisForwarded: Int = 0
    private var numFirsForwarded: Int = 0
    // Number of PLI/FIRs received but dropped due to throttling.
    private var numPlisDropped: Int = 0
    private var numFirsDropped: Int = 0
    // Number of PLI/FIRs generated as a result of an API request or due to translation between PLI/FIR.
    private var numPlisGenerated: Int = 0
    private var numFirsGenerated: Int = 0
    // Number of calls to requestKeyframe
    private var numApiRequests: Int = 0
    // Number of calls to requestKeyframe ignored due to throttling
    private var numApiRequestsDropped: Int = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet.apply {
            when (this) {
                is CompoundRtcpPacket -> {
                    packets.first { it is RtcpFbPliPacket || it is RtcpFbFirPacket } as RtcpFbPacket
                }
                is RtcpFbFirPacket -> this
                is RtcpFbPliPacket -> this
                else -> return@transform packetInfo
            }
        }

        val now = System.currentTimeMillis()
        var sourceSsrc: Long
        var canSend: Boolean
        var forward: Boolean
        when (packet) {
            is RtcpFbPliPacket -> {
                sourceSsrc = packet.mediaSourceSsrc
                canSend = canSendKeyframeRequest(sourceSsrc, now)
                forward = canSend && streamInformationStore.supportsPli
                if (forward) numPlisForwarded++
                if (!canSend) numPlisDropped++
            }
            is RtcpFbFirPacket ->
            {
                sourceSsrc = packet.mediaSenderSsrc
                canSend = canSendKeyframeRequest(sourceSsrc, now)
                // When both are supported, we favor generating a PLI rather than forwarding a FIR
                forward = canSend && hasFirSupport && !streamInformationStore.supportsPli
                if (forward) {
                    // When we forward a FIR we need to update the seq num.
                    packet.seqNum = firCommandSequenceNumber.incrementAndGet()
                    // We manage the seq num space, so we should use the same SSRC
                    localSsrc?.let { packet.mediaSenderSsrc = it }
                    numFirsForwarded++
                }
                if (!canSend) numFirsDropped++
            }
            // This is now possible, but the compiler doesn't know it.
            else -> throw IllegalStateException("Packet is neither PLI nor FIR")
        }

        if (!forward && canSend) {
            doRequestKeyframe(sourceSsrc)
        }

        return if (forward) packetInfo else null
    }

    /**
     * Returns 'true' when at least one method is supported, AND we haven't sent a request very recently.
     */
    private fun canSendKeyframeRequest(mediaSsrc: Long, nowMs: Long): Boolean {
        if (!streamInformationStore.supportsPli && !hasFirSupport) {
            return false
        }
        synchronized(keyframeRequestsSyncRoot) {
            return if (nowMs - keyframeRequests.getOrDefault(mediaSsrc, 0) < waitIntervalMs) {
                logger.cdebug { "Sent a keyframe request less than ${waitIntervalMs}ms ago for $mediaSsrc, " +
                        "ignoring request" }
                false
            } else {
                keyframeRequests[mediaSsrc] = nowMs
                logger.cdebug { "Keyframe requester requesting keyframe for $mediaSsrc" }
                true
            }
        }
    }

    @JvmOverloads
    fun requestKeyframe(mediaSsrc: Long, now: Long = System.currentTimeMillis()) {
        numApiRequests++
        if (!canSendKeyframeRequest(mediaSsrc, now)) {
            numApiRequestsDropped++
            return
        }

        doRequestKeyframe(mediaSsrc)
    }

    private fun doRequestKeyframe(mediaSsrc: Long) {
        val pkt = when {
            streamInformationStore.supportsPli -> {
                numPlisGenerated++
                RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            }
            hasFirSupport -> {
                numFirsGenerated++
                RtcpFbFirPacketBuilder(
                    mediaSenderSsrc = mediaSsrc,
                    firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
                ).build()
            }
            else -> {
                logger.warn("Can not send neither PLI nor FIR")
                return
            }
        }

        next(PacketInfo(pkt))
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                if (event.mediaType == MediaType.VIDEO) {
                    localSsrc = event.ssrc
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addBoolean("has_fir_support", hasFirSupport)
            addString("wait_interval_ms", waitIntervalMs.toString()) // use string to prevent aggregation
            addNumber("num_api_requests", numApiRequests)
            addNumber("num_api_requests_dropped", numApiRequestsDropped)
            addNumber("num_firs_dropped", numFirsDropped)
            addNumber("num_firs_generated", numFirsGenerated)
            addNumber("num_firs_forwarded", numFirsForwarded)
            addNumber("num_plis_dropped", numPlisDropped)
            addNumber("num_plis_generated", numPlisGenerated)
            addNumber("num_plis_forwarded", numPlisForwarded)
        }
    }

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitIntervalMs = min(DEFAULT_WAIT_INTERVAL_MS, newRtt.toInt() + 10)
    }

    companion object {
        private const val DEFAULT_WAIT_INTERVAL_MS = 100
    }
}