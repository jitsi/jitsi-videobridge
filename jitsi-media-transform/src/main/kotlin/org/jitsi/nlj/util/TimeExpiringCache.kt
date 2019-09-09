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

import java.time.Duration
import java.util.TreeMap
import org.jitsi.utils.TimeProvider

/**
 * A cache which holds an arbitrary [DataType] and stores it, as well
 * as the time at which it was added to the cache.  On updates, it will
 * check to prune itself to remove anything older than the configured
 * timeout.
 * NOTE: it's possible the pruning does not remove ALL elements older
 * than the configured timeout as we store the elements according to
 * the value of their [IndexType], not their insertion timestamp.
 * This is because retrieval is much handier when done via the value
 * of [IndexType].  This means that it's possible older values will
 * not be pruned when expected, but if the ordering of [IndexType]
 * roughly follows the insertion order, it should not be too bad.
 */
class TimeExpiringCache<IndexType, DataType>(
    /**
     * The amount of time after which an element should be pruned from the cache
     */
    private val dataTimeout: Duration,
    /**
     * The maximum amount of elements we'll allow in the cache
     */
    private val maxNumElements: Int,
    private val timeProvider: TimeProvider = TimeProvider()
) {
    private val cache: TreeMap<IndexType, Container<DataType>> = TreeMap()

    fun insert(index: IndexType, data: DataType) {
        val container = Container(data, timeProvider.currentTimeMillis())
        synchronized(cache) {
            cache[index] = container
            clean(timeProvider.currentTimeMillis() - dataTimeout.toMillis())
        }
    }

    fun get(index: IndexType): DataType? {
        synchronized(cache) {
            val container = cache.getOrDefault(index, null) ?: return null
            return container.data
        }
    }

    /**
     * For each element in this cache, in descending order of the value of their
     * [IndexType], run the given [predicate].  If [predicate] returns false, stop
     * the iteration.
     */
    fun forEachDescending(predicate: (DataType) -> Boolean) {
        synchronized(cache) {
            val iter = cache.descendingMap().iterator()
            while (iter.hasNext()) {
                val (_, container) = iter.next()
                if (!predicate(container.data)) {
                    break
                }
            }
        }
    }

    private fun clean(expirationTimestamp: Long) {
        synchronized(cache) {
            val iter = cache.entries.iterator()
            while (iter.hasNext()) {
                val (_, container) = iter.next()
                if (cache.size > maxNumElements) {
                    iter.remove()
                } else if (container.timeAdded <= expirationTimestamp) {
                    iter.remove()
                } else {
                    // We'll break out of the loop once we find an insertion time that
                    // is not older than the expiration cutoff
                    break
                }
            }
        }
    }

    /**
     * Holds an instance of [Type] as well as when it was
     * added to the data structure so that we can time out
     * old [Container]s
     */
    private data class Container<Type>(var data: Type, var timeAdded: Long)
}
