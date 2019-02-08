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

class Rfc3711IndexTracker {
    var roc: Int = 0
        private set
    private var highestSeqNumReceived = -1

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given seqNum.
     * NOTE that this method must be called for all 'received' sequence numbers so that it may keep
     * its rollover counter accurate
     */
    fun update(seqNum: Int): Int {
        if (highestSeqNumReceived == -1)
        {
            highestSeqNumReceived = seqNum
            return seqNum
        }

        val v = when {
            seqNum rolledOverTo highestSeqNumReceived -> {
                // Seq num was from the previous roc value
                roc - 1
            }
            highestSeqNumReceived rolledOverTo seqNum -> {
                // We've rolled over, so update the roc value and
                // the highestSeqNumReceived and return the new roc
                // to be used for v
                highestSeqNumReceived = seqNum
                ++roc
            }
            else -> roc
        }

        return 0x1_0000 * v + seqNum
    }
}