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

package org.jitsi.videobridge.config

/**
 * There are some places where we can't help but rely on a value that comes
 * from a singleton (for example when testing [ConditionalProperty]s which use
 * a function which reads a singleton in their predicate), so this class exists
 * to give a hook to reset singleton instances for recreation.  [reset] is
 * ONLY INTENDED TO BE USED BY TESTS WHERE NECESSARY.
 */
class ResettableSingleton<T : Any>(
    private val initializer: () -> T
) {
    private var instance = initializer()

    fun reset() {
        instance = initializer()
    }

    fun get(): T = instance
}
