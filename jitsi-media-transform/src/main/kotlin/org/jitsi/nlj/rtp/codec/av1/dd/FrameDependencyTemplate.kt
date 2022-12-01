package org.jitsi.nlj.rtp.codec.av1.dd

data class FrameDependencyTemplate(
    var spatialId: Int = 0,
    var temporalId: Int = 0,
    var decodeTargetIndications: List<DecodeTargetIndication> = emptyList(),
    var frameDiffs: List<Int> = emptyList(),
    var chainDiffs: List<Int> = emptyList()
) {
    fun copy(): FrameDependencyTemplate {
        return FrameDependencyTemplate(
            spatialId = spatialId,
            temporalId = temporalId,
            decodeTargetIndications = ArrayList(decodeTargetIndications),
            frameDiffs = ArrayList(frameDiffs),
            chainDiffs = ArrayList(chainDiffs)
        )
    }
}
