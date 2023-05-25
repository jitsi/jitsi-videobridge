/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.codec.av1

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.nlj.util.TreeCache
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Av1DDPacket] fields are not able to be determined by looking at a single packet with an AV1 DD
 * (for example the template dependency structure is only carried in keyframes).  This class updates the layer
 * descriptions with information from frames, and also diagnoses packet format variants that the Jitsi videobridge
 * won't be able to route.
 */
class Av1DDParser(
    sources: Array<MediaSourceDesc>,
    parentLogger: Logger
) : VideoCodecParser(sources) {
    private val logger = createChildLogger(parentLogger)

    /** History of AV1 templates and decode targets. */
    private val ddStateHistory = LRUCache<Long, TemplateHistory>(STATE_HISTORY_SIZE, true)

    fun createFrom(packet: RtpPacket, av1DdExtId: Int): Av1DDPacket {
        val history = ddStateHistory.getOrPut(packet.ssrc) {
            TemplateHistory(TEMPLATE_HISTORY_SIZE)
        }

        val priorTemplate = history.get(packet.sequenceNumber)

        return Av1DDPacket(packet, av1DdExtId, priorTemplate?.structure, logger).also {
            val descriptor = it.descriptor
            val decodeTargets = descriptor?.activeDecodeTargetsBitmask
            // a new template structure implies a non-null decodeTargets, so only have to check the latter
            if (decodeTargets != null) {
                val changed = descriptor.structure.templateIdOffset != priorTemplate?.structure?.templateIdOffset ||
                    descriptor.activeDecodeTargetsBitmask != priorTemplate.activeDecodeTargets
                history.insert(
                    packet.sequenceNumber,
                    Av1DdInfo(descriptor.structure, decodeTargets, changed)
                )
            }
        }
    }

    override fun parse(packetInfo: PacketInfo) {
        val av1Packet = packetInfo.packetAs<Av1DDPacket>()
        val history = ddStateHistory[av1Packet.ssrc]

        checkNotNull(history) {
            "History for ${av1Packet.ssrc} disappeared between createFrom and parse!"
        }

        val template = history.get(av1Packet.sequenceNumber)

        if (template?.changed == true) {
            packetInfo.layeringChanged = true

            findSourceDescAndRtpEncodingDesc(av1Packet)?.let { (src, enc) ->
                av1Packet.getScalabilityStructure(eid = enc.eid)?.let {
                    src.setEncodingLayers(it.layers, av1Packet.ssrc)
                }
                for (otherEnc in src.rtpEncodings) {
                    if (!ddStateHistory.keys.contains(otherEnc.primarySSRC)) {
                        src.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
                    }
                }
            }
        }
    }

    companion object {
        const val STATE_HISTORY_SIZE = 500
        const val TEMPLATE_HISTORY_SIZE = 500
    }
}

class TemplateHistory(minHistory: Int) {
    private val indexTracker = Rfc3711IndexTracker()
    private val history = TreeCache<Av1DdInfo>(minHistory)

    fun get(seqNo: Int): Av1DdInfo? {
        val index = indexTracker.update(seqNo)
        return history.getValueBefore(index)
    }

    fun insert(seqNo: Int, value: Av1DdInfo) {
        val index = indexTracker.update(seqNo)
        return history.insert(index, value)
    }
}

data class Av1DdInfo(
    val structure: Av1TemplateDependencyStructure,
    val activeDecodeTargets: Int,
    val changed: Boolean
)
