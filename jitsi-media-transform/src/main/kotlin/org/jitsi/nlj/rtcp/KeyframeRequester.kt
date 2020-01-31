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
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.logging2.cdebug
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
class KeyframeRequester @JvmOverloads constructor(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemDefaultZone()
) : TransformerNode("Keyframe Requester") {
    private val logger = createChildLogger(parentLogger)

    // Map a SSRC to the timestamp (represented as an [Instant]) of when we last requested a keyframe for it
    private val keyframeRequests = mutableMapOf<Long, Instant>()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private val keyframeRequestsSyncRoot = Any()
    private var localSsrc: Long? = null
    private var waitInterval = DEFAULT_WAIT_INTERVAL

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
        val pliOrFirPacket = packetInfo.getPliOrFirPacket() ?: return packetInfo

        val now = clock.instant()
        val sourceSsrc: Long
        val canSend: Boolean
        val forward: Boolean
        when (pliOrFirPacket) {
            is RtcpFbPliPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSourceSsrc
                canSend = canSendKeyframeRequest(sourceSsrc, now)
                forward = canSend && streamInformationStore.supportsPli
                if (forward) numPlisForwarded++
                if (!canSend) numPlisDropped++
            }
            is RtcpFbFirPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSenderSsrc
                canSend = canSendKeyframeRequest(sourceSsrc, now)
                // When both are supported, we favor generating a PLI rather than forwarding a FIR
                forward = canSend && streamInformationStore.supportsFir && !streamInformationStore.supportsPli
                if (forward) {
                    // When we forward a FIR we need to update the seq num.
                    pliOrFirPacket.seqNum = firCommandSequenceNumber.incrementAndGet()
                    // We manage the seq num space, so we should use the same SSRC
                    localSsrc?.let { pliOrFirPacket.mediaSenderSsrc = it }
                    numFirsForwarded++
                }
                if (!canSend) numFirsDropped++
            }
            // This is not possible, but the compiler doesn't know it.
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
    private fun canSendKeyframeRequest(mediaSsrc: Long, now: Instant): Boolean {
        if (!streamInformationStore.supportsPli && !streamInformationStore.supportsFir) {
            return false
        }
        synchronized(keyframeRequestsSyncRoot) {
            return if (Duration.between(keyframeRequests.getOrDefault(mediaSsrc, NEVER), now) < waitInterval) {
                logger.cdebug { "Sent a keyframe request less than $waitInterval ago for $mediaSsrc, " +
                    "ignoring request" }
                false
            } else {
                keyframeRequests[mediaSsrc] = now
                logger.cdebug { "Keyframe requester requesting keyframe for $mediaSsrc" }
                true
            }
        }
    }

    fun requestKeyframe(mediaSsrc: Long? = null) {
        val ssrc = mediaSsrc ?: streamInformationStore.primaryMediaSsrcs.firstOrNull() ?: run {
            numApiRequestsDropped++
            logger.cdebug { "No video SSRC found to request keyframe" }
            return
        }
        numApiRequests++
        if (!canSendKeyframeRequest(ssrc, clock.instant())) {
            numApiRequestsDropped++
            return
        }

        doRequestKeyframe(ssrc)
    }

    private fun doRequestKeyframe(mediaSsrc: Long) {
        val pkt = when {
            streamInformationStore.supportsPli -> {
                numPlisGenerated++
                RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            }
            streamInformationStore.supportsFir -> {
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
            addString("wait_interval_ms", waitInterval.toMillis().toString())
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
        waitInterval = Duration.ofMillis(min(DEFAULT_WAIT_INTERVAL.toMillis(), newRtt.toLong() + 10))
    }

    companion object {
        private val DEFAULT_WAIT_INTERVAL = Duration.ofMillis(100)
    }
}

private fun PacketInfo.getPliOrFirPacket(): RtcpFbPacket? {
    return when (val pkt = packet) {
        // We intentionally ignore compound RTCP packets in order to avoid unnecessary parsing. We can do this because:
        // 1. Compound packets coming from remote endpoint are terminated in RtcpTermination
        // 2. Whenever a PLI or FIR is generated in our code, it is not part of a compound packet.
        is RtcpFbFirPacket -> pkt
        is RtcpFbPliPacket -> pkt
        else -> null
    }
}
