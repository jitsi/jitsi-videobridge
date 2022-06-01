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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType.ABS_SEND_TIME
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension

class AbsSendTime(
    val streamInformationStore: ReadOnlyStreamInformationStore
) : ModifierNode("Absolute send time") {
    private var extensionId: Int? = null

    init {
        streamInformationStore.onRtpExtensionMapping(ABS_SEND_TIME) {
            extensionId = it
        }
    }

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        if (streamInformationStore.supportsTcc) return packetInfo

        extensionId?.let { absSendTimeExtId ->
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            val ext = rtpPacket.getHeaderExtension(absSendTimeExtId)
                ?: rtpPacket.addHeaderExtension(absSendTimeExtId, AbsSendTimeHeaderExtension.DATA_SIZE_BYTES)
            AbsSendTimeHeaderExtension.setTime(ext, System.nanoTime())
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addString("abs_send_time_ext_id", extensionId.toString())
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
