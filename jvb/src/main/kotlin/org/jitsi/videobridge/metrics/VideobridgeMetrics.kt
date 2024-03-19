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

import org.jitsi.videobridge.VideobridgeConfig
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension

object VideobridgeMetrics {
    val gracefulShutdown = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "graceful_shutdown",
        "Whether the bridge is in graceful shutdown mode (not accepting new conferences)."
    )
    val shuttingDown = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "shutting_down",
        "Whether the bridge is shutting down."
    )
    val drainMode = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "drain_mode",
        "Whether the bridge is in drain shutdown mode.",
        VideobridgeConfig.initialDrainMode
    )

    /** The currently configured region, if any. */
    val regionInfo = if (RelayConfig.config.region != null) {
        VideobridgeMetricsContainer.instance.registerInfo(
            ColibriStatsExtension.REGION, "The currently configured region.",
            RelayConfig.config.region!!
        )
    } else null
}