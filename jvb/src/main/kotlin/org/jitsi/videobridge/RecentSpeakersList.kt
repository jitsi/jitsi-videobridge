/*
 * Copyright @ 2021 - Present, 8x8 Inc
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
package org.jitsi.videobridge

import java.util.LinkedList

/**
 * Maintain a list of recent speakers. A speaker can be [promote]d, bumping it to the top of the list. The list of the
 * top [numRecentSpeakers], ordered by the time they were promoted, is exposed as [recentSpeakers].
 *
 * Removal is possible via [removeAllExcept].
 */
class RecentSpeakersList<T>(
    /**
     * The number of speakers to expose via [recentSpeakers]. Internally we store all speakers to allow them to be
     * removed without losing information about the order.
     */
    private val numRecentSpeakers: Int
) {
    /**
     * Store the history of all promoted speakers.
     */
    val list: MutableList<T> = LinkedList()

    /**
     * Promote [endpoint] to the top of the list. If it is already in the list it is moved to the top, otherwise it's
     * added to the top.
     *
     * @return true iff [recentSpeakers] changed as a result of this call.
     */
    fun promote(endpoint: T): Boolean {
        val index = list.indexOf(endpoint)
        if (index >= 0) {
            list.removeAt(index)
        }
        list.add(0, endpoint)
        return index != 0
    }

    /**
     * Remove all entries from the list except the ones contained in [retain].
     *
     * @return true iff [recentSpeakers] changed as a result of this call.
     */
    fun removeAllExcept(retain: List<T>): Boolean {
        val prevRecentSpeakers = recentSpeakers
        list.removeIf { !retain.contains(it) }
        return recentSpeakers != prevRecentSpeakers
    }

    /**
     * The first [numRecentSpeakers] in the list, ordered by the time they were promoted.
     */
    val recentSpeakers: List<T>
        get() = list.take(numRecentSpeakers)

    fun isRecentSpeaker(endpoint: T) = recentSpeakers.contains(endpoint)
}
