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
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorStatelessSubset
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.utils.logging2.Logger
import kotlin.math.min

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
        encodingId: Int,
        descriptor: Av1DependencyDescriptorHeaderExtension?,
        statelessDescriptor: Av1DependencyDescriptorStatelessSubset,
        frameInfo: FrameInfo?
    ) : super(buffer, offset, length, encodingId) {
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
    ) : super(packet.buffer, packet.offset, packet.length, RtpLayerDesc.SUSPENDED_ENCODING_ID) {
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

    override fun meetsRoutingNeeds(): Boolean =
        true // If it didn't parse as AV1 we would have failed in the constructor

    override val layerIds: Collection<Int>
        get() = frameInfo?.dtisPresent
            ?: run { super.layerIds }

    val frameNumber
        get() = statelessDescriptor.frameNumber

    val activeDecodeTargets
        get() = descriptor?.activeDecodeTargetsBitmask

    override fun toString(): String = buildString {
        append(super.toString())
        append(", DTIs=${frameInfo?.dtisPresent}")
        activeDecodeTargets?.let { append(", ActiveTargets=$it") }
    }

    override fun clone(): Av1DDPacket {
        val descriptor = descriptor?.clone()
        val statelessDescriptor = descriptor ?: statelessDescriptor.clone()
        return Av1DDPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            av1DDHeaderExtensionId = av1DDHeaderExtensionId,
            encodingId = encodingId,
            descriptor = descriptor,
            statelessDescriptor = statelessDescriptor,
            frameInfo = frameInfo
        ).also { postClone(it) }
    }

    fun getScalabilityStructure(eid: Int = 0, baseFrameRate: Double = 30.0): RtpEncodingDesc? {
        val descriptor = this.descriptor
        requireNotNull(descriptor) {
            "Can't get scalability structure from packet without a descriptor"
        }
        return descriptor.getScalabilityStructure(ssrc, eid, baseFrameRate)
    }

    /** Re-encode the current descriptor to the header extension.  For use after modifying it. */
    fun reencodeDdExt() {
        val descriptor = this.descriptor
        requireNotNull(descriptor) {
            "Can't re-encode extension from a packet without a descriptor"
        }

        var ext = getHeaderExtension(av1DDHeaderExtensionId)
        if (ext == null || ext.dataLengthBytes != descriptor.encodedLength) {
            removeHeaderExtension(av1DDHeaderExtensionId)
            ext = addHeaderExtension(av1DDHeaderExtensionId, descriptor.encodedLength)
        }
        descriptor.write(ext)
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

    structure.decodeTargetLayers.forEachIndexed { i, dt ->
        if (!activeDecodeTargetsBitmask.containsDecodeTarget(i)) {
            return@forEachIndexed
        }
        // Treat the lesser of width and height as the height in order to handle portrait-mode video correctly
        val height = structure.maxRenderResolutions.getOrNull(dt.spatialId)?.let { min(it.width, it.height) } ?: -1

        // Calculate the fraction of this spatial layer's framerate this DT comprises.
        val frameRate = baseFrameRate * layerCounts[dt.spatialId][dt.temporalId] / maxFrameGroup

        layers.add(Av1DDRtpLayerDesc(eid, i, dt.temporalId, dt.spatialId, height, frameRate))
    }
    return RtpEncodingDesc(ssrc, layers.toArray(arrayOf()), eid)
}

/** Check whether an activeDecodeTargetsBitmask contains a specific decode target. */
fun Int.containsDecodeTarget(dt: Int) = ((1 shl dt) and this) != 0

/**
 * Returns the delta between two AV1 templateID values, taking into account
 * rollover.  This will return the 'positive' delta between the two
 * picture IDs in the form of the number you'd add to b to get a. e.g.:
 * getTl0PicIdxDelta(1, 10) -> 55 (10 + 55 = 1)
 * getTl0PicIdxDelta(1, 58) -> 7 (58 + 7 = 1)
 */
fun getTemplateIdDelta(a: Int, b: Int): Int = (a - b + 64) % 64

/**
 * Apply a delta to a given templateID and return the result (taking
 * rollover into account)
 * @param start the starting templateID
 * @param delta the delta to be applied
 * @return the templateID resulting from doing "start + delta"
 */
fun applyTemplateIdDelta(start: Int, delta: Int): Int = (start + delta) % 64
