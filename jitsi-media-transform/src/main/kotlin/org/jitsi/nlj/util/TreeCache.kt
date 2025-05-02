/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import java.util.*

/**
 * Implements a cache based on integer values, optimized for sparse values, such that you can find the most
 * recent cached value before a specific value.
 *
 * The intended use case is AV1 Dependency Descriptor history.
 */
open class TreeCache<T>(
    private val minSize: Int
) {
    private val map = TreeMap<Long, T>()

    private var highestIndex = -1L

    fun insert(index: Long, value: T) {
        map[index] = value

        updateState(index)
    }

    fun getEntryBefore(index: Long): Map.Entry<Long, T>? {
        updateState(index)
        return map.floorEntry(index)
    }

    fun get(index: Long): T? = map[index]

    private fun updateState(index: Long) {
        if (highestIndex < index) {
            highestIndex = index
        }

        /* Keep at most one entry older than highestIndex - minSize. */
        val headMap = map.headMap(highestIndex - minSize)
        if (headMap.size > 1) {
            val last = headMap.keys.last()
            headMap.keys.removeIf { it < last }
        }
    }

    val size
        get() = map.size
}
