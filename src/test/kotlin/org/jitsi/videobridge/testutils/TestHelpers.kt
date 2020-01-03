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

package org.jitsi.videobridge.testutils

import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import org.jitsi.videobridge.config.ResettableSingleton

/**
 * Call reset on a [ResettableSingleton] property named [propName]
 * inside of [receiver].
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> resetSingleton(propName: String, receiver: T) {
    val prop =
        T::class.declaredMemberProperties.find { it.name == propName }
            ?: throw Exception("No property found with name $propName")
    prop.isAccessible = true
    val singleton = prop.get(receiver)
            as? ResettableSingleton<T> ?: throw Exception("Field $propName is not ResettableSingleton")
    singleton.reset()
}
