/*
 * Copyright @ 2019 - present 8x8 Inc
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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.AudioRedPayloadType
import org.jitsi.nlj.rtp.RedAudioRtpPacket
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpPacketCache
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta

class AudioRedHandler(
    streamInformationStore: ReadOnlyStreamInformationStore
) : MultipleOutputTransformerNode("RedHandler") {

    private val stats = Stats()
    val config = Config()

    var audioLevelExtId: Int? = null
    var redPayloadType: Int? = null

    private val ssrcRedHandlers: MutableMap<Long, SsrcRedHandler> = HashMap()

    init {
        streamInformationStore.onRtpPayloadTypesChanged { payloadTypes ->
            redPayloadType = payloadTypes.values.find { it is AudioRedPayloadType }?.pt?.toInt()
        }
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.SSRC_AUDIO_LEVEL) {
            audioLevelExtId = it
        }
    }

    @ExperimentalStdlibApi
    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        val audioPacket = packetInfo.packet as? AudioRtpPacket ?: return listOf(packetInfo)

        val ssrcHandler = ssrcRedHandlers.computeIfAbsent(audioPacket.ssrc) { SsrcRedHandler() }

        return when (audioPacket) {
            is RedAudioRtpPacket -> ssrcHandler.transformRed(packetInfo)
            else -> listOf(ssrcHandler.transformAudio(packetInfo))
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addString("red_payload_type", redPayloadType?.toString() ?: "null")
        addString("audio_level_ext_id", audioLevelExtId?.toString() ?: "null")
        addString("policy", config.policy.toString())
        addString("distance", config.distance.toString())
        addBoolean("vad_only", config.vadOnly)

        addNumber("red_packets_decapsulated", stats.redPacketsDecapsulated)
        addNumber("red_packets_forwarded", stats.redPacketsForwarded)
        addNumber("audio_packets_encapsulated", stats.audioPacketsEncapsulated)
        addNumber("audio_packets_forwarded", stats.audioPacketsForwarded)
        addNumber("lost_packets_recovered", stats.lostPacketsRecovered)
        addNumber("redundancy_packets_added", stats.redundancyPacketsAdded)
    }

    override fun stop() = super.stop().also {
        ssrcRedHandlers.values.forEach { it.stop() }
        ssrcRedHandlers.clear()
    }

    /**
     * Handler for a specific stream (SSRC)
     */
    private inner class SsrcRedHandler {
        /**
         * Saves audio (non-RED) packets that we've sent. Packets are cloned on insert and the inserted copies are owned
         * by the cache, meaning that the cache is responsible for returning their buffers to the pool.
         */
        val sentAudioCache = RtpPacketCache(20, synchronize = false)

        /**
         * Process an incoming audio packet. It is either forwarded as it is, or encapsulated in RED with previous
         * packets optionally added as redundancy.
         */
        fun transformAudio(packetInfo: PacketInfo): PacketInfo {
            // Whether we need to add RED encapsulation
            val encapsulate = when (config.policy) {
                RedPolicy.NOOP, RedPolicy.STRIP -> false
                RedPolicy.PROTECT_ALL -> true
                // RedPolicy.PROTECT_DOMINANT -> =isDominant
            }

            val redPayloadType = redPayloadType
            if (redPayloadType == null || !encapsulate) {
                stats.audioPacketForwarded()
                return packetInfo
            }

            val audioRtpPacket = packetInfo.packetAs<AudioRtpPacket>()
            sentAudioCache.insert(audioRtpPacket)

            val redundancy = mutableListOf<RtpPacket>()
            val seq = audioRtpPacket.sequenceNumber

            when (config.distance) {
                RedDistance.ONE -> {
                    getPacketToProtect(applySequenceNumberDelta(seq, -1), config.vadOnly)?.also { secondary ->
                        redundancy.add(secondary)
                        stats.redundancyPacketAdded()
                    }
                }
                RedDistance.TWO -> {
                    getPacketToProtect(applySequenceNumberDelta(seq, -1), false)?.also { secondary ->
                        // With distance 2 we only add the tertiary packet when there is a secondary available
                        // (regardless of secondary's VAD). This guarantees that the sequence numbers of the
                        // redundancy packets always directly proceed the primary packet, i.e. that we don't encode
                        // a packet with primary seq=N and a single redundancy with seq=N-2. This is be important
                        // when the receiver of the RED stream is another jitsi-videobridge instance (via Octo),
                        // which makes that assumption about the stream it receives.
                        val tertiary = getPacketToProtect(applySequenceNumberDelta(seq, -2), config.vadOnly)
                        if (tertiary != null) {
                            redundancy.add(tertiary)
                            stats.redundancyPacketAdded()
                            redundancy.add(secondary)
                            stats.redundancyPacketAdded()
                        } else if (!config.vadOnly || secondary.hasVad()) {
                            // If there's no tertiary encode the secondary alone, but this time check its VAD.
                            redundancy.add(secondary)
                            stats.redundancyPacketAdded()
                        }
                    }
                }
            }

            val redPacket = RedAudioRtpPacket.builder.build(redPayloadType, audioRtpPacket, redundancy)
            packetInfo.packet = redPacket

            // We replaced packetInfo.packet with our newly allocated packet, so the original can now be returned.
            // We do not return the redundancy packets, because we only peek()ed at them from the cache.
            BufferPool.returnBuffer(audioRtpPacket.buffer)

            stats.audioPacketEncapsulated()
            return packetInfo
        }

        private fun getPacketToProtect(seq: Int, vadOnly: Boolean): RtpPacket? {
            // All of the transform pipeline runs in a single thread, and we only use the packet momentarily to make a
            // copy into a new RED packet, so its safe to just peek() at it.
            sentAudioCache.peek(seq)?.item?.let {
                // In vad-only mode, we only add redundancy for packets that have an audio level extension with the VAD
                // bit set.
                if (!vadOnly || it.hasVad()) {
                    return it
                }
            }
            return null
        }

        private fun RtpPacket.hasVad(): Boolean = audioLevelExtId?.let { extId ->
            getHeaderExtension(extId)?.let { AudioLevelHeaderExtension.getVad(it) } ?: false
        } ?: false

        fun stop() = sentAudioCache.flush()

        /**
         * Process an incoming RED packet. Depending on the configured policy and whether the receiver supports the
         * RED format, it is either forwarded as it is or it is "stripped" to its primary encoding, with redundancy
         * blocks being read if there are non-received packets.
         */
        @ExperimentalStdlibApi
        fun transformRed(packetInfo: PacketInfo): List<PacketInfo> {
            // Whether we need to strip the RED encapsulation
            val strip = when (redPayloadType) {
                null -> true
                else -> when (config.policy) {
                    RedPolicy.STRIP -> true
                    // RedPolicy.PROTECT_DOMINANT -> !isDominant
                    RedPolicy.NOOP, RedPolicy.PROTECT_ALL -> false
                }
            }

            return if (strip) buildList {
                val redPacket = packetInfo.packetAs<RedAudioRtpPacket>()

                val seq = redPacket.sequenceNumber
                val prev = applySequenceNumberDelta(seq, -1)
                val prev2 = applySequenceNumberDelta(seq, -2)
                val prevMissing = !sentAudioCache.contains(prev)
                val prev2Missing = !sentAudioCache.contains(prev2)

                if (prevMissing || prev2Missing) {
                    redPacket.removeRedAndGetRedundancyPackets().forEach {
                        if ((it.sequenceNumber == prev && prevMissing) ||
                            (it.sequenceNumber == prev2 && prev2Missing)
                        ) {
                            add(PacketInfo(it))
                            stats.lostPacketRecovered()
                        }
                        sentAudioCache.insert(it)
                    }
                } else {
                    redPacket.removeRed()
                }

                stats.redPacketDecapsulated()
                packetInfo.packet = redPacket.toOtherType(::AudioRtpPacket)

                // It's possible we already forwarded the primary packet if we recovered it from a previously received
                // packet.
                if (!sentAudioCache.contains(seq)) {
                    sentAudioCache.insert(packetInfo.packetAs())
                    add(packetInfo)
                }
            } else {
                stats.redPacketForwarded()
                listOf(packetInfo)
            }
        }
    }
}

enum class RedPolicy {
    /**
     * No change.
     */
    NOOP,
    /**
     * Always strip.
     */
    STRIP,
    /**
     * Add RED for all endpoints.
     */
    PROTECT_ALL,
    // TODO
    // /**
    //  * Add redundancy for the dominant (if missing), strip it for everyone else (if present).
    //  */
    // PROTECT_DOMINANT
}

enum class RedDistance {
    ONE,
    TWO
}

data class Stats(
    var redPacketsDecapsulated: Int = 0,
    var redPacketsForwarded: Int = 0,
    var audioPacketsEncapsulated: Int = 0,
    var audioPacketsForwarded: Int = 0,
    var lostPacketsRecovered: Int = 0,
    var redundancyPacketsAdded: Int = 0
) {
    fun redPacketDecapsulated() = redPacketsDecapsulated++
    fun redPacketForwarded() = redPacketsForwarded++
    fun audioPacketEncapsulated() = audioPacketsEncapsulated++
    fun audioPacketForwarded() = audioPacketsForwarded++
    fun lostPacketRecovered() = lostPacketsRecovered++
    fun redundancyPacketAdded() = redundancyPacketsAdded++
}

class Config {
    val policy: RedPolicy by config { "jmt.audio.red.policy".from(JitsiConfig.newConfig) }
    val distance: RedDistance by config { "jmt.audio.red.distance".from(JitsiConfig.newConfig) }
    val vadOnly: Boolean by config { "jmt.audio.red.vad-only".from(JitsiConfig.newConfig) }
}
