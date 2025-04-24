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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.AudioLevelListener
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RtpExtensionType.SSRC_AUDIO_LEVEL
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 */
class AudioLevelReader(
    streamInformationStore: ReadOnlyStreamInformationStore
) {
    /**
     *  Process packets without cryptex pre-SRTP to allow the "skip decryption" optimization if they are to be dropped.
     */
    val preDecryptNode = AudioLevelReaderNode("AudioLevelReader_pre_srtp") { !it.originalHadCryptex }
    val postDecryptNode = AudioLevelReaderNode("AudioLevelReader_post_srtp") { it.originalHadCryptex }

    private var audioLevelExtId: Int? = null
    var audioLevelListener: AudioLevelListener? = null
    var forwardedSilencePackets: Int = 0
    private val stats = Stats()

    /**
     * Whether we should forcibly mute this audio stream (by setting shouldDiscard to true).
     */
    var forceMute: Boolean = false

    init {
        streamInformationStore.onRtpExtensionMapping(SSRC_AUDIO_LEVEL) {
            audioLevelExtId = it
        }
    }

    inner class AudioLevelReaderNode(
        name: String,
        val shouldProcess: (PacketInfo) -> Boolean
    ) : ObserverNode(name) {

        override fun observe(packetInfo: PacketInfo) {
            if (!shouldProcess(packetInfo)) return

            val audioRtpPacket = packetInfo.packet as? AudioRtpPacket ?: return

            audioLevelExtId?.let { audioLevelId ->
                audioRtpPacket.getHeaderExtension(audioLevelId)?.let { ext ->
                    stats.audioLevel()

                    val level = AudioLevelHeaderExtension.getAudioLevel(ext)
                    val silence = level == MUTED_LEVEL

                    if (!silence) stats.nonSilence(AudioLevelHeaderExtension.getVad(ext))
                    if (silence && discardSilence && forwardedSilencePackets > forwardedSilencePacketsLimit) {
                        packetInfo.shouldDiscard = true
                        stats.discardedSilence()
                    } else if (this@AudioLevelReader.forceMute) {
                        packetInfo.shouldDiscard = true
                        stats.discardedForceMute()
                    } else {
                        forwardedSilencePackets = if (silence) forwardedSilencePackets + 1 else 0
                        audioLevelListener?.let { listener ->
                            if (listener.onLevelReceived(audioRtpPacket.ssrc, (127 - level).toPositiveLong())) {
                                packetInfo.shouldDiscard = true
                                stats.discardedRanking()
                            }
                        }
                    }
                }
            }
        }

        override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
            addString("audio_level_ext_id", audioLevelExtId.toString())
            addNumber("num_audio_levels", stats.numAudioLevels)
            addNumber("num_silence_packets_discarded", stats.numDiscardedSilence)
            addNumber("num_force_mute_discarded", stats.numDiscardedForceMute)
            addNumber("num_ranking_discarded", stats.numDiscardedRanking)
            addNumber("num_non_silence", stats.numNonSilence)
            addNumber("num_non_silence_with_vad", stats.numNonSilenceWithVad)
            addBoolean("force_mute", forceMute)
        }

        override fun statsJson() = super.statsJson().apply {
            this["num_audio_levels"] = stats.numAudioLevels
            this["num_silence_packets_discarded"] = stats.numDiscardedSilence
            this["num_force_mute_discarded"] = stats.numDiscardedForceMute
            this["num_ranking_discarded"] = stats.numDiscardedRanking
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    companion object {
        const val MUTED_LEVEL = 127
        private val forwardedSilencePacketsLimit: Int by config {
            "jmt.audio.level.forwarded-silence-packets-limit".from(JitsiConfig.newConfig)
        }
        private val discardSilence: Boolean by config {
            "jmt.audio.level.discard-silence".from(JitsiConfig.newConfig)
        }
    }
}

private class Stats(
    var numAudioLevels: Long = 0,
    var numDiscardedSilence: Long = 0,
    var numDiscardedForceMute: Long = 0,
    var numDiscardedRanking: Long = 0,
    var numNonSilence: Long = 0,
    var numNonSilenceWithVad: Long = 0
) {
    /** A packet contained an audio level header */
    fun audioLevel() = numAudioLevels++

    /** A packet was discarded because it was silence. */
    fun discardedSilence() = numDiscardedSilence++

    /** A packet was discarded because it was force-muted. */
    fun discardedForceMute() = numDiscardedForceMute++

    /** A packet was discarded due to insufficient energy ranking or active speaker status. */
    fun discardedRanking() = numDiscardedRanking++

    /** A non-silence packet was received (with or without the Voice Activity Detection flag). */
    fun nonSilence(hasVad: Boolean) {
        numNonSilence++
        if (hasVad) numNonSilenceWithVad++
    }
}
