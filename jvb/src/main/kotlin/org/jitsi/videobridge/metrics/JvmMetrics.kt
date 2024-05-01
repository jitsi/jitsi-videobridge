/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.videobridge.metrics

import com.sun.management.UnixOperatingSystemMXBean
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.utils.logging2.createLogger
import java.lang.management.ManagementFactory
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer.Companion.instance as metricsContainer

class JvmMetrics private constructor() {
    val logger = createLogger()

    fun update() {
        threadCount.set(ManagementFactory.getThreadMXBean().threadCount.toLong())
        ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
            if (gc.name.lowercase().contains("g1 young")) {
                g1YoungTime.set(gc.collectionTime)
                g1YoungCount.set(gc.collectionCount)
            } else if (gc.name.lowercase().contains("g1 old")) {
                g1OldTime.set(gc.collectionTime)
                g1OldCount.set(gc.collectionCount)
            }
            ManagementFactory.getMemoryPoolMXBeans().toSet().forEach { b ->
                if (b.name.lowercase().contains("g1 old")) {
                    logger.info("${b.name} ${b.memoryManagerNames.joinToString { "," }}....${b.usage}")
                    g1OldUsage.set(b.usage.used)
                    g1OldCapacity.set(b.usage.max)
                }
            }
        }
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)?.let {
            openFdCount.set(it.openFileDescriptorCount)
        }
    }

    val threadCount = metricsContainer.registerLongGauge(
        "thread_count",
        "Current number of JVM threads."
    )

    private val g1YoungCount = metricsContainer.registerLongGauge(
        "jvm_g1_young_count",
        "Collection count for the G1 young generation."
    )

    private val g1YoungTime = metricsContainer.registerLongGauge(
        "jvm_g1_young_time",
        "Collection time for the young G1 generation."
    )

    private val g1OldCount = metricsContainer.registerLongGauge(
        "jvm_g1_old_count",
        "Collection count for the G1 old generation."
    )

    private val g1OldTime = metricsContainer.registerLongGauge(
        "jvm_g1_old_time",
        "Collection time for the G1 old generation."
    )

    private val g1OldCapacity = metricsContainer.registerLongGauge(
        "jvm_g1_old_capacity",
        "Capacity of the G1 Old memory pool."
    )

    private val g1OldUsage = metricsContainer.registerLongGauge(
        "jvm_g1_old_usage",
        "Usage of the G1 Old memory pool."
    )

    private val openFdCount = metricsContainer.registerLongGauge(
        "jvm_open_fd_count",
        "Number of open file descriptors."
    )

    companion object {
        val enable: Boolean by config {
            "videobridge.stats.jvm.enabled".from(JitsiConfig.newConfig)
        }

        val INSTANCE = if (enable) JvmMetrics() else null
        fun update() {
            INSTANCE?.update()
        }
    }
}
