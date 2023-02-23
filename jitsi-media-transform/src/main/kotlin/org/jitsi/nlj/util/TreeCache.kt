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
    private val map = TreeMap<Int, T>()

    private var highestIndex = -1

    fun insert(index: Int, value: T) {
        map[index] = value

        updateState(index)
    }

    fun getValueBefore(index: Int): T? {
        updateState(index)
        return map.floorEntry(index)?.value
    }

    private fun updateState(index: Int) {
        if (highestIndex < index) {
            highestIndex = index
        }

        /* Purge entries older than highestIndex - minSize, but always keep at least one entry. */
        if (map.size > 1) {
            val last = map.keys.last()
            map.keys.removeIf { key -> key < last && key < highestIndex - minSize }
        }
    }
}
