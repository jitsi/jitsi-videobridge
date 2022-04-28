package org.jitsi.nlj.rtp.codec.av1.dd

enum class DecodeTargetIndication(val id: Int) {
    // DecodeTargetInfo symbol '-'
    NOT_PRESENT(0),
    // DecodeTargetInfo symbol 'D'
    DISCARDABLE(1),
    // DecodeTargetInfo symbol 'S'
    SWITCH(2),
    // DecodeTargetInfo symbol 'R'
    REQUIRED(3);

    companion object {
        private val map = values().associateBy { it.id }

        fun parse(id: Int): DecodeTargetIndication {
            return map.getValue(id)
        }
    }
}
