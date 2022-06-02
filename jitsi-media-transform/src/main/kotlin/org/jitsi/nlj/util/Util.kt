/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.rtp.Packet
import java.time.Duration
import java.util.Collections
import java.util.function.Predicate

class Util {
    companion object {
        fun getMbps(numBytes: Number, duration: Duration): Double {
            val mbps = (numBytes.toLong() * 8.0) / (duration.toMillis() * 1000)
            return if (mbps == Double.NaN) -1.0 else mbps
        }
    }
}

/**
 * This method should only be called when the caller is confident the
 * contents of the iterable contain [Expected] types.  Because of this,
 * throwing an exception if that isn't the case is desired.
 */
@Suppress("UNCHECKED_CAST")
inline fun <Expected> Iterable<*>.forEachAs(action: (Expected) -> Unit) {
    for (element in this) action(element as Expected)
}
inline fun <reified Expected> Iterable<*>.forEachIf(action: (Expected) -> Unit) {
    for (element in this) {
        if (element is Expected) action(element)
    }
}

infix fun Int.floorMod(other: Int): Int {
    return Math.floorMod(this, other)
}

/** Set the value at position [index] in a [MutableList] to [element].  If the list has fewer
 * than [index] entries, extend the intermediate entries between its current size and
 * [index] with [fillerElement].
 */
fun <T> MutableList<T>.setAndExtend(index: Int, element: T, fillerElement: T) {
    if (index >= size) {
        addAll(Collections.nCopies(index - size, fillerElement))
        add(element)
    } else {
        this[index] = element
    }
}

/* We inline this so getStackTrace itself doesn't show up in the stack trace. */
@Suppress("NOTHING_TO_INLINE")
inline fun getStackTrace(): String = with(StringBuffer()) {
    for (ste in Thread.currentThread().stackTrace) {
        appendLine(ste.toString())
    }
    toString()
}

typealias PacketPredicate = Predicate<Packet>
