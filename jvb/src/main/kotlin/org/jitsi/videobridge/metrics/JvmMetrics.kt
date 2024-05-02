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

    private val gcType = ManagementFactory.getGarbageCollectorMXBeans().firstOrNull()?.name.let {
        when {
            it?.contains("shenandoah", ignoreCase = true) == true -> GcType.Shenandoah
            it?.contains("zgc", ignoreCase = true) == true -> GcType.Zgc
            it?.contains("g1", ignoreCase = true) == true -> GcType.G1
            else -> GcType.Other
        }
    }.also {
        logger.info("Detected GC type $it")
    }

    fun update() {
        threadCount.set(ManagementFactory.getThreadMXBean().threadCount.toLong())
        gcCount.set(
            ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }
        )
        gcTime.set(
            ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
        )
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)?.let {
            openFdCount.set(it.openFileDescriptorCount)
        }
        if (gcType != GcType.Other) {
            ManagementFactory.getMemoryPoolMXBeans().find { it.name == gcType.memoryPoolName }?.let {
                heapUsed.set(it.usage.used)
                heapCommitted.set(it.usage.committed)
            }
        }
    }

    val threadCount = metricsContainer.registerLongGauge(
        "thread_count",
        "Current number of JVM threads."
    )

    private val gcCount = metricsContainer.registerLongGauge(
        "jvm_gc_count",
        "Garbage collection count."
    )

    private val gcTime = metricsContainer.registerLongGauge(
        "jvm_gc_time",
        "Garbage collection time."
    )

    private val heapCommitted = metricsContainer.registerLongGauge(
        "jvm_heap_committed",
        "Capacity of the main memory pool for the heap (GC type specific)."
    )

    private val heapUsed = metricsContainer.registerLongGauge(
        "jvm_heap_used",
        "Usage of the main memory pool for the heap (GC type specific)."
    )

    private val openFdCount = metricsContainer.registerLongGauge(
        "jvm_open_fd_count",
        "Number of open file descriptors."
    )

    private enum class GcType(
        /** The name of the memory pool we're interested with this type of GC */
        val memoryPoolName: String?
    ) {
        G1("G1 Old Gen"),
        Zgc("ZHeap"),
        Shenandoah("Shenandoah"),
        Other(null)
    }

    companion object {
        val enable: Boolean by config {
            "videobridge.stats.jvm.enabled".from(JitsiConfig.newConfig)
        }

        val INSTANCE = if (enable) JvmMetrics() else null
        fun update() = INSTANCE?.update()
    }
}
