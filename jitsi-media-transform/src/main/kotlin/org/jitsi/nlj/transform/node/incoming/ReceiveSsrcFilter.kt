/*
 * Copyright @ 2026 - Present, 8x8 Inc
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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Drops RTP packets whose SSRC the sending endpoint did not signal (i.e. which is not among the receive SSRCs in
 * the [ReadOnlyStreamInformationStore]).
 *
 * An endpoint can only send media for the sources that were signaled for it. Without this check, an audio packet
 * is attributed to a source by the SSRC in its header alone, so an endpoint could send audio that the bridge
 * attributes to another endpoint's source (SRTP authenticates the packet with the keys of the sending endpoint, but
 * does not bind the SSRC to them). The video path already dropped a packet that matched no encoding of the
 * endpoint, in [VideoQualityLayerLookup].
 *
 * A packet for an SSRC that is not signaled yet (media arriving before the signaling that describes it) is also
 * dropped. Before this node, such an audio packet reached the conference. The local receivers and the exporters
 * refused it, because they require a known source, but it was still sent to the relays (which dropped it on
 * ingress).
 */
class ReceiveSsrcFilter(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("Receive SSRC filter") {
    private val logger = createChildLogger(parentLogger)
    private var numPacketsDropped = 0

    /**
     * The SSRCs for which a drop was logged, so that each is logged once rather than for every packet. Bounded, so
     * the worst case of eviction is logging again for an SSRC seen long ago.
     */
    private val loggedSsrcs: MutableSet<Long> = LRUCache.lruSet(MAX_LOGGED_SSRCS, true)

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val ssrc = packetInfo.packetAs<RtpPacket>().ssrc
        if (streamInformationStore.receiveSsrcs.contains(ssrc)) {
            return packetInfo
        }

        numPacketsDropped++
        if (loggedSsrcs.add(ssrc)) {
            // Debug level: media that arrives before its signaling (e.g. at join or on unmute) takes this path too.
            logger.debug {
                "Dropping packets with SSRC $ssrc, which is not a signaled receive SSRC. " +
                    "Further packets with this SSRC are not logged."
            }
        }
        return null
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_packets_dropped_unsignaled_ssrc", numPacketsDropped)
    }

    override fun statsJson() = super.statsJson().apply {
        put("num_packets_dropped_unsignaled_ssrc", numPacketsDropped)
    }

    companion object {
        private const val MAX_LOGGED_SSRCS = 100

        /** A kill switch for the filter. See reference.conf. */
        val enabled: Boolean by config("jmt.transceiver.recv.drop-unsignaled-ssrcs".from(JitsiConfig.newConfig))
    }
}
