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

/** Windowed minimum filter,
 * based on WebRTC rtc_base/numerics/windowed_min_filter.h in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * @param defaultValue what [getMin] returns if no value has been inserted. In WebRTC this is the default value of
 * the type.
 */
class WindowedMinFilter<V : Comparable<V>>(
    windowLength: Int,
    private val defaultValue: V
) {
    private val maxSize = windowLength

    init {
        require(windowLength > 1)
    }

    fun insert(value: V) {
        if (minValues.isNotEmpty()) {
            if (minValues.first().index == index) {
                // Min value is too old.
                minValues.removeFirst()
            }
            // If value <= min_values_.front().value, value is the minimum value and
            // we can forget all other. The alternative had been to always
            // check the back value, but then we would also have to check for
            // empty.
            if (minValues.first().value >= value) {
                minValues.clear()
            } else {
                while (minValues.last().value >= value) {
                    minValues.removeLast()
                }
            }
        }
        check(minValues.size < maxSize)
        minValues.addLast(ValueAndIndex(value, index))
        index = (index + 1) % maxSize
    }

    // Returns the min value within the window. If no value has been inserted,
    // returns the default value.
    fun getMin(): V = if (minValues.isEmpty()) defaultValue else minValues.first().value

    fun reset() {
        minValues.clear()
        index = 0
    }

    private class ValueAndIndex<V>(val value: V, val index: Int)

    private var index = 0
    private val minValues = ArrayDeque<ValueAndIndex<V>>()
}
