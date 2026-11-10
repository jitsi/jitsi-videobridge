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
import org.jitsi.utils.RateLimit
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration

/**
 * Drops RTP packets whose SSRC was never added as a receive SSRC of this endpoint (see
 * [ReadOnlyStreamInformationStore.receiveSsrcs]).
 *
 * SRTP authenticates a packet with the keys of the endpoint that sent it, but does not bind the SSRC to them. Without
 * this check, an endpoint can send a packet with the SSRC of a source of another endpoint, and the layers above
 * attribute the packet to that source. A packet that arrives before the signaling of its source is also dropped.
 */
class ReceiveSsrcFilter(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    clock: Clock = Clock.systemUTC()
) : TransformerNode("Receive SSRC filter") {
    private val logger = createChildLogger(parentLogger)

    /** The number of packets dropped because their SSRC is not a receive SSRC. */
    var numPacketsDropped = 0
        private set

    /**
     * The SSRCs for which a drop was logged at warning level, so that each is logged at that level once rather than
     * for every packet. Bounded, so the worst case of eviction is logging again for an SSRC seen long ago.
     */
    private val loggedSsrcs: MutableSet<Long> = LRUCache.lruSet(MAX_LOGGED_SSRCS, true)

    /**
     * Limits the warnings of this filter, so that an endpoint which sends packets with many different SSRCs can not
     * flood the log. The set above alone only limits the memory.
     */
    private val warnRateLimit = RateLimit(
        defaultMinInterval = Duration.ZERO,
        maxRequests = MAX_WARNINGS_PER_INTERVAL,
        interval = WARNING_INTERVAL,
        clock = clock
    )

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val ssrc = packetInfo.packetAs<RtpPacket>().ssrc
        if (streamInformationStore.receiveSsrcs.contains(ssrc)) {
            return packetInfo
        }

        numPacketsDropped++
        if (loggedSsrcs.add(ssrc) && warnRateLimit.accept()) {
            logger.warn(
                "Dropping packet with SSRC $ssrc, which is not a receive SSRC " +
                    "(receive SSRCs: ${streamInformationStore.receiveSsrcs}). Further occurrences are logged at " +
                    "debug level."
            )
        } else {
            logger.debug { "Dropping packet with SSRC $ssrc, which is not a receive SSRC." }
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

        /** At most [MAX_WARNINGS_PER_INTERVAL] warnings in [WARNING_INTERVAL], for each filter. */
        private const val MAX_WARNINGS_PER_INTERVAL = 3
        private val WARNING_INTERVAL: Duration = Duration.ofMinutes(1)

        /** A kill switch for the filter. See reference.conf. */
        val enabled: Boolean by config("jmt.transceiver.recv.drop-unsignaled-ssrcs".from(JitsiConfig.newConfig))
    }
}
