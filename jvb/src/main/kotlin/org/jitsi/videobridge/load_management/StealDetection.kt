/*
 * Copyright @ 2025 - present 8x8, Inc.
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

import org.jitsi.metrics.DoubleGaugeMetric
import java.io.File
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer.Companion.instance as metricsContainer

class StealDetection internal constructor(private val file: File) {

    private var previous = TotalAndSteal(0, 0)

    fun update(): CpuMeasurement {
        val current = try {
            readFromFile() ?: return CpuMeasurement(0.0)
        } catch (e: Exception) {
            return CpuMeasurement(0.0)
        }

        if (previous.steal == 0L && previous.total == 0L) {
            previous = current
            return CpuMeasurement(0.0)
        }

        val totalDelta = current.total - previous.total
        val stealDelta = current.steal - previous.steal
        val currentSteal = if (totalDelta > 0 && stealDelta > 0) {
            stealDelta.toDouble() / totalDelta
        } else {
            0.0
        }

        previous = current
        stealMetric.set(currentSteal)

        return CpuMeasurement(currentSteal)
    }

    private fun readFromFile(): TotalAndSteal? {
        val cpuLine = file.readLines()
            .firstOrNull { it.startsWith("cpu ") }
            ?.split("\\s+".toRegex())
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null

        if (cpuLine.size < 8) return null
        return TotalAndSteal(total = cpuLine.sum(), steal = cpuLine[7])
    }

    companion object {
        private val stealMetric: DoubleGaugeMetric by lazy {
            metricsContainer.registerDoubleGauge(
                "cpu_steal",
                "CPU steal fraction (from 0 to 1)"
            )
        }

        private val linux = System.getProperty("os.name").lowercase().contains("linux")

        val instance = if (linux) StealDetection(File("/proc/stat")) else null
    }
}

private data class TotalAndSteal(val total: Long, val steal: Long)
