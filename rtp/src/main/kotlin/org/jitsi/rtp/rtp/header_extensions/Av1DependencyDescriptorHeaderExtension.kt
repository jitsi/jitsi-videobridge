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
package org.jitsi.rtp.rtp.header_extensions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.BitReader
import org.jitsi.rtp.util.BitReaderOverrunException
import org.jitsi.rtp.util.BitWriter

/**
 * The subset of the fields of an AV1 Dependency Descriptor that can be parsed statelessly.
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
open class Av1DependencyDescriptorStatelessSubset(
    val startOfFrame: Boolean,
    val endOfFrame: Boolean,
    var frameDependencyTemplateId: Int,
    var frameNumber: Int,

    newTemplateDependencyStructure: Av1TemplateDependencyStructure?,
) {
    /**
     * The template dependency structure carried by this descriptor, or null if it does not carry one. A structure
     * is immutable and shared (see [Av1TemplateDependencyStructure]); when the descriptor is rewritten, this is
     * replaced, not modified.
     */
    var newTemplateDependencyStructure: Av1TemplateDependencyStructure? = newTemplateDependencyStructure
        protected set

    open fun clone(): Av1DependencyDescriptorStatelessSubset = Av1DependencyDescriptorStatelessSubset(
        startOfFrame = startOfFrame,
        endOfFrame = endOfFrame,
        frameDependencyTemplateId = frameDependencyTemplateId,
        frameNumber = frameNumber,
        newTemplateDependencyStructure = newTemplateDependencyStructure
    )

    companion object {
        /**
         * Parse those parts of the dependency descriptor in [length] bytes of [buffer] at [offset] that can be parsed
         * statelessly, i.e. without an external template dependency structure. The result is not a complete
         * representation of the descriptor, because some fields need the external structure to be parseable; see
         * [Av1DependencyDescriptorHeaderExtension.parse] for that.
         * @throws Av1DependencyException if the descriptor cannot be parsed.
         */
        fun parse(buffer: ByteArray, offset: Int, length: Int): Av1DependencyDescriptorStatelessSubset =
            Av1DependencyDescriptorParser.parseStateless(buffer, offset, length)

        /** Parse the stateless subset of the dependency descriptor in [ext]. See [parse]. */
        fun parse(ext: RtpPacket.HeaderExtension): Av1DependencyDescriptorStatelessSubset =
            parse(ext.buffer, ext.dataOffset, ext.dataLengthBytes)
    }
}

/**
 * The AV1 Dependency Descriptor header extension, as defined in https://aomediacodec.github.io/av1-rtp-spec/#appendix
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
class Av1DependencyDescriptorHeaderExtension(
    startOfFrame: Boolean,
    endOfFrame: Boolean,
    frameDependencyTemplateId: Int,
    frameNumber: Int,

    newTemplateDependencyStructure: Av1TemplateDependencyStructure?,

    /**
     * The active decode targets bitmask, or null if the descriptor does not specify one. A descriptor which carries a
     * template dependency structure should have a bitmask, since the specification defines its default (all decode
     * targets active) for that case, and the parser sets it. In that case a null bitmask is encoded as that default.
     * Without a carried structure, a null bitmask is encoded as absent, which means unchanged for the receiver.
     */
    var activeDecodeTargetsBitmask: Int?,

    val customDtis: List<DTI>?,
    val customFdiffs: List<Int>?,
    val customChains: List<Int>?,

    structure: Av1TemplateDependencyStructure
) : Av1DependencyDescriptorStatelessSubset(
    startOfFrame,
    endOfFrame,
    frameDependencyTemplateId,
    frameNumber,
    newTemplateDependencyStructure
) {
    /**
     * The template dependency structure in effect for this descriptor: the one it carries, if it carries one, and
     * otherwise the one from an earlier descriptor of the stream. See [rewriteTemplateIdOffset].
     */
    var structure: Av1TemplateDependencyStructure = structure
        private set

    init {
        require(newTemplateDependencyStructure == null || newTemplateDependencyStructure === structure) {
            "A descriptor which carries a template dependency structure must use that structure"
        }
        require(customDtis == null || customDtis.size == structure.decodeTargetCount) {
            "Custom DTIs must have one entry for each of the ${structure.decodeTargetCount} decode targets"
        }
        require(customChains == null || customChains.size == structure.chainCount) {
            "Custom chains must have one entry for each of the ${structure.chainCount} chains"
        }
    }

    /**
     * Change the template ID offset of [structure], for a descriptor which is being rewritten. The structure is
     * replaced with a copy that has the new offset (or kept, if the offset is unchanged), because structures are
     * immutable and shared. If this descriptor carries the structure, [newTemplateDependencyStructure] is replaced
     * too, so that the rewritten descriptor is encoded with the new offset.
     */
    fun rewriteTemplateIdOffset(templateIdOffset: Int) {
        val newStructure = structure.withTemplateIdOffset(templateIdOffset)
        if (newTemplateDependencyStructure != null) {
            newTemplateDependencyStructure = newStructure
        }
        structure = newStructure
    }

    companion object {
        /**
         * Parse the dependency descriptor in [length] bytes of [buffer] at [offset], in the context of [dep], the
         * template dependency structure currently in effect for the stream (null if none is known).
         * @throws Av1DependencyException if the descriptor cannot be parsed with [dep]: it does not carry a structure
         * and [dep] is null, it does not match [dep] (the sender changed to a new structure and the packet which
         * carried it was lost), or it is truncated or exceeds the limits of the specification.
         */
        fun parse(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            dep: Av1TemplateDependencyStructure?
        ): Av1DependencyDescriptorHeaderExtension = Av1DependencyDescriptorParser.parse(buffer, offset, length, dep)

        /** Parse the dependency descriptor in [ext]. See [parse]. */
        fun parse(
            ext: RtpPacket.HeaderExtension,
            dep: Av1TemplateDependencyStructure?
        ): Av1DependencyDescriptorHeaderExtension = parse(ext.buffer, ext.dataOffset, ext.dataLengthBytes, dep)
    }

    val frameInfo: FrameInfo by lazy {
        val templateIndex = structure.getTemplateIndex(frameDependencyTemplateId)
        val templateVal = structure.templateInfo[templateIndex]

        FrameInfo(
            spatialId = templateVal.spatialId,
            temporalId = templateVal.temporalId,
            dti = customDtis ?: templateVal.dti,
            fdiff = customFdiffs ?: templateVal.fdiff,
            chains = customChains ?: templateVal.chains
        )
    }

    val encodedLength: Int
        get() = (unpaddedLengthBits + 7) / 8

    /**
     * Whether the active decode targets bitmask is encoded in the descriptor. It is omitted when there is no bitmask,
     * and when the descriptor carries a template dependency structure and all the decode targets are active, which
     * is the default.
     */
    private val activeDecodeTargetsPresent: Boolean
        get() = isActiveDecodeTargetsPresent(activeDecodeTargetsBitmask)

    private fun isActiveDecodeTargetsPresent(bitmask: Int?): Boolean {
        if (bitmask == null) {
            return false
        }
        val newStructure = newTemplateDependencyStructure ?: return true
        return bitmask != newStructure.allDecodeTargetsMask
    }

    private val unpaddedLengthBits: Int
        get() {
            var length = 24
            if (newTemplateDependencyStructure != null ||
                activeDecodeTargetsBitmask != null ||
                customDtis != null ||
                customFdiffs != null ||
                customChains != null
            ) {
                length += 5
            }
            newTemplateDependencyStructure?.let { length += it.unpaddedLengthBits }
            if (activeDecodeTargetsPresent) {
                length += structure.decodeTargetCount
            }
            if (customDtis != null) {
                length += 2 * structure.decodeTargetCount
            }
            if (customFdiffs != null) {
                customFdiffs.forEach {
                    length += 2 + it.bitsForFdiff()
                }
                length += 2
            }
            if (customChains != null) {
                length += 8 * customChains.size
            }

            return length
        }

    override fun clone(): Av1DependencyDescriptorHeaderExtension = Av1DependencyDescriptorHeaderExtension(
        startOfFrame,
        endOfFrame,
        frameDependencyTemplateId,
        frameNumber,
        // The structure is immutable (see Av1TemplateDependencyStructure), so it is shared, as are the lists.
        newTemplateDependencyStructure,
        activeDecodeTargetsBitmask,
        customDtis,
        customFdiffs,
        customChains,
        structure
    )

    fun write(ext: RtpPacket.HeaderExtension) = write(ext.buffer, ext.dataOffset, ext.dataLengthBytes)

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(length >= encodedLength) {
            "Cannot write AV1 DD to buffer: buffer length $length must be at least $encodedLength"
        }
        val writer = BitWriter(buffer, offset, length)

        writeMandatoryDescriptorFields(writer)

        if (newTemplateDependencyStructure != null ||
            activeDecodeTargetsBitmask != null ||
            customDtis != null ||
            customFdiffs != null ||
            customChains != null
        ) {
            writeOptionalDescriptorFields(writer)
            writePadding(writer)
        } else {
            check(length == 3) {
                "AV1 DD without optional descriptors must be 3 bytes in length"
            }
        }
    }

    private fun writeMandatoryDescriptorFields(writer: BitWriter) {
        writer.writeBit(startOfFrame)
        writer.writeBit(endOfFrame)
        writer.writeBits(6, frameDependencyTemplateId)
        writer.writeBits(16, frameNumber)
    }

    private fun writeOptionalDescriptorFields(writer: BitWriter) {
        val newTemplateDependencyStructure = this.newTemplateDependencyStructure
        val templateDependencyStructurePresent = newTemplateDependencyStructure != null
        val activeDecodeTargetsBitmask = this.activeDecodeTargetsBitmask
        val activeDecodeTargetsPresent = isActiveDecodeTargetsPresent(activeDecodeTargetsBitmask)

        val customDtisFlag = customDtis != null
        val customFdiffsFlag = customFdiffs != null
        val customChainsFlag = customChains != null

        writer.writeBit(templateDependencyStructurePresent)
        writer.writeBit(activeDecodeTargetsPresent)
        writer.writeBit(customDtisFlag)
        writer.writeBit(customFdiffsFlag)
        writer.writeBit(customChainsFlag)

        newTemplateDependencyStructure?.write(writer)

        if (activeDecodeTargetsBitmask != null && activeDecodeTargetsPresent) {
            writer.writeBits(structure.decodeTargetCount, activeDecodeTargetsBitmask)
        }

        if (customDtisFlag) {
            writeFrameDtis(writer)
        }

        if (customFdiffsFlag) {
            writeFrameFdiffs(writer)
        }

        if (customChainsFlag) {
            writeFrameChains(writer)
        }
    }

    private fun writeFrameDtis(writer: BitWriter) {
        customDtis!!.forEach { dti ->
            writer.writeBits(2, dti.dti)
        }
    }

    private fun writeFrameFdiffs(writer: BitWriter) {
        customFdiffs!!.forEach { fdiff ->
            val bits = fdiff.bitsForFdiff()
            writer.writeBits(2, bits / 4)
            writer.writeBits(bits, fdiff - 1)
        }
        writer.writeBits(2, 0)
    }

    private fun writeFrameChains(writer: BitWriter) {
        customChains!!.forEach { chain ->
            writer.writeBits(8, chain)
        }
    }

    private fun writePadding(writer: BitWriter) {
        writer.writeBits(writer.remainingBits, 0)
    }

    override fun toString(): String {
        val mapper = ObjectMapper()
        return JsonNodeFactory.instance.objectNode().apply {
            put("startOfFrame", startOfFrame)
            put("endOfFrame", endOfFrame)
            put("frameDependencyTemplateId", frameDependencyTemplateId)
            put("frameNumber", frameNumber)
            newTemplateDependencyStructure?.let {
                put("templateStructure", it.toString())
            }
            customDtis?.let {
                set<com.fasterxml.jackson.databind.node.ArrayNode>(
                    "customDTIs",
                    mapper.valueToTree<com.fasterxml.jackson.databind.node.ArrayNode>(it)
                )
            }
            customFdiffs?.let {
                set<com.fasterxml.jackson.databind.node.ArrayNode>(
                    "customFdiffs",
                    mapper.valueToTree<com.fasterxml.jackson.databind.node.ArrayNode>(it)
                )
            }
            customChains?.let {
                set<com.fasterxml.jackson.databind.node.ArrayNode>(
                    "customChains",
                    mapper.valueToTree<com.fasterxml.jackson.databind.node.ArrayNode>(it)
                )
            }
            val activeDecodeTargetsBitmask = this@Av1DependencyDescriptorHeaderExtension.activeDecodeTargetsBitmask
            if (activeDecodeTargetsBitmask != null && isActiveDecodeTargetsPresent(activeDecodeTargetsBitmask)) {
                put("activeDecodeTargets", Integer.toBinaryString(activeDecodeTargetsBitmask))
            }
        }.toString()
    }
}

fun Int.bitsForFdiff() = when {
    this <= 0x10 -> 4
    this <= 0x100 -> 8
    this <= 0x1000 -> 12
    else -> throw IllegalArgumentException("Invalid FDiff value $this")
}

/**
 * The template information about a stream described by AV1 dependency descriptors.  This is carried in the
 * first packet of a codec video sequence (i.e. the first packet of a keyframe), and is necessary to interpret
 * dependency descriptors carried in subsequent packets of the sequence.
 *
 * A structure is immutable, so one instance can be shared by the descriptors of all the packets of the sequence,
 * the frames which track them, and the parser history, on any thread. A descriptor which is rewritten gets a copy
 * with a new template ID offset from [withTemplateIdOffset]; see
 * [Av1DependencyDescriptorHeaderExtension.rewriteTemplateIdOffset].
 */
class Av1TemplateDependencyStructure private constructor(
    val templateIdOffset: Int,
    val templateInfo: List<FrameInfo>,
    val decodeTargetProtectedBy: List<Int>,
    val decodeTargetLayers: List<DecodeTargetLayer>,
    val maxRenderResolutions: List<Resolution>,
    val maxSpatialId: Int,
    val maxTemporalId: Int
) {
    val templateCount
        get() = templateInfo.size

    val decodeTargetCount
        get() = decodeTargetLayers.size

    /** The active decode targets bitmask with all the decode targets active. The count can be 32, for which
     * `(1 shl count) - 1` is 0, because shift amounts are taken modulo 32. */
    val allDecodeTargetsMask: Int
        get() = if (decodeTargetCount >= Int.SIZE_BITS) -1 else (1 shl decodeTargetCount) - 1

    val chainCount: Int =
        templateInfo.first().chains.size

    /**
     * Get the index in [templateInfo] of the template with the given frame dependency template ID.
     * @throws Av1DependencyException if the ID is not a template of this structure. This happens when the sender
     * has changed to a new structure, and the packet which carried it was lost or delayed.
     */
    fun getTemplateIndex(frameDependencyTemplateId: Int): Int {
        val templateIndex = (frameDependencyTemplateId + 64 - templateIdOffset) % 64
        if (templateIndex >= templateCount) {
            val maxTemplate = (templateIdOffset + templateCount - 1) % 64
            throw Av1DependencyException(
                "Invalid template ID $frameDependencyTemplateId. " +
                    "Should be in range $templateIdOffset .. $maxTemplate. " +
                    "Missed a keyframe?"
            )
        }
        return templateIndex
    }

    companion object {
        /** The maximum number of templates in a structure: template IDs are 6 bits. */
        const val MAX_TEMPLATE_COUNT = 64

        /** The maximum spatial ID: the spatial_id field of the AV1 OBU extension header is 2 bits. */
        const val MAX_SPATIAL_ID = 3

        /** The maximum temporal ID: the temporal_id field of the AV1 OBU extension header is 3 bits. */
        const val MAX_TEMPORAL_ID = 7

        /**
         * Read a template dependency structure (template_dependency_structure() in the AV1 RTP specification) from
         * [reader]. This is the only way a structure is created from a packet; [withTemplateIdOffset] makes a copy.
         *
         * @throws Av1DependencyException if the structure exceeds the limits of the specification.
         * @throws BitReaderOverrunException if the reader runs out of bits.
         */
        internal fun read(reader: BitReader): Av1TemplateDependencyStructure = Reader(reader).read()
    }

    /** Reads one structure. The state accumulated while reading is kept here, so one instance reads one structure. */
    private class Reader(private val reader: BitReader) {
        /** A template while it is being read. [build] makes the read-only [FrameInfo] which the structure keeps. */
        private class TemplateBuilder(val spatialId: Int, val temporalId: Int) {
            val dti = mutableListOf<DTI>()
            val fdiff = mutableListOf<Int>()
            val chains = mutableListOf<Int>()

            fun build() = FrameInfo(spatialId, temporalId, dti.toList(), fdiff.toList(), chains.toList())
        }

        private val templateInfo = mutableListOf<TemplateBuilder>()
        private val decodeTargetProtectedBy = mutableListOf<Int>()
        private val decodeTargetLayers = mutableListOf<DecodeTargetLayer>()
        private val maxRenderResolutions = mutableListOf<Resolution>()

        private var dtCnt = 0
        private var maxSpatialId = 0
        private var maxTemporalId = 0

        private val templateCnt
            get() = templateInfo.size

        fun read(): Av1TemplateDependencyStructure {
            val templateIdOffset = reader.bits(6)

            val dtCntMinusOne = reader.bits(5)
            dtCnt = dtCntMinusOne + 1

            readTemplateLayers()
            readTemplateDtis()
            readTemplateFdiffs()
            readTemplateChains()
            readDecodeTargetLayers()

            val resolutionsPresent = reader.bitAsBoolean()

            if (resolutionsPresent) {
                readRenderResolutions()
            }

            return Av1TemplateDependencyStructure(
                templateIdOffset,
                templateInfo.map { it.build() },
                decodeTargetProtectedBy.toList(),
                decodeTargetLayers.toList(),
                maxRenderResolutions.toList(),
                maxSpatialId,
                maxTemporalId
            )
        }

        /**
         * Read the template layers, and check the limits of the specification. The checks run as the layers are
         * read, so that a structure which exceeds a limit is rejected for that reason, and not because the fields
         * which follow (whose sizes depend on the template count) run past the end of the descriptor.
         */
        private fun readTemplateLayers() {
            var temporalId = 0
            var spatialId = 0

            var nextLayerIdc: Int
            do {
                if (templateCnt >= MAX_TEMPLATE_COUNT) {
                    throw Av1DependencyException(
                        "Template dependency structure has more than $MAX_TEMPLATE_COUNT templates"
                    )
                }
                templateInfo.add(TemplateBuilder(spatialId, temporalId))
                nextLayerIdc = reader.bits(2)
                if (nextLayerIdc == 1) {
                    temporalId++
                    if (temporalId > MAX_TEMPORAL_ID) {
                        throw Av1DependencyException(
                            "Template dependency structure has a temporal ID above $MAX_TEMPORAL_ID"
                        )
                    }
                    if (maxTemporalId < temporalId) {
                        maxTemporalId = temporalId
                    }
                } else if (nextLayerIdc == 2) {
                    temporalId = 0
                    spatialId++
                    if (spatialId > MAX_SPATIAL_ID) {
                        throw Av1DependencyException(
                            "Template dependency structure has a spatial ID above $MAX_SPATIAL_ID"
                        )
                    }
                }
            } while (nextLayerIdc != 3)

            maxSpatialId = spatialId
        }

        private fun readTemplateDtis() {
            for (templateIndex in 0 until templateCnt) {
                for (dtIndex in 0 until dtCnt) {
                    templateInfo[templateIndex].dti.add(dtIndex, DTI.fromInt(reader.bits(2)))
                }
            }
        }

        private fun readTemplateFdiffs() {
            for (templateIndex in 0 until templateCnt) {
                var fdiffFollowsFlag = reader.bitAsBoolean()
                while (fdiffFollowsFlag) {
                    val fdiffMinusOne = reader.bits(4)
                    templateInfo[templateIndex].fdiff.add(fdiffMinusOne + 1)
                    fdiffFollowsFlag = reader.bitAsBoolean()
                }
            }
        }

        private fun readTemplateChains() {
            val chainCount = reader.ns(dtCnt + 1)
            if (chainCount != 0) {
                for (dtIndex in 0 until dtCnt) {
                    decodeTargetProtectedBy.add(dtIndex, reader.ns(chainCount))
                }
                for (templateIndex in 0 until templateCnt) {
                    for (chainIndex in 0 until chainCount) {
                        templateInfo[templateIndex].chains.add(chainIndex, reader.bits(4))
                    }
                }
            }
        }

        private fun readDecodeTargetLayers() {
            for (dtIndex in 0 until dtCnt) {
                var spatialId = 0
                var temporalId = 0
                for (templateIndex in 0 until templateCnt) {
                    if (templateInfo[templateIndex].dti[dtIndex] != DTI.NOT_PRESENT) {
                        if (templateInfo[templateIndex].spatialId > spatialId) {
                            spatialId = templateInfo[templateIndex].spatialId
                        }
                        if (templateInfo[templateIndex].temporalId > temporalId) {
                            temporalId = templateInfo[templateIndex].temporalId
                        }
                    }
                }
                decodeTargetLayers.add(dtIndex, DecodeTargetLayer(spatialId, temporalId))
            }
        }

        private fun readRenderResolutions() {
            for (spatialId in 0..maxSpatialId) {
                val widthMinus1 = reader.bits(16)
                val heightMinus1 = reader.bits(16)
                maxRenderResolutions.add(spatialId, Resolution(widthMinus1 + 1, heightMinus1 + 1))
            }
        }
    }

    val unpaddedLengthBits: Int
        get() {
            var length = 6 // Template ID offset

            length += 5 // DT Count - 1

            length += templateCount * 2 // templateLayers
            length += templateCount * decodeTargetCount * 2 // TemplateDTIs
            templateInfo.forEach {
                length += it.fdiffCnt * 5 + 1 // TemplateFDiffs
            }
            // TemplateChains
            length += nsBits(decodeTargetCount + 1, chainCount)
            if (chainCount > 0) {
                decodeTargetProtectedBy.forEach {
                    length += nsBits(chainCount, it)
                }
                length += templateCount * chainCount * 4
            }
            length += 1 // ResolutionsPresent
            length += maxRenderResolutions.size * 32 // RenderResolutions

            return length
        }

    /** A copy of this structure with the template ID offset [templateIdOffset], or this structure if it already has
     * that offset. */
    fun withTemplateIdOffset(templateIdOffset: Int): Av1TemplateDependencyStructure =
        if (templateIdOffset == this.templateIdOffset) {
            this
        } else {
            require(templateIdOffset in 0 until MAX_TEMPLATE_COUNT) {
                "Template ID offset $templateIdOffset is not a 6-bit template ID"
            }
            Av1TemplateDependencyStructure(
                templateIdOffset,
                // The lists are read-only, so they can be shared.
                templateInfo,
                decodeTargetProtectedBy,
                decodeTargetLayers,
                maxRenderResolutions,
                maxSpatialId,
                maxTemporalId
            )
        }

    fun write(writer: BitWriter) {
        writer.writeBits(6, templateIdOffset)

        writer.writeBits(5, decodeTargetCount - 1)

        writeTemplateLayers(writer)
        writeTemplateDtis(writer)
        writeTemplateFdiffs(writer)
        writeTemplateChains(writer)

        writeRenderResolutions(writer)
    }

    private fun writeTemplateLayers(writer: BitWriter) {
        check(templateInfo[0].spatialId == 0 && templateInfo[0].temporalId == 0) {
            "First template must have spatial and temporal IDs 0/0, but found " +
                "${templateInfo[0].spatialId}/${templateInfo[0].temporalId}"
        }
        for (templateNum in 1 until templateInfo.size) {
            val layerIdc = when {
                templateInfo[templateNum].spatialId == templateInfo[templateNum - 1].spatialId &&
                    templateInfo[templateNum].temporalId == templateInfo[templateNum - 1].temporalId ->
                    0

                templateInfo[templateNum].spatialId == templateInfo[templateNum - 1].spatialId &&
                    templateInfo[templateNum].temporalId == templateInfo[templateNum - 1].temporalId + 1 ->
                    1

                templateInfo[templateNum].spatialId == templateInfo[templateNum - 1].spatialId + 1 &&
                    templateInfo[templateNum].temporalId == 0 ->
                    2

                else ->
                    throw IllegalStateException(
                        "Template $templateNum with spatial and temporal IDs " +
                            "${templateInfo[templateNum].spatialId}/${templateInfo[templateNum].temporalId} " +
                            "cannot follow template ${templateNum - 1} with spatial and temporal IDs " +
                            "${templateInfo[templateNum - 1].spatialId}/${templateInfo[templateNum - 1].temporalId}."
                    )
            }
            writer.writeBits(2, layerIdc)
        }
        writer.writeBits(2, 3)
    }

    private fun writeTemplateDtis(writer: BitWriter) {
        templateInfo.forEach { t ->
            t.dti.forEach { dti ->
                writer.writeBits(2, dti.dti)
            }
        }
    }

    private fun writeTemplateFdiffs(writer: BitWriter) {
        templateInfo.forEach { t ->
            t.fdiff.forEach { fdiff ->
                writer.writeBit(true)
                writer.writeBits(4, fdiff - 1)
            }
            writer.writeBit(false)
        }
    }

    private fun writeTemplateChains(writer: BitWriter) {
        writer.writeNs(decodeTargetCount + 1, chainCount)
        decodeTargetProtectedBy.forEach {
            writer.writeNs(chainCount, it)
        }
        templateInfo.forEach { t ->
            t.chains.forEach { chain ->
                writer.writeBits(4, chain)
            }
        }
    }

    private fun writeRenderResolutions(writer: BitWriter) {
        if (maxRenderResolutions.isEmpty()) {
            writer.writeBit(false)
        } else {
            writer.writeBit(true)
            maxRenderResolutions.forEach { r ->
                writer.writeBits(16, r.width - 1)
                writer.writeBits(16, r.height - 1)
            }
        }
    }

    /** Return whether, in this structure, it's possible to switch from DT [fromDt] to DT [toDt]
     * without a keyframe.
     * Note this makes certain assumptions about the encoding structure.
     */
    fun canSwitchWithoutKeyframe(fromDt: Int, toDt: Int): Boolean = templateInfo.any {
        it.hasInterPictureDependency() && it.dti.size > fromDt && it.dti.size > toDt &&
            it.dti[fromDt] != DTI.NOT_PRESENT && it.dti[toDt] == DTI.SWITCH
    }

    /** Given that we are sending packets for a given DT, return a decodeTargetBitmask corresponding to all DTs
     * contained in that DT.
     */
    fun getDtBitmaskForDt(dt: Int): Int {
        var mask = allDecodeTargetsMask
        templateInfo.forEach { frameInfo ->
            frameInfo.dti.forEachIndexed { i, dti ->
                if (frameInfo.dti[dt] == DTI.NOT_PRESENT && dti != DTI.NOT_PRESENT) {
                    mask = mask and (1 shl i).inv()
                }
            }
        }
        return mask
    }

    override fun toString(): String {
        val mapper = ObjectMapper()
        return JsonNodeFactory.instance.objectNode().apply {
            put("templateIdOffset", templateIdOffset)
            set<com.fasterxml.jackson.databind.node.ObjectNode>(
                "templateInfo",
                mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
                    templateInfo.map {
                        it.toString()
                    }.toIndexedMap()
                )
            )
            set<com.fasterxml.jackson.databind.node.ObjectNode>(
                "decodeTargetProtectedBy",
                mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
                    decodeTargetProtectedBy.toIndexedMap()
                )
            )
            set<com.fasterxml.jackson.databind.node.ObjectNode>(
                "decodeTargetLayers",
                mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
                    decodeTargetLayers.map {
                        it.toString()
                    }.toIndexedMap()
                )
            )
            if (maxRenderResolutions.isNotEmpty()) {
                set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "maxRenderResolutions",
                    mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
                        maxRenderResolutions.map {
                            it.toString()
                        }.toIndexedMap()
                    )
                )
            }
            put("maxSpatialId", maxSpatialId)
            put("maxTemporalId", maxTemporalId)
        }.toString()
    }
}

fun nsBits(n: Int, v: Int): Int {
    require(n > 0)
    if (n == 1) return 0
    var w = 0
    var x = n
    while (x != 0) {
        x = x shr 1
        w++
    }
    val m = (1 shl w) - n
    if (v < m) return w - 1
    return w
}

/**
 * Parses AV1 dependency descriptors, for [Av1DependencyDescriptorHeaderExtension.parse] and
 * [Av1DependencyDescriptorStatelessSubset.parse]. Each call works on its own [BitReader] and in locals.
 */
private object Av1DependencyDescriptorParser {
    /** The fields at the start of every descriptor (mandatory_descriptor_fields() in the specification). */
    private class MandatoryFields(
        val startOfFrame: Boolean,
        val endOfFrame: Boolean,
        val frameDependencyTemplateId: Int,
        val frameNumber: Int
    )

    fun parseStateless(buffer: ByteArray, offset: Int, length: Int): Av1DependencyDescriptorStatelessSubset =
        readFields(buffer, offset, length) { reader ->
            val mandatory = reader.readMandatoryDescriptorFields()
            var newTemplateDependencyStructure: Av1TemplateDependencyStructure? = null

            if (length > 3) {
                val templateDependencyStructurePresent = reader.bitAsBoolean()

                /* activeDecodeTargetsPresent, customDtisFlag, customFdiffsFlag, and customChainsFlag;
                 * none of these fields are parseable statelessly.
                 */
                reader.skipBits(4)

                if (templateDependencyStructurePresent) {
                    newTemplateDependencyStructure = Av1TemplateDependencyStructure.read(reader)
                }
            }
            Av1DependencyDescriptorStatelessSubset(
                mandatory.startOfFrame,
                mandatory.endOfFrame,
                mandatory.frameDependencyTemplateId,
                mandatory.frameNumber,
                newTemplateDependencyStructure,
            )
        }

    fun parse(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        dep: Av1TemplateDependencyStructure?
    ): Av1DependencyDescriptorHeaderExtension = readFields(buffer, offset, length) { reader ->
        val mandatory = reader.readMandatoryDescriptorFields()
        if (length > 3) {
            reader.readExtendedDescriptorFields(mandatory, dep)
        } else {
            if (dep == null) {
                throw Av1DependencyException("No external dependency structure specified for non-first packet")
            }
            /* See readExtendedDescriptorFields: a template ID which is not in the structure means the structure
             * is stale. */
            dep.getTemplateIndex(mandatory.frameDependencyTemplateId)
            Av1DependencyDescriptorHeaderExtension(
                mandatory.startOfFrame,
                mandatory.endOfFrame,
                mandatory.frameDependencyTemplateId,
                mandatory.frameNumber,
                newTemplateDependencyStructure = null,
                activeDecodeTargetsBitmask = null,
                customDtis = null,
                customFdiffs = null,
                customChains = null,
                structure = dep
            )
        }
    }

    /**
     * Run [block] with a reader positioned at the start of the descriptor, and convert a read past the end of the
     * descriptor into an [Av1DependencyException]. This happens when the descriptor is truncated, or when it does
     * not match the template dependency structure it is parsed with (the sender changed to a new structure, and the
     * packet which carried it was lost). An [Av1DependencyException] thrown by [block] passes through. Other
     * exceptions are not converted: they indicate a bug, not unparseable input.
     */
    private inline fun <T> readFields(buffer: ByteArray, offset: Int, length: Int, block: (BitReader) -> T): T {
        try {
            return block(BitReader(buffer, offset, length))
        } catch (e: BitReaderOverrunException) {
            throw Av1DependencyException(
                "Dependency descriptor of $length bytes is too short for its fields: ${e.message}"
            )
        }
    }

    private fun BitReader.readMandatoryDescriptorFields(): MandatoryFields {
        val startOfFrame = bitAsBoolean()
        val endOfFrame = bitAsBoolean()
        val frameDependencyTemplateId = bits(6)
        val frameNumber = bits(16)
        return MandatoryFields(startOfFrame, endOfFrame, frameDependencyTemplateId, frameNumber)
    }

    private fun BitReader.readExtendedDescriptorFields(
        mandatory: MandatoryFields,
        dep: Av1TemplateDependencyStructure?
    ): Av1DependencyDescriptorHeaderExtension {
        val templateDependencyStructurePresent = bitAsBoolean()
        val activeDecodeTargetsPresent = bitAsBoolean()
        val customDtisFlag = bitAsBoolean()
        val customFdiffsFlag = bitAsBoolean()
        val customChainsFlag = bitAsBoolean()

        val newTemplateDependencyStructure: Av1TemplateDependencyStructure?
        val structure: Av1TemplateDependencyStructure
        if (templateDependencyStructurePresent) {
            newTemplateDependencyStructure = Av1TemplateDependencyStructure.read(this)
            structure = newTemplateDependencyStructure
        } else {
            newTemplateDependencyStructure = null
            structure = dep
                ?: throw Av1DependencyException("No external dependency structure specified for non-first packet")
        }
        val activeDecodeTargetsBitmask = if (activeDecodeTargetsPresent) {
            bits(structure.decodeTargetCount)
        } else if (templateDependencyStructurePresent) {
            structure.allDecodeTargetsMask
        } else {
            null
        }

        /* The spec (frame_dependency_definition()) checks the template index before it reads the custom DTIs,
         * fdiffs and chains, because their sizes come from the structure. If the template ID is not in the
         * structure, the structure is stale, and the custom fields must not be read with it. */
        structure.getTemplateIndex(mandatory.frameDependencyTemplateId)

        val customDtis = if (customDtisFlag) readFrameDtis(structure) else null
        val customFdiffs = if (customFdiffsFlag) readFrameFdiffs() else null
        val customChains = if (customChainsFlag) readFrameChains(structure) else null

        return Av1DependencyDescriptorHeaderExtension(
            mandatory.startOfFrame,
            mandatory.endOfFrame,
            mandatory.frameDependencyTemplateId,
            mandatory.frameNumber,
            newTemplateDependencyStructure,
            activeDecodeTargetsBitmask,
            customDtis,
            customFdiffs,
            customChains,
            structure
        )
    }

    private fun BitReader.readFrameDtis(structure: Av1TemplateDependencyStructure): List<DTI> =
        List(structure.decodeTargetCount) {
            DTI.fromInt(bits(2))
        }

    private fun BitReader.readFrameFdiffs(): List<Int> = buildList {
        var nextFdiffSize = bits(2)
        while (nextFdiffSize != 0) {
            val fdiffMinus1 = bits(4 * nextFdiffSize)
            add(fdiffMinus1 + 1)
            nextFdiffSize = bits(2)
        }
    }

    private fun BitReader.readFrameChains(structure: Av1TemplateDependencyStructure): List<Int> =
        List(structure.chainCount) {
            bits(8)
        }
}

class FrameInfo(
    val spatialId: Int,
    val temporalId: Int,
    val dti: List<DTI>,
    val fdiff: List<Int>,
    val chains: List<Int>
) {
    val fdiffCnt
        get() = fdiff.size

    override fun equals(other: Any?): Boolean {
        if (other !is FrameInfo) {
            return false
        }
        return other.spatialId == spatialId &&
            other.temporalId == temporalId &&
            other.dti == dti &&
            other.fdiff == fdiff &&
            other.chains == chains
    }

    override fun hashCode(): Int {
        var result = spatialId
        result = 31 * result + temporalId
        result = 31 * result + dti.hashCode()
        result = 31 * result + fdiff.hashCode()
        result = 31 * result + chains.hashCode()
        return result
    }

    override fun toString(): String =
        "spatialId=$spatialId, temporalId=$temporalId, dti=$dti, fdiff=$fdiff, chains=$chains"

    fun toJson(): String {
        val mapper = ObjectMapper()
        return JsonNodeFactory.instance.objectNode().apply {
            put("spatialId", spatialId)
            put("temporalId", temporalId)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("dti", mapper.valueToTree(dti.map { it.name }))
            set<com.fasterxml.jackson.databind.node.ArrayNode>("fdiff", mapper.valueToTree(fdiff))
            set<com.fasterxml.jackson.databind.node.ArrayNode>("chains", mapper.valueToTree(chains))
        }.toString()
    }

    /** Whether the frame has a dependency on a frame earlier than this "picture", the other frames of this
     * temporal moment.  If it doesn't, it's probably part of a keyframe, and not part of the regular structure.
     * Note this makes assumptions about the scalability structure.
     */
    fun hasInterPictureDependency(): Boolean = fdiff.any { it > spatialId }

    val dtisPresent: List<Int>
        get() = dti.withIndex().filter { (_, dti) -> dti != DTI.NOT_PRESENT }.map { (i, _) -> i }
}

class DecodeTargetLayer(
    val spatialId: Int,
    val temporalId: Int
) {
    override fun toString(): String = JsonNodeFactory.instance.objectNode().apply {
        put("spatialId", spatialId)
        put("temporalId", temporalId)
    }.toString()
}

data class Resolution(
    val width: Int,
    val height: Int
) {
    override fun toString(): String = JsonNodeFactory.instance.objectNode().apply {
        put("width", width)
        put("height", height)
    }.toString()
}

/** Decode target indication */
enum class DTI(val dti: Int) {
    NOT_PRESENT(0),
    DISCARDABLE(1),
    SWITCH(2),
    REQUIRED(3);

    companion object {
        private val map = DTI.values().associateBy(DTI::dti)
        fun fromInt(type: Int) = map[type] ?: throw java.lang.IllegalArgumentException("Bad DTI $type")
    }

    fun toShortString(): String = when (this) {
        NOT_PRESENT -> "N"
        DISCARDABLE -> "D"
        SWITCH -> "S"
        REQUIRED -> "R"
    }
}

fun List<DTI>.toShortString(): String = joinToString(separator = "") { it.toShortString() }

/** A dependency descriptor, or a template dependency structure, which cannot be parsed. */
open class Av1DependencyException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

fun <T> List<T>.toIndexedMap(): Map<Int, T> = mapIndexed { i, t -> i to t }.toMap()
