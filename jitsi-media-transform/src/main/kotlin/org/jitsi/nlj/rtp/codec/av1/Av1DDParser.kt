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
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.nlj.util.TreeCache
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Av1DDPacket] fields are not able to be determined by looking at a single packet with an AV1 DD
 * (for example the template dependency structure is only carried in keyframes).  This class updates the layer
 * descriptions with information from frames, and also diagnoses packet format variants that the Jitsi videobridge
 * won't be able to route.
 */
class Av1DDParser(
    source: MediaSourceDesc,
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) : VideoCodecParser(source) {
    private val logger = createChildLogger(parentLogger)

    /** History of AV1 templates. */
    private val ddStateHistory = LRUCache<Long, TemplateHistory>(STATE_HISTORY_SIZE, true)

    fun createFrom(packet: RtpPacket, av1DdExtId: Int): Av1DDPacket {
        val history = ddStateHistory.getOrPut(packet.ssrc) {
            TemplateHistory(TEMPLATE_HISTORY_SIZE)
        }

        val priorEntry = history.get(packet.sequenceNumber)

        val priorStructure = priorEntry?.value?.structure?.clone()

        val av1Packet = Av1DDPacket(packet, av1DdExtId, priorStructure, logger)

        val newStructure = av1Packet.descriptor?.newTemplateDependencyStructure
        if (newStructure != null) {
            val structureChanged = newStructure.templateIdOffset != priorStructure?.templateIdOffset
            history.insert(packet.sequenceNumber, Av1DdInfo(newStructure.clone(), structureChanged))
            logger.debug {
                "Inserting new structure with templates ${newStructure.templateIdOffset} .. " +
                    "${(newStructure.templateIdOffset + newStructure.templateCount - 1) % 64} " +
                    "for RTP packet ssrc ${packet.ssrc} seq ${packet.sequenceNumber}.  " +
                    "Changed from previous: $structureChanged."
            }
        }

        if (timeSeriesLogger.isTraceEnabled) {
            val point = diagnosticContext
                .makeTimeSeriesPoint("av1_parser")
                .addField("rtp.ssrc", packet.ssrc)
                .addField("rtp.seq", packet.sequenceNumber)
                .addField("rtp.timestamp", packet.timestamp)
                .addField("av1_parser.key", priorEntry?.key)
                .addField("av1.startOfFrame", av1Packet.statelessDescriptor.startOfFrame)
                .addField("av1.endOfFrame", av1Packet.statelessDescriptor.endOfFrame)
                .addField("av1.templateId", av1Packet.statelessDescriptor.frameDependencyTemplateId)
                .addField("av1.frameNum", av1Packet.statelessDescriptor.frameNumber)
                .addField("av1.frameInfo", av1Packet.frameInfo?.toString())
                .addField("av1.structure", newStructure != null)
                .addField("av1.activeTargets", av1Packet.descriptor?.activeDecodeTargetsBitmask)
            val packetStructure = av1Packet.descriptor?.structure
            if (packetStructure != null) {
                point.addField("av1.structureIdOffset", packetStructure.templateIdOffset)
                    .addField("av1.templateCount", packetStructure.templateCount)
                    .addField("av1.structureId", System.identityHashCode(packetStructure))
            }
            if (newStructure != null) {
                point.addField("av1.newStructureIdOffset", newStructure.templateIdOffset)
                    .addField("av1.newTemplateCount", newStructure.templateCount)
                    .addField("av1.newStructureId", System.identityHashCode(newStructure))
            }
            timeSeriesLogger.trace(point)
        }

        return av1Packet
    }

    override fun parse(packetInfo: PacketInfo) {
        val av1Packet = packetInfo.packetAs<Av1DDPacket>()
        val history = ddStateHistory[av1Packet.ssrc]

        if (history == null) {
            /** Probably getting spammed with SSRCs? */
            logger.warn("History for ${av1Packet.ssrc} disappeared between createFrom and parse!")
            return
        }

        val activeDecodeTargets = av1Packet.activeDecodeTargets

        if (activeDecodeTargets != null) {
            val changed = history.updateDecodeTargets(av1Packet.sequenceNumber, activeDecodeTargets)

            if (changed) {
                packetInfo.layeringChanged = true
                logger.debug {
                    "Decode targets for ${av1Packet.ssrc} changed in seq ${av1Packet.sequenceNumber}: " +
                        "now 0x${Integer.toHexString(activeDecodeTargets)}.  Updating layering."
                }

                findRtpEncodingDesc(av1Packet)?.let { enc ->
                    av1Packet.getScalabilityStructure(eid = enc.eid)?.let {
                        source.setEncodingLayers(it.layers, av1Packet.ssrc)
                    }
                    for (otherEnc in source.rtpEncodings) {
                        if (!ddStateHistory.keys.contains(otherEnc.primarySSRC)) {
                            source.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val STATE_HISTORY_SIZE = 500
        const val TEMPLATE_HISTORY_SIZE = 500

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(Av1DDParser::class.java)
    }
}

class TemplateHistory(minHistory: Int) {
    private val indexTracker = RtpSequenceIndexTracker()
    private val history = TreeCache<Av1DdInfo>(minHistory)
    private var latestDecodeTargets = -1
    private var latestDecodeTargetIndex = -1L

    fun get(seqNo: Int): Map.Entry<Long, Av1DdInfo>? {
        val index = indexTracker.update(seqNo)
        return history.getEntryBefore(index)
    }

    fun insert(seqNo: Int, value: Av1DdInfo) {
        val index = indexTracker.update(seqNo)
        return history.insert(index, value)
    }

    /** Update the current decode targets.
     *  Return true if the decode target set or the template structure has changed. */
    fun updateDecodeTargets(seqNo: Int, decodeTargets: Int): Boolean {
        val index = indexTracker.update(seqNo)
        if (index < latestDecodeTargetIndex) {
            return false
        }
        val changed = decodeTargets != latestDecodeTargets || history.get(index)?.changed == true
        latestDecodeTargetIndex = index
        latestDecodeTargets = decodeTargets
        return changed
    }
}

data class Av1DdInfo(
    val structure: Av1TemplateDependencyStructure,
    val changed: Boolean
)
