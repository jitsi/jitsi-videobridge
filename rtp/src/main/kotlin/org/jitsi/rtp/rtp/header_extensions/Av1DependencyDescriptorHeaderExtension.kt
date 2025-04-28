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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.BitReader
import org.jitsi.rtp.util.BitWriter
import org.jitsi.utils.OrderedJsonObject
import org.json.simple.JSONAware

/**
 * The subset of the fields of an AV1 Dependency Descriptor that can be parsed statelessly.
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
open class Av1DependencyDescriptorStatelessSubset(
    val startOfFrame: Boolean,
    val endOfFrame: Boolean,
    var frameDependencyTemplateId: Int,
    var frameNumber: Int,

    val newTemplateDependencyStructure: Av1TemplateDependencyStructure?,
) {
    open fun clone(): Av1DependencyDescriptorStatelessSubset {
        return Av1DependencyDescriptorStatelessSubset(
            startOfFrame = startOfFrame,
            endOfFrame = endOfFrame,
            frameDependencyTemplateId = frameDependencyTemplateId,
            frameNumber = frameNumber,
            newTemplateDependencyStructure = newTemplateDependencyStructure?.clone()
        )
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

    var activeDecodeTargetsBitmask: Int?,

    val customDtis: List<DTI>?,
    val customFdiffs: List<Int>?,
    val customChains: List<Int>?,

    val structure: Av1TemplateDependencyStructure
) : Av1DependencyDescriptorStatelessSubset(
    startOfFrame,
    endOfFrame,
    frameDependencyTemplateId,
    frameNumber,
    newTemplateDependencyStructure
),
    JSONAware {
    val frameInfo: FrameInfo by lazy {
        val templateIndex = (frameDependencyTemplateId + 64 - structure.templateIdOffset) % 64
        if (templateIndex >= structure.templateCount) {
            val maxTemplate = (structure.templateIdOffset + structure.templateCount - 1) % 64
            throw Av1DependencyException(
                "Invalid template ID $frameDependencyTemplateId. " +
                    "Should be in range ${structure.templateIdOffset} .. $maxTemplate. " +
                    "Missed a keyframe?"
            )
        }
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
            if (newTemplateDependencyStructure != null) {
                length += newTemplateDependencyStructure.unpaddedLengthBits
            }
            if (activeDecodeTargetsBitmask != null &&
                (
                    newTemplateDependencyStructure == null ||
                        activeDecodeTargetsBitmask != ((1 shl newTemplateDependencyStructure.decodeTargetCount) - 1)
                    )
            ) {
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

    override fun clone(): Av1DependencyDescriptorHeaderExtension {
        val structureCopy = structure.clone()
        val newStructure = if (newTemplateDependencyStructure == null) null else structureCopy
        return Av1DependencyDescriptorHeaderExtension(
            startOfFrame,
            endOfFrame,
            frameDependencyTemplateId,
            frameNumber,
            newStructure,
            activeDecodeTargetsBitmask,
            // These values are not mutable so it's safe to copy them by reference
            customDtis,
            customFdiffs,
            customChains,
            structureCopy
        )
    }

    fun write(ext: RtpPacket.HeaderExtension) = write(ext.buffer, ext.dataOffset, ext.dataLengthBytes)

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(length <= encodedLength) {
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
        val templateDependencyStructurePresent = newTemplateDependencyStructure != null
        val activeDecodeTargetsPresent = activeDecodeTargetsBitmask != null &&
            (
                newTemplateDependencyStructure == null ||
                    activeDecodeTargetsBitmask != ((1 shl newTemplateDependencyStructure.decodeTargetCount) - 1)
                )

        val customDtisFlag = customDtis != null
        val customFdiffsFlag = customFdiffs != null
        val customChainsFlag = customChains != null

        writer.writeBit(templateDependencyStructurePresent)
        writer.writeBit(activeDecodeTargetsPresent)
        writer.writeBit(customDtisFlag)
        writer.writeBit(customFdiffsFlag)
        writer.writeBit(customChainsFlag)

        if (templateDependencyStructurePresent) {
            newTemplateDependencyStructure!!.write(writer)
        }

        if (activeDecodeTargetsPresent) {
            writeActiveDecodeTargets(writer)
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

    private fun writeActiveDecodeTargets(writer: BitWriter) {
        writer.writeBits(structure.decodeTargetCount, activeDecodeTargetsBitmask!!)
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

    override fun toJSONString(): String {
        return OrderedJsonObject().apply {
            put("startOfFrame", startOfFrame)
            put("endOfFrame", endOfFrame)
            put("frameDependencyTemplateId", frameDependencyTemplateId)
            put("frameNumber", frameNumber)
            newTemplateDependencyStructure?.let { put("templateStructure", it) }
            customDtis?.let { put("customDTIs", it) }
            customFdiffs?.let { put("customFdiffs", it) }
            customChains?.let { put("customChains", it) }
            activeDecodeTargetsBitmask?.let {
                if (newTemplateDependencyStructure == null ||
                    it != ((1 shl newTemplateDependencyStructure.decodeTargetCount) - 1)
                ) {
                    put("activeDecodeTargets", Integer.toBinaryString(it))
                }
            }
        }.toJSONString()
    }

    override fun toString(): String = toJSONString()
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
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
class Av1TemplateDependencyStructure(
    var templateIdOffset: Int,
    val templateInfo: List<FrameInfo>,
    val decodeTargetProtectedBy: List<Int>,
    val decodeTargetLayers: List<DecodeTargetLayer>,
    val maxRenderResolutions: List<Resolution>,
    val maxSpatialId: Int,
    val maxTemporalId: Int
) : JSONAware {
    val templateCount
        get() = templateInfo.size

    val decodeTargetCount
        get() = decodeTargetLayers.size

    val chainCount: Int =
        templateInfo.first().chains.size

    init {
        check(templateInfo.all { it.chains.size == chainCount }) {
            "Templates have inconsistent chain sizes"
        }
        check(templateInfo.all { it.temporalId <= maxTemporalId }) {
            "Incorrect maxTemporalId"
        }
        check(maxRenderResolutions.isEmpty() || maxRenderResolutions.size == maxSpatialId + 1) {
            "Non-zero number of render resolutions does not match maxSpatialId"
        }
        check(templateInfo.all { it.spatialId <= maxSpatialId }) {
            "Incorrect maxSpatialId"
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

    fun clone(): Av1TemplateDependencyStructure {
        return Av1TemplateDependencyStructure(
            templateIdOffset,
            // These objects are not mutable so it's safe to copy them by reference
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
        var mask = (1 shl decodeTargetCount) - 1
        templateInfo.forEach { frameInfo ->
            frameInfo.dti.forEachIndexed { i, dti ->
                if (frameInfo.dti[dt] == DTI.NOT_PRESENT && dti != DTI.NOT_PRESENT) {
                    mask = mask and (1 shl i).inv()
                }
            }
        }
        return mask
    }

    override fun toJSONString(): String {
        return OrderedJsonObject().apply {
            put("templateIdOffset", templateIdOffset)
            put("templateInfo", templateInfo.toIndexedMap())
            put("decodeTargetProtectedBy", decodeTargetProtectedBy.toIndexedMap())
            put("decodeTargetLayers", decodeTargetLayers.toIndexedMap())
            if (maxRenderResolutions.isNotEmpty()) {
                put("maxRenderResolutions", maxRenderResolutions.toIndexedMap())
            }
            put("maxSpatialId", maxSpatialId)
            put("maxTemporalId", maxTemporalId)
        }.toJSONString()
    }

    override fun toString() = toJSONString()
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

class Av1DependencyDescriptorReader(
    buffer: ByteArray,
    offset: Int,
    val length: Int,
) {
    private var startOfFrame = false
    private var endOfFrame = false
    private var frameDependencyTemplateId = 0
    private var frameNumber = 0

    private var customDtis: List<DTI>? = null
    private var customFdiffs: List<Int>? = null
    private var customChains: List<Int>? = null

    private var localTemplateDependencyStructure: Av1TemplateDependencyStructure? = null
    private var templateDependencyStructure: Av1TemplateDependencyStructure? = null

    private var activeDecodeTargetsBitmask: Int? = null

    private val reader = BitReader(buffer, offset, length)

    constructor(ext: RtpPacket.HeaderExtension) :
        this(ext.buffer, ext.dataOffset, ext.dataLengthBytes)

    /** Parse those parts of the dependency descriptor that can be parsed statelessly, i.e. without an external
     * template dependency structure.  The returned object will not be a complete representation of the
     * dependency descriptor, because some fields need the external structure to be parseable.
     */
    fun parseStateless(): Av1DependencyDescriptorStatelessSubset {
        reset()
        readMandatoryDescriptorFields()

        if (length > 3) {
            val templateDependencyStructurePresent = reader.bitAsBoolean()

            /* activeDecodeTargetsPresent, customDtisFlag, customFdiffsFlag, and customChainsFlag;
             * none of these fields are parseable statelessly.
             */
            reader.skipBits(4)

            if (templateDependencyStructurePresent) {
                localTemplateDependencyStructure = readTemplateDependencyStructure()
            }
        }
        return Av1DependencyDescriptorStatelessSubset(
            startOfFrame,
            endOfFrame,
            frameDependencyTemplateId,
            frameNumber,
            localTemplateDependencyStructure,
        )
    }

    /** Parse the dependency descriptor in the context of [dep], the currently-applicable template dependency
     * structure.*/
    fun parse(dep: Av1TemplateDependencyStructure?): Av1DependencyDescriptorHeaderExtension {
        reset()
        readMandatoryDescriptorFields()
        if (length > 3) {
            readExtendedDescriptorFields(dep)
        } else {
            if (dep == null) {
                throw Av1DependencyException("No external dependency structure specified for non-first packet")
            }
            templateDependencyStructure = dep
        }
        return Av1DependencyDescriptorHeaderExtension(
            startOfFrame,
            endOfFrame,
            frameDependencyTemplateId,
            frameNumber,
            localTemplateDependencyStructure,
            activeDecodeTargetsBitmask,
            customDtis,
            customFdiffs,
            customChains,
            templateDependencyStructure!!
        )
    }

    private fun reset() = reader.reset()

    private fun readMandatoryDescriptorFields() {
        startOfFrame = reader.bitAsBoolean()
        endOfFrame = reader.bitAsBoolean()
        frameDependencyTemplateId = reader.bits(6)
        frameNumber = reader.bits(16)
    }

    private fun readExtendedDescriptorFields(dep: Av1TemplateDependencyStructure?) {
        val templateDependencyStructurePresent = reader.bitAsBoolean()
        val activeDecodeTargetsPresent = reader.bitAsBoolean()
        val customDtisFlag = reader.bitAsBoolean()
        val customFdiffsFlag = reader.bitAsBoolean()
        val customChainsFlag = reader.bitAsBoolean()

        if (templateDependencyStructurePresent) {
            localTemplateDependencyStructure = readTemplateDependencyStructure()
            templateDependencyStructure = localTemplateDependencyStructure
        } else {
            if (dep == null) {
                throw Av1DependencyException("No external dependency structure specified for non-first packet")
            }
            templateDependencyStructure = dep
        }
        if (activeDecodeTargetsPresent) {
            activeDecodeTargetsBitmask = reader.bits(templateDependencyStructure!!.decodeTargetCount)
        } else if (templateDependencyStructurePresent) {
            activeDecodeTargetsBitmask = (1 shl templateDependencyStructure!!.decodeTargetCount) - 1
        }

        if (customDtisFlag) {
            customDtis = readFrameDtis()
        }
        if (customFdiffsFlag) {
            customFdiffs = readFrameFdiffs()
        }
        if (customChainsFlag) {
            customChains = readFrameChains()
        }
    }

    /* Data for template dependency structure */
    private var templateIdOffset: Int = 0
    private val templateInfo = mutableListOf<TemplateFrameInfo>()
    private val decodeTargetProtectedBy = mutableListOf<Int>()
    private val decodeTargetLayers = mutableListOf<DecodeTargetLayer>()
    private val maxRenderResolutions = mutableListOf<Resolution>()

    private var dtCnt = 0

    private fun resetDependencyStructureInfo() {
        /* These fields are assembled incrementally when parsing a dependency structure; reset them
         * in case we're running a parser more than once.
         */
        templateCnt = 0
        templateInfo.clear()
        decodeTargetProtectedBy.clear()
        decodeTargetLayers.clear()
        maxRenderResolutions.clear()
    }

    private fun readTemplateDependencyStructure(): Av1TemplateDependencyStructure {
        resetDependencyStructureInfo()

        templateIdOffset = reader.bits(6)

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
            templateInfo.toList(),
            decodeTargetProtectedBy.toList(),
            decodeTargetLayers.toList(),
            maxRenderResolutions.toList(),
            maxSpatialId,
            maxTemporalId
        )
    }

    private var templateCnt = 0
    private var maxSpatialId = 0
    private var maxTemporalId = 0

    @SuppressFBWarnings(
        value = ["SF_SWITCH_NO_DEFAULT"],
        justification = "Artifact of generated Kotlin code."
    )
    private fun readTemplateLayers() {
        var temporalId = 0
        var spatialId = 0

        var nextLayerIdc: Int
        do {
            templateInfo.add(templateCnt, TemplateFrameInfo(spatialId, temporalId))
            templateCnt++
            nextLayerIdc = reader.bits(2)
            if (nextLayerIdc == 1) {
                temporalId++
                if (maxTemporalId < temporalId) {
                    maxTemporalId = temporalId
                }
            } else if (nextLayerIdc == 2) {
                temporalId = 0
                spatialId++
            }
        } while (nextLayerIdc != 3)

        check(templateInfo.size == templateCnt)

        maxSpatialId = spatialId
    }

    private fun readTemplateDtis() {
        for (templateIndex in 0 until templateCnt) {
            for (dtIndex in 0 until dtCnt) {
                templateInfo[templateIndex].dti.add(dtIndex, DTI.fromInt(reader.bits(2)))
            }
        }
    }

    private fun readFrameDtis(): List<DTI> {
        return List(templateDependencyStructure!!.decodeTargetCount) {
            DTI.fromInt(reader.bits(2))
        }
    }

    private fun readTemplateFdiffs() {
        for (templateIndex in 0 until templateCnt) {
            var fdiffCnt = 0
            var fdiffFollowsFlag = reader.bitAsBoolean()
            while (fdiffFollowsFlag) {
                val fdiffMinusOne = reader.bits(4)
                templateInfo[templateIndex].fdiff.add(fdiffCnt, fdiffMinusOne + 1)
                fdiffCnt++
                fdiffFollowsFlag = reader.bitAsBoolean()
            }
            check(fdiffCnt == templateInfo[templateIndex].fdiffCnt)
        }
    }

    private fun readFrameFdiffs(): List<Int> {
        return buildList {
            var nextFdiffSize = reader.bits(2)
            while (nextFdiffSize != 0) {
                val fdiffMinus1 = reader.bits(4 * nextFdiffSize)
                add(fdiffMinus1 + 1)
                nextFdiffSize = reader.bits(2)
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
                check(templateInfo[templateIndex].chains.size == chainCount)
            }
        }
    }

    private fun readFrameChains(): List<Int> {
        return List(templateDependencyStructure!!.chainCount) {
            reader.bits(8)
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
        check(decodeTargetLayers.size == dtCnt)
    }

    private fun readRenderResolutions() {
        for (spatialId in 0..maxSpatialId) {
            val widthMinus1 = reader.bits(16)
            val heightMinus1 = reader.bits(16)
            maxRenderResolutions.add(spatialId, Resolution(widthMinus1 + 1, heightMinus1 + 1))
        }
    }
}

open class FrameInfo(
    val spatialId: Int,
    val temporalId: Int,
    open val dti: List<DTI>,
    open val fdiff: List<Int>,
    open val chains: List<Int>
) : JSONAware {
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

    override fun toString(): String {
        return "spatialId=$spatialId, temporalId=$temporalId, dti=$dti, fdiff=$fdiff, chains=$chains"
    }

    override fun toJSONString(): String {
        return OrderedJsonObject().apply {
            put("spatialId", spatialId)
            put("temporalId", temporalId)
            put("dti", dti)
            put("fdiff", fdiff)
            put("chains", chains)
        }.toJSONString()
    }

    /** Whether the frame has a dependency on a frame earlier than this "picture", the other frames of this
     * temporal moment.  If it doesn't, it's probably part of a keyframe, and not part of the regular structure.
     * Note this makes assumptions about the scalability structure.
     */
    fun hasInterPictureDependency(): Boolean = fdiff.any { it > spatialId }

    val dtisPresent: List<Int>
        get() = dti.withIndex().filter { (_, dti) -> dti != DTI.NOT_PRESENT }.map { (i, _) -> i }
}

/* The only thing this changes from its parent class is to make the lists mutable, so the parent equals() is fine. */
@SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
class TemplateFrameInfo(
    spatialId: Int,
    temporalId: Int,
    override val dti: MutableList<DTI> = mutableListOf(),
    override val fdiff: MutableList<Int> = mutableListOf(),
    override val chains: MutableList<Int> = mutableListOf()
) : FrameInfo(spatialId, temporalId, dti, fdiff, chains)

class DecodeTargetLayer(
    val spatialId: Int,
    val temporalId: Int
) : JSONAware {
    override fun toJSONString(): String {
        return OrderedJsonObject().apply {
            put("spatialId", spatialId)
            put("temporalId", temporalId)
        }.toJSONString()
    }
}

data class Resolution(
    val width: Int,
    val height: Int
) : JSONAware {
    override fun toJSONString(): String {
        return OrderedJsonObject().apply {
            put("width", width)
            put("height", height)
        }.toJSONString()
    }
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

    fun toShortString(): String {
        return when (this) {
            NOT_PRESENT -> "N"
            DISCARDABLE -> "D"
            SWITCH -> "S"
            REQUIRED -> "R"
        }
    }
}

fun List<DTI>.toShortString(): String {
    return joinToString(separator = "") { it.toShortString() }
}

class Av1DependencyException(msg: String) : RuntimeException(msg)

fun <T> List<T>.toIndexedMap(): Map<Int, T> = mapIndexed { i, t -> i to t }.toMap()
