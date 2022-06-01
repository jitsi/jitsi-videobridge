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

package org.jitsi.nlj.util

import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.rolledOverTo

class Rfc3711IndexTracker {
    var roc: Int = 0
        private set
    private var highestSeqNumReceived = -1

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given seqNum, updating our ROC if we roll over.
     * NOTE that this method must be called for all 'received' sequence numbers so that it may keep
     * its rollover counter accurate
     */
    fun update(seqNum: Int): Int {
        return getIndex(seqNum, true)
    }

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given [seqNum]. If [updateRoc] is [true] and we've rolled over, updates our ROC.
     */
    private fun getIndex(seqNum: Int, updateRoc: Boolean): Int {
        if (highestSeqNumReceived == -1) {
            if (updateRoc) {
                highestSeqNumReceived = seqNum
            }
            return seqNum
        }

        val v = when {
            seqNum rolledOverTo highestSeqNumReceived -> {
                // Seq num was from the previous roc value
                roc - 1
            }
            highestSeqNumReceived rolledOverTo seqNum -> {
                // We've rolled over, so update the roc in place if updateRoc
                // is set, otherwise return the right value (our current roc
                // + 1)
                if (updateRoc) {
                    ++roc
                } else {
                    roc + 1
                }
            }
            else -> roc
        }

        if (updateRoc && (seqNum isNewerThan highestSeqNumReceived)) {
            highestSeqNumReceived = seqNum
        }

        return 0x1_0000 * v + seqNum
    }

    /**
     * Interprets an RTP sequence number in the context of the highest sequence number received. Returns the index
     * which corresponds to the packet, but does not update the ROC.
     */
    fun interpret(seqNum: Int): Int {
        return getIndex(seqNum, false)
    }
}
