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

package org.jitsi.rtp

import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

/**
 * A delegate to be used for serialized fields, where changing them should set a dirty flag
 * that any prior serialization needs to be updated to reflect this change
 */
class SerializedField<T>(initialValue: T, private var dirtyField: KMutableProperty0<Boolean>) {
    private var value: T = initialValue
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
        value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        dirtyField.set(true)
        this.value = value
    }
}
