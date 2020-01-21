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

package org.jitsi.videobridge.util

import java.lang.Integer.max
import org.jitsi.nlj.util.floorMod
import java.lang.Integer.min

/**
 * Implements a fixed-sized tracker based on a pre-filled array. The main use-case is the frame projection tracker.
 *
 * @author Jonathan Lennox, based on [org.jitsi.nlj.util.ArrayCache] by Boris Grozev
 */
open class ArrayTracker<T>(
    val size: Int
) {
    private val cache: Array<Container> = Array(size) { Container() }
    protected val syncRoot = Any()
    /**
     * The index in [cache] where the item with the highest index is stored.
     */
    private var head = -1

    protected val lastIndex: Int
        get() = if (head == -1) -1 else cache[head].index

    protected var firstIndex: Int = -1
        private set

    val empty: Boolean
        get() = (head == -1)

    var numTracked: Int = 0
        private set

    /**
     * Inserts an item with a specific index in the cache. Stores a copy.
     */
    fun insertItem(item: T, index: Int): Boolean {
        val diff = if (head == -1) -1 else index - cache[head].index
        val position = when {
            head == -1 -> {
                head = 0
                head
            }
            diff <= -size -> {
                // The item is too old
                return false
            }
            diff < 0 -> (head + diff) floorMod size
            else -> {
                val newHead = (head + diff) floorMod size
                if (diff >= size) {
                    flush()
                } else {
                    flushBetween(head, newHead)
                }
                head = newHead
                head
            }
        }

        if (firstIndex == -1 || firstIndex > index) {
            firstIndex = index
        }

        cache[position].item?.let { numTracked--; discardItem(it) }
        cache[position].item = item
        cache[position].index = index
        numTracked++
        return true
    }

    /**
     * Called when an item in the cache is replaced/discarded.
     */
    protected open fun discardItem(item: T) {}

    /**
     * Gets an item from the cache with a given index. Returns 'null' if there is no item with this index in the cache.
     * The item is wrapped in a [Container] to allow access to the time it was added to the cache, and we provide a
     * copy.
     */
    fun getContainer(index: Int): Container? {
        if (head == -1) {
            // Not initialized (empty).
            return null
        }

        val diff = index - cache[head].index
        if (diff > 0) {
            // The requested index is newer than the last index we have.
            return null
        }

        val position = (head + diff) floorMod size
        if (cache[position].index == index) {
            return cache[position]
        }
        return null
    }

    fun findContainerAfter(index: Int, predicate: (T) -> Boolean): Container? {
        if (head == -1) {
            // Not initialized (empty).
            return null
        }

        val diff = index - cache[head].index
        if (diff > 0) {
            // The requested index is newer than the last index we have.
            return null
        }

        val searchStartIndex = max(index + 1, max(lastIndex - size, firstIndex))

        for (i in searchStartIndex..lastIndex) {
            val iDiff = i - cache[head].index
            val position = (head + iDiff) floorMod size
            if (cache[position].index == i) {
                // We maintain the invariant [index==-1 iff item==null]
                if (predicate(cache[position].item!!)) {
                    return cache[position]
                }
            }
        }
        return null
    }

    fun findContainerBefore(index: Int, predicate: (T) -> Boolean): Container? {
        if (head == -1) {
            // Not initialized (empty).
            return null
        }

        val diff = cache[head].index - index
        if (diff >= size) {
            // The requested index is older than the earliest index we have.
            return null
        }

        val searchStartIndex = min(index - 1, lastIndex)
        val searchEndIndex = max(lastIndex - size, firstIndex)

        for (i in searchStartIndex downTo searchEndIndex) {
            val iDiff = i - cache[head].index
            val position = (head + iDiff) floorMod size
            if (cache[position].index == i) {
                // We maintain the invariant [index==-1 iff item==null]
                if (predicate(cache[position].item!!)) {
                    return cache[position]
                }
            }
        }
        return null
    }

    /**
     * Removes all items stored in the cache, calling [discardItem] for each one.
     */
    fun flush() {
        for (container in cache) {
            with(container) {
                index = -1
                item?.let { discardItem(it) }
                item = null
            }
        }
        head = -1
        numTracked = 0
    }

    /**
     * Flush entries between oldHead and newHead (non-inclusively)
     */
    private fun flushBetween(oldHead: Int, newHead: Int) {
        if (newHead == (oldHead + 1) floorMod size)
            return
        val ranges = if (oldHead < newHead) {
            setOf(oldHead + 1..newHead - 1)
        } else {
            setOf(oldHead + 1..size - 1, 0..newHead - 1)
        }
        for (range in ranges) {
            for (i in range) {
                with(cache[i]) {
                    index = -1
                    item?.let { numTracked--; discardItem(it) }
                    item = null
                }
            }
        }
    }

    inner class Container(
        var item: T? = null,
        var index: Int = -1
    )
}
