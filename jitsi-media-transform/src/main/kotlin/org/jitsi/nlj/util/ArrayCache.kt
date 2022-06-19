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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import java.lang.Integer.max
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implements a fixed-sized cache based on a pre-filled array. The main use-case is the outgoing RTP packet cache.
 *
 * @author Boris Grozev
 */
@SuppressFBWarnings(
    value = ["CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"],
    justification = "False positives."
)
open class ArrayCache<T>(
    val size: Int,
    private val cloneItem: (T) -> T,
    protected val synchronize: Boolean = true,
    /**
     * The function to use to clone items. The cache always saves copies of the items that are inserted.
     */
    protected val clock: Clock = Clock.systemUTC()
) : NodeStatsProducer {
    private val cache: Array<Container> = Array(size) { Container() }
    protected val syncRoot = Any()
    /**
     * The index in [cache] where the item with the highest index is stored.
     */
    private var head = -1

    var numInserts = 0
    var numOldInserts = 0
    private val _numHits = AtomicInteger()
    private val _numMisses = AtomicInteger()
    val numHits
        get() = _numHits.get()
    val numMisses
        get() = _numMisses.get()
    val hitRate
        get() = _numHits.get() * 1.0 / max(1, _numHits.get() + _numMisses.get())

    protected val lastIndex: Int
        get() = if (head == -1) -1 else cache[head].index

    val empty: Boolean
        get() = (head == -1)

    /**
     * Inserts an item with a specific index in the cache. Stores a copy.
     */
    fun insertItem(item: T, index: Int, timeAdded: Long): Boolean =
        if (synchronize) {
            synchronized(syncRoot) {
                doInsert(item, index, timeAdded)
            }
        } else {
            doInsert(item, index, timeAdded)
        }

    /**
     * Inserts an item with a specific index in the cache, computing time
     * from [clock]. Stores a copy.
     */
    fun insertItem(item: T, index: Int): Boolean =
        insertItem(item, index, clock.millis())

    private fun doInsert(item: T, index: Int, timeAdded: Long): Boolean {
        val diff = if (head == -1) -1 else index - cache[head].index
        val position = when {
            head == -1 -> {
                head = 0
                head
            }
            diff <= -size -> {
                // The item is too old
                numOldInserts++
                return false
            }
            diff < 0 -> (head + diff) floorMod size
            else -> {
                head = (head + diff) floorMod size
                head
            }
        }

        numInserts++
        cache[position].item?.let { discardItem(it) }
        cache[position].item = cloneItem(item)
        cache[position].index = index
        cache[position].timeAdded = timeAdded
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
    @JvmOverloads
    fun getContainer(index: Int, shouldCloneItem: Boolean = true): Container? {
        val result = when {
            synchronize -> synchronized(syncRoot) {
                doGet(index, shouldCloneItem)
            }
            else -> doGet(index, shouldCloneItem)
        }

        result?.let { _numHits.incrementAndGet() } ?: _numMisses.incrementAndGet()
        return result
    }

    private fun doGet(index: Int, shouldCloneItem: Boolean): Container? {
        if (index < 0) {
            return null
        }
        if (head == -1) {
            // Not initialized (empty), or newer than head.
            return null
        }

        val diff = index - cache[head].index
        if (diff > 0) {
            // The requested index is newer than the last index we have.
            return null
        }

        val position = (head + diff) floorMod size
        if (cache[position].index == index) {
            return cache[position].clone(shouldCloneItem)
        }
        return null
    }

    /**
     * Checks whether the cache contains an item with a given index.
     */
    fun containsIndex(index: Int): Boolean {
        return if (synchronize) {
            synchronized(syncRoot) {
                doContains(index)
            }
        } else {
            doContains(index)
        }
    }

    private fun doContains(index: Int): Boolean {
        if (head == -1) {
            return false
        }

        val diff = index - cache[head].index
        if (diff > 0) {
            return false
        }

        val position = (head + diff) floorMod size
        return (cache[position].index == index)
    }

    /**
     * Updates the [timeAdded] value of an item with a particular index, if it is in the cache.
     */
    protected fun updateTimeAdded(index: Int, timeAdded: Long) =
        if (synchronize) {
            synchronized(syncRoot) {
                doUpdateTimeAdded(index, timeAdded)
            }
        } else {
            doUpdateTimeAdded(index, timeAdded)
        }

    private fun doUpdateTimeAdded(index: Int, timeAdded: Long) {
        if (head == -1 || index > cache[head].index) {
            return
        }
        val diff = cache[head].index - index
        val position = (head - diff) floorMod size
        if (cache[position].index == index) {
            cache[position].timeAdded = timeAdded
        }
    }

    /**
     * Iterates from the last index added to the cache down at most [size] elements. For each item, if it is in the
     * last 'window', calls [predicate] with the item.  If [predicate] returns [false] for any item, stops the
     * iteration and returns.  Note that if the caller must clone the item on their own if they want to keep or modify
     * it in any way.
     */
    fun forEachDescending(predicate: (T) -> Boolean) =
        if (synchronize) {
            synchronized(syncRoot) {
                doForEachDescending(predicate)
            }
        } else {
            doForEachDescending(predicate)
        }

    private fun doForEachDescending(predicate: (T) -> Boolean) {
        if (head == -1) return

        val indexRange = cache[head].index downTo max(cache[head].index - size, 1)
        for (i in 0 until size) {
            val position = (head - i) floorMod size
            if (cache[position].index in indexRange) {
                // We maintain the invariant [index==-1 iff item==null]
                if (!predicate(cache[position].item!!)) {
                    return
                }
            }
        }
    }

    /**
     * Removes all items stored in the cache, calling [discardItem] for each one.
     */
    fun flush() =
        if (synchronize) {
            synchronized(syncRoot) {
                doFlush()
            }
        } else {
            doFlush()
        }

    private fun doFlush() {
        for (container in cache) {
            with(container) {
                index = -1
                item?.let { discardItem(it) }
                item = null
                timeAdded = -1
            }
        }
        head = -1
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("ArrayCache").apply {
        addNumber("size", size)
        addNumber("numInserts", numInserts)
        addNumber("numOldInserts", numOldInserts)
        addNumber("numHits", numHits)
        addNumber("numMisses", numMisses)
        addNumber("numRequests", numHits + numMisses)
        addRatio("hitRate", "numHits", "numRequests", 1)
    }

    inner class Container(
        var item: T? = null,
        var index: Int = -1,
        var timeAdded: Long = -1
    ) {
        fun clone(shouldCloneItem: Boolean): Container {
            return Container(item?.let { if (shouldCloneItem) cloneItem(it) else it }, index, timeAdded)
        }
    }
}
