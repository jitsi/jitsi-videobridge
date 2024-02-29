/*
 * Copyright @ 2023 - present 8x8, Inc.
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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

class CpuMeasurement(private val value: Double) : JvbLoadMeasurement {
    override fun getLoad(): Double = value

    override fun div(other: JvbLoadMeasurement): Double {
        if (other !is CpuMeasurement) {
            throw UnsupportedOperationException("Can only divide load measurements of same type")
        }
        return value / other.value
    }

    override fun toString(): String = "CPU usage ${String.format("%.2f", value * 100)}%"

    companion object {
        val loadThreshold: CpuMeasurement by config {
            "${JvbLoadMeasurement.CONFIG_BASE}.cpu-usage.load-threshold"
                .from(JitsiConfig.newConfig)
                .convertFrom<Double>(::CpuMeasurement)
        }
        val recoverThreshold: CpuMeasurement by config {
            "${JvbLoadMeasurement.CONFIG_BASE}.cpu-usage.recovery-threshold"
                .from(JitsiConfig.newConfig)
                .convertFrom<Double>(::CpuMeasurement)
        }
    }
}
