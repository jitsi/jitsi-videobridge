package org.jitsi.videobridge.metrics

import org.jitsi.videobridge.VideobridgeConfig

object VideobridgeMetrics {
    val gracefulShutdown = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "graceful_shutdown",
        "Whether the bridge is in graceful shutdown mode (not accepting new conferences)."
    )
    val drainMode = VideobridgeMetricsContainer.instance.registerBooleanMetric(
        "drain_mode",
        "Whether the bridge is in drain shutdown mode.",
        VideobridgeConfig.initialDrainMode
    )
}