package org.jitsi.nlj.rtp.codec.av1.dd

class DependencyDescriptorReader(
    private val rawData: BytesView,
    private var structure: FrameDependencyStructure?
) {
    private var frameDependencyTemplateId = 0
    private var activeDecodeTargetsPresentFlag = false
    private var customDTIsFlag = false
    private var customFDiffsFlag = false
    private var customChainsFlag = false

    fun parse(): Pair<DependencyDescriptor, FrameDependencyStructure> {
        val descriptor = DependencyDescriptor()

        readMandatoryFields(descriptor)

        if (rawData.length > 3) {
            readExtendedFields(descriptor)
        } else {
            customDTIsFlag = false
            customFDiffsFlag = false
            customChainsFlag = false
        }

        val nonNullStructure = checkNotNull(descriptor.attachedStructure ?: structure)

        if (activeDecodeTargetsPresentFlag) {
            descriptor.activeDecodeTargetsBitmask = rawData.readInt(nonNullStructure.numDecodeTargets)
        }

        readFrameDependencyDefinition(descriptor, nonNullStructure)

        return descriptor to nonNullStructure
    }

    private fun readExtendedFields(descriptor: DependencyDescriptor) {
        val templateDependencyStructurePresentFlag = rawData.readBoolean()
        activeDecodeTargetsPresentFlag = rawData.readBoolean()
        customDTIsFlag = rawData.readBoolean()
        customFDiffsFlag = rawData.readBoolean()
        customChainsFlag = rawData.readBoolean()

        if (templateDependencyStructurePresentFlag) {
            readTemplateDependencyStructure(descriptor)
            val attachedStructure = checkNotNull(descriptor.attachedStructure)
            descriptor.activeDecodeTargetsBitmask = (1 shl attachedStructure.numDecodeTargets) - 1
        }
    }

    private fun readTemplateDependencyStructure(descriptor: DependencyDescriptor) {
        descriptor.attachedStructure = FrameDependencyStructure().apply {
            structureId = rawData.readInt(6)
            numDecodeTargets = rawData.readInt(5) + 1
            readTemplateLayers(this)
            readTemplateDTIs(this)
            readTemplateFDiffs(this)
            readTemplateChains(this)

            val hasResolutions = rawData.readBoolean()
            if (hasResolutions)
                readResolutions(this)
        }
    }

    private fun readTemplateLayers(structure: FrameDependencyStructure) {
        val templates = mutableListOf<FrameDependencyTemplate>()
        var temporalId = 0
        var spatialId = 0
        var nextLayerIdc: NextLayerIdc

        do {
            check(templates.size < DependencyDescriptor.kMaxTemplates) { "template overflow" }

            templates.add(
                FrameDependencyTemplate().apply {
                    this.temporalId = temporalId
                    this.spatialId = spatialId
                }
            )

            nextLayerIdc = NextLayerIdc.parse(rawData.readInt(2))
            if (nextLayerIdc == NextLayerIdc.NEXT_TEMPORAL_LAYER) {
                temporalId++
                check(temporalId < DependencyDescriptor.kMaxTemporalIds) { "temporal id $temporalId overflow" }
            } else if (nextLayerIdc == NextLayerIdc.NEXT_SPATIAL_LAYER) {
                temporalId = 0
                spatialId++
                check(spatialId < DependencyDescriptor.kMaxSpatialIds) { "spatial id $spatialId overflow" }
            }
        } while (nextLayerIdc != NextLayerIdc.NO_MORE_TEMPLATES)

        structure.templates = templates.toList()
    }

    private fun readTemplateDTIs(structure: FrameDependencyStructure) {
        structure.templates.forEach { currentTemplate ->
            currentTemplate.decodeTargetIndications = (0 until structure.numDecodeTargets).map {
                DecodeTargetIndication.parse(rawData.readInt(2))
            }
        }
    }

    private fun readTemplateFDiffs(structure: FrameDependencyStructure) {
        for (currentTemplate in structure.templates) {
            val frameDiffs = mutableListOf<Int>()
            var fDiffFollows = rawData.readBoolean()
            while (fDiffFollows) {
                val fDiffMinusOne = rawData.readInt(4)
                frameDiffs.add(fDiffMinusOne + 1)
                fDiffFollows = rawData.readBoolean()
            }
            currentTemplate.frameDiffs = frameDiffs.toList()
        }
    }

    private fun readTemplateChains(structure: FrameDependencyStructure) {
        structure.numChains = rawData.readNonSymmetric(structure.numDecodeTargets + 1)
        if (structure.numChains == 0) {
            return
        }

        structure.decodeTargetProtectedByChain = (0 until structure.numDecodeTargets).map {
            rawData.readNonSymmetric(structure.numChains)
        }

        structure.templates.forEach { frameTemplate ->
            frameTemplate.chainDiffs = (0 until structure.numChains).map {
                rawData.readInt(4)
            }
        }
    }

    private fun readResolutions(structure: FrameDependencyStructure) {
        // The way templates are bitpacked, they are always ordered by spatial_id.
        val spatialLayers = structure.templates.last().spatialId + 1
        structure.resolutions = (0 until spatialLayers).map {
            val widthMinusOne = rawData.readInt(16)
            val heightMinusOne = rawData.readInt(16)
            Resolution(widthMinusOne + 1, heightMinusOne + 1)
        }
    }

    private fun readFrameDependencyDefinition(
        descriptor: DependencyDescriptor,
        structure: FrameDependencyStructure
    ) {
        val templateIndex =
            (frameDependencyTemplateId + DependencyDescriptor.kMaxTemplates - structure.structureId) %
                DependencyDescriptor.kMaxTemplates

        check(templateIndex < structure.templates.size) { "template index $templateIndex overflow" }

        // Copy all the fields from the matching template
        val descriptorFrameDependencies = structure.templates[templateIndex].copy()
        descriptor.frameDependencies = descriptorFrameDependencies

        if (customDTIsFlag)
            readFrameDTIs(descriptorFrameDependencies, structure)
        if (customFDiffsFlag)
            readFrameFDiffs(descriptorFrameDependencies)
        if (customChainsFlag)
            readFrameChains(descriptorFrameDependencies, structure)

        if (structure.resolutions.isEmpty()) {
            descriptor.resolution = null
        } else {
            // Format guarantees that if there were resolutions in the last structure, then each spatial layer got one.
            check(descriptorFrameDependencies.spatialId <= structure.resolutions.size)
            descriptor.resolution = structure.resolutions[descriptorFrameDependencies.spatialId]
        }
    }

    private fun readFrameDTIs(
        frameDependencies: FrameDependencyTemplate,
        structure: FrameDependencyStructure
    ) {
        check(frameDependencies.decodeTargetIndications.size == structure.numDecodeTargets)

        frameDependencies.decodeTargetIndications = frameDependencies.decodeTargetIndications.map {
            DecodeTargetIndication.parse(rawData.readInt(2))
        }
    }

    private fun readFrameFDiffs(frameDependencies: FrameDependencyTemplate) {
        val frameDiffs = mutableListOf<Int>()
        var nextFDiffSize = rawData.readInt(2)
        while (nextFDiffSize > 0) {
            val fDiffMinusOne = rawData.readInt(4 * nextFDiffSize)
            frameDiffs.add(fDiffMinusOne + 1)
            nextFDiffSize = rawData.readInt(2)
        }
        frameDependencies.frameDiffs = frameDiffs
    }

    private fun readFrameChains(frameDependencies: FrameDependencyTemplate, structure: FrameDependencyStructure) {
        check(frameDependencies.chainDiffs.size == structure.numChains)

        frameDependencies.chainDiffs = frameDependencies.chainDiffs.map { rawData.readInt(8) }
    }

    // 3 bytes
    private fun readMandatoryFields(descriptor: DependencyDescriptor) {
        descriptor.firstPacketInFrame = rawData.readBoolean()
        descriptor.lastPacketInFrame = rawData.readBoolean()
        frameDependencyTemplateId = rawData.readInt(6)
        descriptor.frameNumber = rawData.readInt(16)
    }
}
