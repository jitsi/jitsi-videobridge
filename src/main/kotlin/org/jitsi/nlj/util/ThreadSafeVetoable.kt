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

import kotlin.properties.ObservableProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Same as [kotlin.properties.Delegates.vetoable], but thread safe
 */
public inline fun <T> threadSafeVetoable(
    initialValue: T,
    crossinline onChange: (property: KProperty<*>, oldValue: T, newValue: T) -> Boolean
): ReadWriteProperty<Any?, T> = object : ObservableProperty<T>(initialValue) {
        private val lock = Any()

        override fun beforeChange(property: KProperty<*>, oldValue: T, newValue: T): Boolean =
            onChange(property, oldValue, newValue)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            synchronized(lock) {
                super.setValue(thisRef, property, value)
            }
        }
    }
