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
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket

/**
 * [KeyframeRequester] handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the [KeyframeRequester#requestKeyframe]
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
class KeyframeRequester : Node("Keyframe Requester") {
    private var numKeyframesRequestedByBridge: Int = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        //TODO: translation
        //TODO: aggregation
        next(p)
    }

    fun requestKeyframe(mediaSsrc: Long) {
        //TODO(brian): for now hardcode to send an FIR
        val firPacket = RtcpFbFirPacket(mediaSourceSsrc = mediaSsrc)
        numKeyframesRequestedByBridge++
        processPackets(listOf(PacketInfo(firPacket)))
    }

    override fun handleEvent(event: Event) {
        //TODO: rtcpfb events so we can tell what is supported (pli, fir)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num keyframes requested by the bridge: $numKeyframesRequestedByBridge")
        }
    }
}