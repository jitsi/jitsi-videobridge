/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
import org.jitsi.nlj.rtp.RtpExtensionType.MID
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.SdesHeaderExtension

/**
 * Stamps the sdes:mid (media identification) RTP header extension on outgoing packets, using a per-SSRC mid supplied by
 * [getMidBySsrc]. This is used under SSRC rewriting so that the receiving client can demux forwarded media by mid (which
 * disables Chrome's payload-type demuxing fallback, the trigger for the audio-demux wedge).
 *
 * This node sits in the shared portion of the outgoing pipeline (after RTX encapsulation) so that both media packets and
 * their retransmissions are stamped with the same mid. The mid is resolved from the packet's (already rewritten) SSRC,
 * which is stable per slot, so primary and RTX SSRCs of a slot resolve to the same mid.
 *
 * Does nothing unless the mid extension has been negotiated for this endpoint and [getMidBySsrc] returns a mid for the
 * packet's SSRC (i.e. the endpoint opted into mid-based demuxing and the SSRC is a rewritten slot SSRC).
 */
class MidStamper(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    private val getMidBySsrc: (Long) -> String?
) : ModifierNode("Mid stamper") {
    private var extensionId: Int? = null
    private var numStamped = 0

    init {
        streamInformationStore.onRtpExtensionMapping(MID) {
            extensionId = it
        }
    }

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val midExtId = extensionId ?: return packetInfo
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val mid = getMidBySsrc(rtpPacket.ssrc) ?: return packetInfo

        // The header extensions of outgoing packets have been stripped by this point, so a mid extension is only present
        // if we (or RTX, re-using the same packet) added it. Avoid adding it twice.
        if (rtpPacket.getHeaderExtension(midExtId) == null) {
            val ext = rtpPacket.addHeaderExtension(midExtId, mid.length)
            SdesHeaderExtension.setTextValue(ext, mid)
            numStamped++
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addString("mid_ext_id", extensionId.toString())
            addNumber("num_stamped", numStamped)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
