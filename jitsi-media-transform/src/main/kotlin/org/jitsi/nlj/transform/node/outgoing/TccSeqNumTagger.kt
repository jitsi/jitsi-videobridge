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
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class TccSeqNumTagger(
    transportCcEngine: TransportCcEngine,
    streamInformationStore: ReadOnlyStreamInformationStore
) : ModifierNode("TCC sequence number tagger") {
    /**
     * The next sequence number. More than one thread calls [modify]: the sender queue (media), the thread that handles
     * incoming RTCP (retransmissions in response to NACKs), and the probing threads.
     */
    private val nextTccSeqNum = AtomicLong(1)
    private var tccExtensionId: Int? = null

    /**
     * Whether RFC 8888 feedback is in use. It identifies packets by their SSRC and RTP sequence number, so no
     * sequence number is sent with them, but they are still registered with the [TransportCcEngine].
     */
    private var ccfb = false

    init {
        streamInformationStore.onRtpExtensionMapping(TRANSPORT_CC) {
            tccExtensionId = it
        }
        streamInformationStore.onRtpPayloadTypesChanged {
            ccfb = streamInformationStore.supportsCcfb
        }
    }

    private val weakTcc = WeakReference(transportCcEngine)

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val ccfb = this.ccfb
        val tccExtId = tccExtensionId
        if (tccExtId == null && !ccfb) {
            return packetInfo
        }
        when (val rtpPacket = packetInfo.packetAs<RtpPacket>()) {
            is VideoRtpPacket -> {
                val curSeq = nextTccSeqNum.getAndIncrement()

                if (tccExtId != null && !ccfb) {
                    val ext = rtpPacket.getHeaderExtension(tccExtId)
                        ?: rtpPacket.addHeaderExtension(tccExtId, TccHeaderExtension.DATA_SIZE_BYTES)

                    TccHeaderExtension.setSequenceNumber(ext, curSeq.toInt())
                }

                weakTcc.get()?.mediaPacketTagged(packetInfo, curSeq)

                packetInfo.onSent { pkt -> weakTcc.get()?.mediaPacketSent(pkt, curSeq) }
            }

            else -> Unit
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addString("tcc_ext_id", tccExtensionId.toString())
        addBoolean("ccfb", ccfb)
    }

    override fun stop() {
        super.stop()
        weakTcc.get()?.stop()
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
