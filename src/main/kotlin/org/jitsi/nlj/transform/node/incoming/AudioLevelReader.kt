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
) : ObserverNode("Audio level reader") {
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

    override fun observe(packetInfo: PacketInfo) {
        val audioRtpPacket = packetInfo.packet as? AudioRtpPacket ?: return

        audioLevelExtId?.let { audioLevelId ->
            audioRtpPacket.getHeaderExtension(audioLevelId)?.let { ext ->
                val level = AudioLevelHeaderExtension.getAudioLevel(ext)
                val silence = level == MUTED_LEVEL

                if (!silence) stats.nonSilence(AudioLevelHeaderExtension.getVad(ext))
                if ((silence && forwardedSilencePackets > forwardedSilencePacketsLimit) || this.forceMute) {
                    packetInfo.shouldDiscard = true
                    stats.discarded(silence)
                } else {
                    forwardedSilencePackets = if (silence) forwardedSilencePackets + 1 else 0
                    audioLevelListener?.onLevelReceived(audioRtpPacket.ssrc, (127 - level).toPositiveLong())
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addString("audio_level_ext_id", audioLevelExtId.toString())
        addNumber("num_silence_packets_discarded", stats.numDiscardedSilence)
        addNumber("num_force_mute_discarded", stats.numDiscardedForceMute)
        addNumber("num_non_silence", stats.numNonSilence)
        addNumber("num_non_silence_with_vad", stats.numNonSilenceWithVad)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    companion object {
        const val MUTED_LEVEL = 127
        private val forwardedSilencePacketsLimit: Int by config {
            "jmt.audio.level.forwarded-silence-packets-limit".from(JitsiConfig.newConfig)
        }
    }
}

private class Stats(
    var numDiscardedSilence: Long = 0,
    var numDiscardedForceMute: Long = 0,
    var numNonSilence: Long = 0,
    var numNonSilenceWithVad: Long = 0
) {
    /** A packet was discarded. If [silence] is false we assume it was because of "force mute". */
    fun discarded(silence: Boolean) = if (silence) numDiscardedSilence++ else numDiscardedForceMute++

    /** A non-silence packet was received (with or without the Voice Activity Detection flag). */
    fun nonSilence(hasVad: Boolean) {
        numNonSilence++
        if (hasVad) numNonSilenceWithVad++
    }
}
