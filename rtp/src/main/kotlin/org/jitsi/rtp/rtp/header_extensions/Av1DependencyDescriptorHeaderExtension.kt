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

import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.BitReader

/**
 * The AV1 Dependency Descriptor header extension, as defined in https://aomediacodec.github.io/av1-rtp-spec/#appendix
 */
class Av1DependencyDescriptorHeaderExtension {
    val startOfFrame: Boolean
    val endOfFrame: Boolean
    val frameDependencyTemplateId: Int
    val frameNumber: Int

    val spatialId: Int
    val temporalId: Int

    val frameDtis: List<DTI>
    val frameFdiffs: List<Int>
    val frameChains: List<Int>

    val templateDependencyStructurePresent: Boolean
    val template: Av1DependencyTemplate

    val activeDecodeTargetsBitmask: Int?

    constructor(ext: RtpPacket.HeaderExtension, template: Av1DependencyTemplate?) :
        this(ext.buffer, ext.dataOffset, ext.dataLengthBytes, template)

    constructor(buffer: ByteArray, offset: Int, length: Int, tmpl: Av1DependencyTemplate?) {
        val reader = BitReader(buffer, offset, length)

        val activeDecodeTargetsPresent: Boolean

        val customDtisFlag: Boolean
        val customFdiffsFlag: Boolean
        val customChainsFlag: Boolean

        /* Mandatory descriptor fields */
        startOfFrame = reader.bitAsBoolean()
        endOfFrame = reader.bitAsBoolean()
        frameDependencyTemplateId = reader.bits(6)
        frameNumber = reader.bits(16)

        if (length > 3) {
            /* Extended descriptor fields */

            templateDependencyStructurePresent = reader.bitAsBoolean()
            activeDecodeTargetsPresent = reader.bitAsBoolean()
            customDtisFlag = reader.bitAsBoolean()
            customFdiffsFlag = reader.bitAsBoolean()
            customChainsFlag = reader.bitAsBoolean()

            if (templateDependencyStructurePresent) {
                this.template = Av1DependencyTemplate(reader)
            } else {
                checkNotNull(tmpl) { "No external template specified for non-first packet" }
                // TODO: we should have a cleaner way to determine that something isn't a first packet
                template = tmpl
            }
            val dtCnt = template.decodeTargetCount
            activeDecodeTargetsBitmask = if (activeDecodeTargetsPresent) {
                reader.bits(dtCnt)
            } else if (templateDependencyStructurePresent) {
                (1 shl dtCnt) - 1
            } else {
                null /* Not updated by this packet */
            }
        } else {
            /* No extended descriptor fields */
            templateDependencyStructurePresent = false
            checkNotNull(tmpl) { "No external template specified for non-first packet" }
            template = tmpl
            activeDecodeTargetsBitmask = null /* Not updated by this packet */
            customDtisFlag = false
            customFdiffsFlag = false
            customChainsFlag = false
        }

        /* Frame dependency definition */
        val templateIndex = (frameDependencyTemplateId + 64 - template.templateIdOffset) % 64
        check(templateIndex < template.templateCount) {
            val maxTemplate = (template.templateCount - 1 + 64 - template.templateIdOffset) % 64
            "Invalid template index $templateIndex. " +
                "Should be in range ${template.templateIdOffset} .. $maxTemplate. " +
                "Missed a keyframe?"
        }
        val templateVal = template.templateInfo[templateIndex]
        spatialId = templateVal.spatialId
        temporalId = templateVal.temporalId

        frameDtis = if (customDtisFlag) {
            List(template.decodeTargetCount) {
                DTI.fromInt(reader.bits(2))
            }
        } else {
            templateVal.dti
        }

        frameFdiffs = if (customFdiffsFlag) {
            buildList {
                var nextFdiffSize = reader.bits(2)
                while (nextFdiffSize != 0) {
                    val fdiffMinus1 = reader.bits(4 * nextFdiffSize)
                    add(fdiffMinus1 + 1)
                    nextFdiffSize = reader.bits(2)
                }
            }
        } else {
            templateVal.fdiff
        }

        frameChains = if (customChainsFlag) {
            List(template.chainCount) {
                reader.bits(8)
            }
        } else {
            templateVal.chains
        }
    }

    /**
     * The template information about a stream described by AV1 dependency descriptors.  This is carried in the
     * first packet of a codec video sequence (i.e. the first packet of a keyframe), and is necessary to interpret
     * dependency descriptiors carried in subsequent packets of the sequence.
     */
    class Av1DependencyTemplate {
        val templateIdOffset: Int
        val templateInfo = mutableListOf<FrameInfo>()
        val decodeTargetInfo = mutableListOf<DecodeTargetInfo>()
        val maxRenderResolutions = mutableListOf<Resolution>()

        val templateCount
            get() = templateInfo.size

        val decodeTargetCount
            get() = decodeTargetInfo.size

        val chainCount: Int

        constructor(reader: BitReader) {
            /* Template dependency structure */
            templateIdOffset = reader.bits(6)
            val dtCntMinusOne = reader.bits(5)

            val dtCnt = dtCntMinusOne + 1

            var nextLayerIdc: Int

            var templateCnt = 0
            val maxSpatialId: Int
            /* Template layers */
            run {
                var temporalId = 0
                var spatialId = 0
                var maxTemporalId = 0
                do {
                    templateInfo.add(templateCnt, FrameInfo(spatialId, temporalId))
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
                check(templateCount == templateCnt)
                maxSpatialId = spatialId

                /* Template DTIs */
                for (templateIndex in 0 until templateCnt) {
                    for (dtIndex in 0 until dtCnt) {
                        templateInfo[templateIndex].dti.add(dtIndex, DTI.fromInt(reader.bits(2)))
                    }
                }
            }

            /* Template fdiffs */
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

            /* Template chains */
            chainCount = reader.ns(dtCnt + 1)
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

            /* Decode target layers */
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
            check(this.decodeTargetCount == dtCnt)

            val resolutionsPresent = reader.bitAsBoolean()

            if (resolutionsPresent) {
                /* Render resolutions */
                for (spatialId in 0..maxSpatialId) {
                    val widthMinus1 = reader.bits(16)
                    val heightMinus1 = reader.bits(16)
                    maxRenderResolutions.add(spatialId, Resolution(widthMinus1 + 1, heightMinus1 + 1))
                }
            }
        }
    }

    class FrameInfo(
        val spatialId: Int,
        val temporalId: Int
    ) {
        val dti = ArrayList<DTI>()
        val fdiff = ArrayList<Int>()

        val fdiffCnt
            get() = fdiff.size

        val chains = ArrayList<Int>()
    }

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
}
