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