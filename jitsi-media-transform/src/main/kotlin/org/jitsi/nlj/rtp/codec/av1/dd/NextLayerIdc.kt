package org.jitsi.nlj.rtp.codec.av1.dd

enum class NextLayerIdc(val id: Int) {
    SAME_LAYER(0),
    NEXT_TEMPORAL_LAYER(1),
    NEXT_SPATIAL_LAYER(2),
    NO_MORE_TEMPLATES(3);

    companion object {
        private val map = values().associateBy { it.id }

        fun parse(id: Int): NextLayerIdc {
            return map.getValue(id)
        }
    }
}
