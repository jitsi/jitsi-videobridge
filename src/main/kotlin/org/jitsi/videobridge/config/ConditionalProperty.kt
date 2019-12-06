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
 * Define a property which only exists based on the result of the given
 * [predicate].  If [predicate] returns true, we define the prop via the
 * given [propDef], if not, we define a property which will throw on access
 * and use the given [notEnabledMessage]
 * TODO: move this to Jicoco
 */
fun <T : Any >conditionalProperty(
    predicate: () -> Boolean,
    propDef: () -> ConfigProperty<T>,
    notEnabledMessage: String
): ConfigProperty<T> {
    return if (predicate()) {
        propDef()
    } else {
        object : ConfigProperty<T> {
            override val value: T
                get() = throw ConditionalPropertyConditionNotMetException(notEnabledMessage)
        }
    }
}

class ConditionalPropertyConditionNotMetException(msg: String) :
        Exception("Property not enabled: $msg")