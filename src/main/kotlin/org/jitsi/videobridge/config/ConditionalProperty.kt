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

import org.jitsi.utils.config.ConfigProperty

/**
 * A property which only exists based on the result of the given
 * [predicate].  If [predicate] returns true, the inner property [innerProp]
 * can be accessed via [value], if not, [value] will throw
 */
abstract class ConditionalProperty <T : Any>(
    private val predicate: () -> Boolean,
    private val innerProp: ConfigProperty<T>,
    private val notEnabledMessage: String
) : ConfigProperty<T> {
    override val value: T
        get() {
            return if (!predicate()) {
                throw ConditionalPropertyConditionNotMetException(notEnabledMessage)
            } else {
                innerProp.value
            }
        }
}

class ConditionalPropertyConditionNotMetException(msg: String) :
        Exception("Property not enabled: $msg")