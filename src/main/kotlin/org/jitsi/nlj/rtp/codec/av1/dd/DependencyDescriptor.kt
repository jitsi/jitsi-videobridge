package org.jitsi.nlj.rtp.codec.av1.dd

data class DependencyDescriptor(
    var firstPacketInFrame: Boolean = true,
    var lastPacketInFrame: Boolean = true,
    var frameNumber: Int = 0,
    var frameDependencies: FrameDependencyTemplate? = null,
    var resolution: Resolution? = null,
    // uint32_t
    var activeDecodeTargetsBitmask: Int? = null,
    var attachedStructure: FrameDependencyStructure? = null
) {
    companion object {
        const val kMaxSpatialIds = 4
        const val kMaxTemporalIds = 8
        const val kMaxDecodeTargets = 32
        const val kMaxTemplates = 64
    }
}
