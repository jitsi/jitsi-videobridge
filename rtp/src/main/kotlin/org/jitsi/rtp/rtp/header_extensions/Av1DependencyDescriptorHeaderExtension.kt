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

/**
 * The subset of the fields of an AV1 Dependency Descriptor that can be parsed statelessly.
 */
open class Av1DependencyDescriptorStatelessSubset(
    val startOfFrame: Boolean,
    val endOfFrame: Boolean,
    val frameDependencyTemplateId: Int,
    val frameNumber: Int,

    val newTemplateDependencyStructure: Av1TemplateDependencyStructure?,
)

/**
 * The AV1 Dependency Descriptor header extension, as defined in https://aomediacodec.github.io/av1-rtp-spec/#appendix
 */
class Av1DependencyDescriptorHeaderExtension(
    startOfFrame: Boolean,
    endOfFrame: Boolean,
    frameDependencyTemplateId: Int,
    frameNumber: Int,

    newTemplateDependencyStructure: Av1TemplateDependencyStructure?,

    val activeDecodeTargetsBitmask: Int?,

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
) {
    val frameInfo: FrameInfo
        get() {
            val templateIndex = (frameDependencyTemplateId + 64 - structure.templateIdOffset) % 64
            if (templateIndex >= structure.templateCount) {
                val maxTemplate = (structure.templateIdOffset + structure.templateCount - 1) % 64
                throw Av1DependencyException(
                    "Invalid template index $frameDependencyTemplateId. " +
                        "Should be in range ${structure.templateIdOffset} .. $maxTemplate. " +
                        "Missed a keyframe?"
                )
            }
            val templateVal = structure.templateInfo[templateIndex]

            return FrameInfo(
                spatialId = templateVal.spatialId,
                temporalId = templateVal.temporalId,
                dti = customDtis ?: templateVal.dti,
                fdiff = customFdiffs ?: templateVal.fdiff,
                chains = customChains ?: templateVal.chains
            )
        }
}

/**
 * The template information about a stream described by AV1 dependency descriptors.  This is carried in the
 * first packet of a codec video sequence (i.e. the first packet of a keyframe), and is necessary to interpret
 * dependency descriptors carried in subsequent packets of the sequence.
 */
class Av1TemplateDependencyStructure(
    val templateIdOffset: Int,
    val templateInfo: List<FrameInfo>,
    val decodeTargetInfo: List<DecodeTargetInfo>,
    val maxRenderResolutions: List<Resolution>
) {
    val templateCount
        get() = templateInfo.size

    val decodeTargetCount
        get() = decodeTargetInfo.size

    val chainCount: Int =
        templateInfo.first().chains.size

    init {
        check(templateInfo.all { it.chains.size == chainCount }) {
            "Templates have inconsistent chain sizes"
        }
    }
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
     */
    fun parseStateless(): Av1DependencyDescriptorStatelessSubset {
        reset()
        readMandatoryDescriptorFields()

        if (length > 3) {
            val templateDependencyStructurePresent = reader.bitAsBoolean()

            /* activeDecodeTargetsPresent, customDtisFlag, customFdiffsFlag, and customChainsFlag;
             * none of these fields are parseable statelessly.
             */
            reader.skipbits(4)

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
            activeDecodeTargetsBitmask = reader.bits(8)
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
    private val decodeTargetInfo = mutableListOf<DecodeTargetInfo>()
    private val maxRenderResolutions = mutableListOf<Resolution>()

    private var dtCnt = 0

    private fun resetDependencyStructureInfo() {
        /* These fields are assembled incrementally when parsing a dependency structure; reset them
         * in case we're running a parser more than once.
         */
        templateCnt = 0
        templateInfo.clear()
        decodeTargetInfo.clear()
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
            decodeTargetInfo.toList(),
            maxRenderResolutions.toList()
        )
    }

    private var templateCnt = 0
    private var maxSpatialId = 0

    @SuppressFBWarnings(
        value = ["SF_SWITCH_NO_DEFAULT"],
        justification = "Artifact of generated Kotlin code."
    )
    private fun readTemplateLayers() {
        var temporalId = 0
        var spatialId = 0
        var maxTemporalId = 0

        var nextLayerIdc: Int
        do {
            templateInfo.add(templateCnt, TemplateFrameInfo(spatialId, temporalId))
            templateCnt++
            nextLayerIdc = reader.bits(2)
            if (nextLayerIdc == 1) {
                temporalId++
                if (maxTemporalId > temporalId) {
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
                decodeTargetInfo.add(dtIndex, DecodeTargetInfo(reader.ns(chainCount)))
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
            decodeTargetInfo[dtIndex].spatialId = spatialId
            decodeTargetInfo[dtIndex].temporalId = temporalId
        }
        check(decodeTargetInfo.size == dtCnt)
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
) {
    val fdiffCnt
        get() = fdiff.size
}

class TemplateFrameInfo(
    spatialId: Int,
    temporalId: Int,
    override val dti: MutableList<DTI> = mutableListOf(),
    override val fdiff: MutableList<Int> = mutableListOf(),
    override val chains: MutableList<Int> = mutableListOf()
) : FrameInfo(spatialId, temporalId, dti, fdiff, chains)

class DecodeTargetInfo(
    val protectedBy: Int
) {
    /** Todo: only want to be able to set these from the constructor */
    var spatialId: Int = -1
    var temporalId: Int = -1
}

data class Resolution(
    val width: Int,
    val height: Int
)

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
}

class Av1DependencyException(msg: String) : RuntimeException(msg)
