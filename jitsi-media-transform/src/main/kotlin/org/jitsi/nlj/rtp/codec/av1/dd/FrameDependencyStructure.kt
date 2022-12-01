package org.jitsi.nlj.rtp.codec.av1.dd

data class FrameDependencyStructure(
    /**
     * template_id_offset
     */
    var structureId: Int = 0,
    /**
     * DtCnt
     */
    var numDecodeTargets: Int = 0,
    var numChains: Int = 0,
    // If chains are used (num_chains > 0), maps decode target index into index of
    // the chain protecting that target.
    var decodeTargetProtectedByChain: List<Int> = emptyList(),
    var resolutions: List<Resolution> = emptyList(),
    var templates: List<FrameDependencyTemplate> = emptyList()
)
