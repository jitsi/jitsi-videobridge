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
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket

/**
 * Strip all hop-by-hop header extensions. By default, this leaves ssrc-audio-level and video-orientation,
 * plus the AV1 dependency descriptor if the packet is an Av1DDPacket.
 */
class HeaderExtStripper(
    streamInformationStore: ReadOnlyStreamInformationStore,
) : ModifierNode("Strip header extensions") {
    private var retainedExts: Set<Int> = emptySet()
    private var retainedExtsWithAv1DD: Set<Int> = emptySet()
    private var retainedExtTypes = defaultRetainedExtTypes

    init {
        retainedExtTypes.forEach { rtpExtensionType ->
            streamInformationStore.onRtpExtensionMapping(rtpExtensionType) {
                it?.let {
                    retainedExts += it
                    retainedExtsWithAv1DD += it
                }
            }
        }
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.AV1_DEPENDENCY_DESCRIPTOR) {
            it?.let { retainedExtsWithAv1DD += it }
        }
    }

    fun addRtpExtensionToRetain(extensionType: RtpExtensionType) {
        retainedExtTypes += extensionType
    }

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()

        val retained = if (rtpPacket is Av1DDPacket) retainedExtsWithAv1DD else retainedExts

        // TODO: we should also retain any extensions that were not signaled.
        rtpPacket.removeHeaderExtensionsExcept(retained)

        return packetInfo
    }

    override fun trace(f: () -> Unit) = f.invoke()

    companion object {
        val defaultRetainedExtTypes: Set<RtpExtensionType> = setOf(
            RtpExtensionType.SSRC_AUDIO_LEVEL,
            RtpExtensionType.VIDEO_ORIENTATION
        )
    }
}
