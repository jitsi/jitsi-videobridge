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

package org.jitsi.videobridge.load_management

/**
 * A measurement of load on the JVB.  Implementations should implement a description of the
 * measurement in the [toString] method.
 */
interface JvbLoadMeasurement {
    fun getLoad(): Double

    operator fun div(other: JvbLoadMeasurement): Double

    companion object {
        const val CONFIG_BASE = "videobridge.load-management.load-measurements"
    }
}
