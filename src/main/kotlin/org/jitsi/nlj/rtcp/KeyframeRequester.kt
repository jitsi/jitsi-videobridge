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
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.format.VideoPayloadType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
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
class KeyframeRequester : TransformerNode("Keyframe Requester") {

    companion object {
        private const val DEFAULT_WAIT_INTERVAL_MS = 100
    }

    var waitIntervalMs = DEFAULT_WAIT_INTERVAL_MS

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet
        val pliOrFir = when {
            packet is CompoundRtcpPacket -> {
                packet.packets.first { it is RtcpFbPliPacket || it is RtcpFbFirPacket }
            }
            packet is RtcpFbFirPacket -> packet
            packet is RtcpFbPliPacket -> packet
            else -> null
        } ?: return null

        var forward = true
        when {
            pliOrFir is RtcpFbPliPacket -> {
                when {
                    hasPliSupport -> {
                        // Forward PLI
                        forward = canSendKeyframeRequest(pliOrFir.mediaSenderSsrc, System.currentTimeMillis())
                    }
                    hasFirSupport -> {
                        // Translate to FIR
                        requestKeyframe(pliOrFir.mediaSenderSsrc)
                        forward = false
                    }
                    else -> {
                        // Can't do anything
                        forward = false
                    }
                }
            }
            pliOrFir is RtcpFbFirPacket -> {
                when {
                    hasPliSupport -> {
                        // Translate to PLI
                        requestKeyframe(pliOrFir.mediaSenderSsrc)
                        forward = false
                    }
                    hasFirSupport -> {
                        // Forward FIR
                        forward = canSendKeyframeRequest(pliOrFir.mediaSenderSsrc, System.currentTimeMillis())
                        if (forward) {
                            pliOrFir.seqNum = firCommandSequenceNumber.incrementAndGet()
                        }
                    }
                    else -> {
                        // Can't do anything
                        forward = false
                    }
                }
            }
        }

        return if (forward) packetInfo else null
    }

    // Map a SSRC to the timestamp (in ms) of when we last requested a keyframe for it
    private val keyframeRequests = mutableMapOf<Long, Long>()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private val keyframeRequestsSyncRoot = Any()

    // Stats
    private var numKeyframesRequestedByBridge: Int = 0
    private var numKeyframeRequestsDropped: Int = 0

    private var hasPliSupport: Boolean = false
    private var hasFirSupport: Boolean = true

    private fun canSendKeyframeRequest(mediaSsrc: Long, nowMs: Long): Boolean {
        synchronized (keyframeRequestsSyncRoot) {
            return if (nowMs - keyframeRequests.getOrDefault(mediaSsrc, 0) < waitIntervalMs) {
                logger.cdebug { "Sent a keyframe request less than ${waitIntervalMs}ms ago for $mediaSsrc, " +
                        "ignoring request" }
                numKeyframeRequestsDropped++
                false
            } else {
                keyframeRequests[mediaSsrc] = nowMs
                logger.cdebug { "Keyframe requester requesting keyframe with FIR for $mediaSsrc" }
                numKeyframesRequestedByBridge++
                true
            }
        }
    }

    fun requestKeyframe(mediaSsrc: Long) {
        if (!canSendKeyframeRequest(mediaSsrc, System.currentTimeMillis())) {
            return
        }

        val pkt = if (hasPliSupport) RtcpFbPliPacketBuilder(
                mediaSenderSsrc = mediaSsrc
        ).build() else RtcpFbFirPacketBuilder(
                mediaSenderSsrc = mediaSsrc,
                firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
        ).build()

        next(PacketInfo(pkt))
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                when (event.payloadType) {
                    is VideoPayloadType -> {
                        // Support for FIR and PLI is declared per-payload type, but currently
                        // our code which requests FIR and PLI is not payload-type aware. So
                        // until this changes we will just check if any of the PTs supports
                        // FIR and PLI.
                        hasPliSupport = event.payloadType.rtcpFeedbackSet.contains("nack pli")
                        hasFirSupport = event.payloadType.rtcpFeedbackSet.contains("ccm fir")
                    }
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num keyframes requested by the bridge: $numKeyframesRequestedByBridge")
            addStat("num keyframes dropped due to throttling: $numKeyframeRequestsDropped")
        }
    }

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitIntervalMs = min(DEFAULT_WAIT_INTERVAL_MS, newRtt.toInt() + 10)
    }
}