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
import org.jitsi.rtp.rtp.header_extensions.DTI
import org.jitsi.rtp.rtp.header_extensions.FrameInfo

/** A video packet carrying an AV1 Dependency Descriptor.  Note that this may or may not be an actual AV1 packet;
 * other video codecs can also carry the AV1 DD.
 */
class Av1DDPacket : ParsedVideoPacket {
    val descriptor: Av1DependencyDescriptorHeaderExtension?
    val statelessDescriptor: Av1DependencyDescriptorStatelessSubset
    val frameInfo: FrameInfo?

    private constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        encodingIndices: Collection<Int>,
        descriptor: Av1DependencyDescriptorHeaderExtension?,
        statelessDescriptor: Av1DependencyDescriptorStatelessSubset,
        frameInfo: FrameInfo?
    ) : super(buffer, offset, length, encodingIndices) {
        this.descriptor = descriptor
        this.statelessDescriptor = statelessDescriptor
        this.frameInfo = frameInfo
    }

    constructor(
        packet: RtpPacket,
        av1DDHeaderExtensionId: Int,
        templateDependencyStructure: Av1TemplateDependencyStructure?
    ) : super(packet.buffer, packet.offset, packet.length, emptyList()) {
        val ddExt = packet.getHeaderExtension(av1DDHeaderExtensionId)
        requireNotNull(ddExt) {
            "Packet did not have Dependency Descriptor"
        }
        val parser = Av1DependencyDescriptorReader(ddExt)
        descriptor = try {
            parser.parse(templateDependencyStructure)
        } catch (e: Av1DependencyException) {
            // TODO: log this, without creating a logger for each packet
            // TODO: have some way of recovering if we get the correct template structure later?
            null
        }
        statelessDescriptor = descriptor ?: parser.parseStateless()
        frameInfo = try {
            descriptor?.frameInfo
        } catch (e: Av1DependencyException) {
            // TODO: log this, without creating a logger for each packet
            // TODO: have some way of recovering if we get the correct template structure later?
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
        get() = frameInfo?.let {
            it.dti.withIndex().filter { (_, dti) -> dti != DTI.NOT_PRESENT }.map { (i, _), -> i }
        } ?: run { super.layerIds }

    val frameNumber
        get() = statelessDescriptor.frameNumber

    override fun clone(): Av1DDPacket {
        /* TODO: when descriptor becomes mutable (i.e. we can write to the active decode targets bitmask), clone
         *  it here.
         */
        return Av1DDPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            encodingIndices = qualityIndices,
            descriptor = descriptor,
            statelessDescriptor = statelessDescriptor,
            frameInfo = frameInfo
        )
    }

    fun getScalabilityStructure(
        eid: Int = 0,
        baseFrameRate: Double = 30.0
    ): RtpEncodingDesc {
        require(descriptor != null) {
            "Can't get scalability structure from packet without a descriptor"
        }
        val activeDecodeTargetsBitmask = descriptor.activeDecodeTargetsBitmask
        require(activeDecodeTargetsBitmask != null) {
            "Can't get scalability structure from packet that doesn't specify decode targets"
        }
        val layers = ArrayList<Av1DDRtpLayerDesc>()
        // TODO: figure out frame rates from the template structure
        descriptor.structure.decodeTargetInfo.forEachIndexed { i, dt ->
            if (((1 shl i) and activeDecodeTargetsBitmask) == 0) {
                return@forEachIndexed
            }
            val height = descriptor.structure.maxRenderResolutions.getOrNull(dt.spatialId)?.height ?: -1
            val frameRate = baseFrameRate // TODO: figure out frame rates from the template structure
            layers.add(Av1DDRtpLayerDesc(eid, i, height, frameRate))
        }
        return RtpEncodingDesc(ssrc, layers.toArray(arrayOf()), eid)
    }
}
