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

package org.jitsi.videobridge.cc.allocation

import org.jitsi.utils.OrderedJsonObject

/**
 * A Map of Endpoint IDs to their receiver video constraints.  Tracks the max height of all
 * of those constraints as new constraints are added and removed.
 */
class ReceiverConstraintsMap {
    private val map = mutableMapOf<String, VideoConstraints>()
    private val lock = Any()
    var maxHeight: Int = 0
        private set

    fun put(key: String, value: VideoConstraints): VideoConstraints? {
        synchronized(lock) {
            if (maxHeight < value.maxHeight) {
                maxHeight = value.maxHeight
            }
            return map.put(key, value)
        }
    }

    fun remove(key: String): VideoConstraints? {
        synchronized(lock) {
            return map.remove(key)?.also { removed ->
                if (removed.maxHeight == maxHeight) {
                    maxHeight = findNextMax(maxHeight)
                }
            }
        }
    }

    /**
     * Given the previous max, go through the constraints until we either:
     * 1) Find a maxHeight equal to the old one or
     * 2) Go through the whole list, and track the highest value we've seen.
     * Note: this method must be called with the lock held
     */
    private fun findNextMax(oldMaxHeight: Int): Int {
        var maxSeen = 0
        map.values.forEach { constraints ->
            if (constraints.maxHeight == oldMaxHeight) {
                return oldMaxHeight
            } else if (constraints.maxHeight > maxSeen) {
                maxSeen = constraints.maxHeight
            }
        }
        return maxSeen
    }

    fun getDebugState() = OrderedJsonObject().apply {
        put("maxHeight", maxHeight)
        put("constraints", map.toMap())
    }
}
