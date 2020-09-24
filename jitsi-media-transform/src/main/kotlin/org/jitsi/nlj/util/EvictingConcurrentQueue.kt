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

import java.util.concurrent.ConcurrentLinkedQueue

class EvictingConcurrentQueue<T>(private val maxSize: Int) : ConcurrentLinkedQueue<T>() {
    override fun add(element: T): Boolean {
        val result = super.add(element)
        if (size > maxSize) {
            // We check on every add, so we can't be more than 1 over the max size
            poll()
        }
        return result
    }
}
