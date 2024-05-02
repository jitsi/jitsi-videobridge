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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metrics.MetricsUpdater
import org.jitsi.utils.concurrent.CustomizableThreadFactory
import java.time.Duration
import java.util.concurrent.Executors

object Metrics {
    val interval: Duration by config {
        "videobridge.stats.interval".from(JitsiConfig.newConfig)
    }

    /** Updating the metrics shouldn't block anywhere, but use a separate executor just in case. */
    private val executor = Executors.newSingleThreadScheduledExecutor(
        CustomizableThreadFactory("MetricsUpdater-scheduled", false)
    )
    val metricsUpdater = MetricsUpdater(executor, interval)

    /**
     * The lock which is used when metrics are updated or queried. The [MetricsUpdater] internally uses itself as the
     * lock, so we reuse it here.
     */
    val lock: Any
        get() = metricsUpdater

    fun start() {
        if (JvmMetrics.enable) {
            metricsUpdater.addUpdateTask { JvmMetrics.update() }
        }
        QueueMetrics.init()
    }
    fun stop() {
        metricsUpdater.stop()
        executor.shutdown()
    }
}
