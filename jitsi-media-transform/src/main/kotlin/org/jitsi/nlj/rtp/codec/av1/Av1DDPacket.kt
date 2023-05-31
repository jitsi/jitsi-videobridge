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

import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorStatelessSubset
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.utils.logging2.Logger

/** A video packet carrying an AV1 Dependency Descriptor.  Note that this may or may not be an actual AV1 packet;
 * other video codecs can also carry the AV1 DD.
 */
class Av1DDPacket : ParsedVideoPacket {
    var descriptor: Av1DependencyDescriptorHeaderExtension?
    val statelessDescriptor: Av1DependencyDescriptorStatelessSubset
    val frameInfo: FrameInfo?
    val av1DDHeaderExtensionId: Int

    private constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        av1DDHeaderExtensionId: Int,
        encodingIndices: Collection<Int>,
        descriptor: Av1DependencyDescriptorHeaderExtension?,
        statelessDescriptor: Av1DependencyDescriptorStatelessSubset,
        frameInfo: FrameInfo?
    ) : super(buffer, offset, length, encodingIndices) {
        this.descriptor = descriptor
        this.statelessDescriptor = statelessDescriptor
        this.frameInfo = frameInfo
        this.av1DDHeaderExtensionId = av1DDHeaderExtensionId
    }

    constructor(
        packet: RtpPacket,
        av1DDHeaderExtensionId: Int,
        templateDependencyStructure: Av1TemplateDependencyStructure?,
        logger: Logger
    ) : super(packet.buffer, packet.offset, packet.length, emptyList()) {
        this.av1DDHeaderExtensionId = av1DDHeaderExtensionId
        val ddExt = packet.getHeaderExtension(av1DDHeaderExtensionId)
        requireNotNull(ddExt) {
            "Packet did not have Dependency Descriptor"
        }
        val parser = Av1DependencyDescriptorReader(ddExt)
        descriptor = try {
            parser.parse(templateDependencyStructure)
        } catch (e: Av1DependencyException) {
            logger.warn(
                "Could not parse AV1 Dependency Descriptor for ssrc ${packet.ssrc} seq ${packet.sequenceNumber}: " +
                    e.message
            )
            null
        }
        statelessDescriptor = descriptor ?: parser.parseStateless()
        frameInfo = try {
            descriptor?.frameInfo
        } catch (e: Av1DependencyException) {
            logger.warn(
                "Could not extract frame info from AV1 Dependency Descriptor for " +
                    "ssrc ${packet.ssrc} seq ${packet.sequenceNumber}: ${e.message}"
            )
            null
        }
    }

    /* "template_dependency_structure_present_flag MUST be set to 1 for the first packet of a coded video sequence,
     * and MUST be set to 0 otherwise"
     */
    override val isKeyframe: Boolean
        get() = statelessDescriptor.newTemplateDependencyStructure != null

    override val isStartOfFrame: Boolean
        get() = statelessDescriptor.startOfFrame

    override val isEndOfFrame: Boolean
        get() = statelessDescriptor.endOfFrame

    override val layerIds: Collection<Int>
        get() = frameInfo?.dtisPresent
            ?: run { super.layerIds }

    val frameNumber
        get() = statelessDescriptor.frameNumber

    val activeDecodeTargets
        get() = descriptor?.activeDecodeTargetsBitmask

    override fun clone(): Av1DDPacket {
        return Av1DDPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            av1DDHeaderExtensionId = av1DDHeaderExtensionId,
            encodingIndices = qualityIndices,
            descriptor = descriptor?.clone(),
            statelessDescriptor = statelessDescriptor,
            frameInfo = frameInfo
        )
    }

    fun getScalabilityStructure(
        eid: Int = 0,
        baseFrameRate: Double = 30.0
    ): RtpEncodingDesc? {
        val descriptor = this.descriptor
        require(descriptor != null) {
            "Can't get scalability structure from packet without a descriptor"
        }
        return descriptor.getScalabilityStructure(ssrc, eid, baseFrameRate)
    }
}

fun Av1DependencyDescriptorHeaderExtension.getScalabilityStructure(
    ssrc: Long,
    eid: Int = 0,
    baseFrameRate: Double = 30.0
): RtpEncodingDesc? {
    val activeDecodeTargetsBitmask = this.activeDecodeTargetsBitmask
        ?: // Can't get scalability structure from dependency descriptor that doesn't specify decode targets
        return null

    val layerCounts = Array(structure.maxSpatialId + 1) {
        IntArray(structure.maxTemporalId + 1)
    }

    // Figure out the frame rates per spatial/temporal layer.
    structure.templateInfo.forEach { t ->
        if (!t.hasInterPictureDependency()) {
            // This is a template that doesn't reference any previous frames, so is probably a key frame or
            // part of the same temporal picture with one, i.e. not part of the regular structure.
            return@forEach
        }
        layerCounts[t.spatialId][t.temporalId]++
    }

    // Sum up counts per spatial layer
    layerCounts.forEach { a ->
        var total = 0
        for (i in a.indices) {
            val entry = a[i]
            a[i] += total
            total += entry
        }
    }

    val maxFrameGroup = layerCounts.maxOf { it.maxOrNull()!! }

    val layers = ArrayList<Av1DDRtpLayerDesc>()

    structure.decodeTargetInfo.forEachIndexed { i, dt ->
        if (!activeDecodeTargetsBitmask.containsDecodeTarget(i)) {
            return@forEachIndexed
        }
        val height = structure.maxRenderResolutions.getOrNull(dt.spatialId)?.height ?: -1

        // Calculate the fraction of this spatial layer's framerate this DT comprises.
        val frameRate = baseFrameRate * layerCounts[dt.spatialId][dt.temporalId] / maxFrameGroup

        layers.add(Av1DDRtpLayerDesc(eid, i, height, frameRate))
    }
    return RtpEncodingDesc(ssrc, layers.toArray(arrayOf()), eid)
}

/** Check whether an activeDecodeTargetsBitmask contains a specific decode target. */
fun Int.containsDecodeTarget(dt: Int) =
    ((1 shl dt) and this) != 0
