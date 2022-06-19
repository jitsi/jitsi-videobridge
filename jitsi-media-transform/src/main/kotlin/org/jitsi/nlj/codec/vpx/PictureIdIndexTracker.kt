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
package org.jitsi.nlj.codec.vpx

/** Like [org.jitsi.nlj.util.Rfc3711IndexTracker], but for VP8/VP9 picture IDs (so with a rollover
 * of 0x8000).
 */
class PictureIdIndexTracker {
    private var roc = 0
    private var highestSeqNumReceived = -1
    private fun getIndex(seqNum: Int, updateRoc: Boolean): Int {
        if (highestSeqNumReceived == -1) {
            if (updateRoc) {
                highestSeqNumReceived = seqNum
            }
            return seqNum
        }
        val delta = VpxUtils.getExtendedPictureIdDelta(seqNum, highestSeqNumReceived)
        val v =
            if (delta < 0 && highestSeqNumReceived < seqNum) {
                roc - 1
            } else if (delta > 0 && seqNum < highestSeqNumReceived) {
                (roc + 1).also {
                    if (updateRoc) roc = it
                }
            } else {
                roc
            }
        if (updateRoc && delta > 0) {
            highestSeqNumReceived = seqNum
        }
        return 0x8000 * v + seqNum
    }

    fun update(seq: Int) = getIndex(seq, updateRoc = true)

    fun interpret(seq: Int) = getIndex(seq, updateRoc = false)

    /** Force this sequence to be interpreted as the new highest, regardless
     * of its rollover state.
     */
    fun resetAt(seq: Int) {
        val delta = VpxUtils.getExtendedPictureIdDelta(seq, highestSeqNumReceived)
        if (delta < 0) {
            roc++
            highestSeqNumReceived = seq
        }
        getIndex(seq, true)
    }
}
