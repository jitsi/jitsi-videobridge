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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.srtp.AbstractSrtpTransformer
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.srtp.SrtpErrorStatus

abstract class SrtpTransformerNode(name: String) : MultipleOutputTransformerNode(name) {
    /**
     * The function to use to use protect or unprotect a single SRT(C)P packet.
     */
    var transformer: AbstractSrtpTransformer<*>? = null

    /**
     * We'll cache all packets that come through before [transformer]
     * gets set so that we don't lose any packets at the beginning
     * (likely a keyframe)
     */
    private var cachedPackets = mutableListOf<PacketInfo>()

    /**
     * Transforms a list of packets using [#transformer]
     *
     * We pass it as an arg (rather than referencing it from the object) so Kotlin
     * knows it's non-null here.
     */
    private fun transformList(packetInfos: List<PacketInfo>, transformer: AbstractSrtpTransformer<*>): List<PacketInfo> {
        val transformedPackets = mutableListOf<PacketInfo>()
        packetInfos.forEach { packetInfo ->
            val err = transformer.transform(packetInfo)
            if (err == SrtpErrorStatus.OK) {
                transformedPackets.add(packetInfo)
            } else {
                packetDiscarded(packetInfo)
            }
            countErrorStatus(err)
        }
        return transformedPackets
    }

    private var firstPacketReceivedTimestamp = -1L
    private var firstPacketForwardedTimestamp = -1L
    /**
     * How many packets, total, we put into the cache while waiting for the transformer
     * (this includes packets which may have been dropped due to the cache filling up)
     */
    private var numCachedPackets = 0

    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        if (firstPacketReceivedTimestamp == -1L) {
            firstPacketReceivedTimestamp = System.currentTimeMillis()
        }
        transformer?.let { transformer ->
            if (firstPacketForwardedTimestamp == -1L) {
                firstPacketForwardedTimestamp = System.currentTimeMillis()
            }
            val outPackets: List<PacketInfo>
            synchronized(cachedPackets) {
                if (cachedPackets.isNotEmpty()) {
                    cachedPackets.add(packetInfo)
                    outPackets = transformList(cachedPackets, transformer)
                    cachedPackets.clear()
                } else {
                    val err = transformer.transform(packetInfo)
                    outPackets = if (err == SrtpErrorStatus.OK)
                        listOf(packetInfo) else {
                        packetDiscarded(packetInfo)
                        countErrorStatus(err)
                        emptyList()
                    }
                }
            }
            return outPackets
        } ?: run {
            numCachedPackets++
            synchronized(cachedPackets) {
                cachedPackets.add(packetInfo)
                while (cachedPackets.size > 1024) {
                    packetDiscarded(cachedPackets.removeAt(0))
                }
            }
            return emptyList()
        }
    }

    private var numSrtpProcessed = 0
    private var numSrtpFail = 0
    private var numSrtpAuthFail = 0
    private var numSrtpReplayFail = 0
    private var numSrtpReplayOld = 0
    private var numSrtpInvalidPacket = 0

    private fun countErrorStatus(err: SrtpErrorStatus) {
        when (err) {
            SrtpErrorStatus.OK -> numSrtpProcessed++
            SrtpErrorStatus.FAIL -> numSrtpFail++
            SrtpErrorStatus.AUTH_FAIL -> numSrtpAuthFail++
            SrtpErrorStatus.REPLAY_FAIL -> numSrtpReplayFail++
            SrtpErrorStatus.REPLAY_OLD -> numSrtpReplayOld++
            SrtpErrorStatus.INVALID_PACKET -> numSrtpInvalidPacket++
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_cached_packets", numCachedPackets)
            if (firstPacketReceivedTimestamp != -1L && firstPacketForwardedTimestamp != -1L) {
                val timeBetweenReceivedAndForwarded = firstPacketForwardedTimestamp - firstPacketReceivedTimestamp
                addNumber("time_initial_hold_ms", timeBetweenReceivedAndForwarded)
            } else {
                addString("state", "hold_for_transformer")
            }
            addNumber("num_srtp_processed", numSrtpProcessed)
            addNumber("num_srtp_fail", numSrtpFail)
            addNumber("num_srtp_auth_fail", numSrtpAuthFail)
            addNumber("num_srtp_replay_fail", numSrtpReplayFail)
            addNumber("num_srtp_replay_old", numSrtpReplayOld)
            addNumber("num_srtp_invalid_packet", numSrtpInvalidPacket)
        }
    }

    override fun stop() {
        super.stop()
        synchronized(cachedPackets) {
            cachedPackets.forEach { packetDiscarded(it) }
            cachedPackets.clear()
        }
        transformer?.close()
    }
}

class SrtcpDecryptNode : SrtpTransformerNode("SRTCP Decrypt Node")
class SrtcpEncryptNode : SrtpTransformerNode("SRTCP Encrypt Node")
class SrtpDecryptNode : SrtpTransformerNode("SRTP Decrypt Node")
class SrtpEncryptNode : SrtpTransformerNode("SRTP Encrypt Node")
