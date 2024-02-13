package org.jitsi.videobridge.stats

import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.videobridge.xmpp.config.XmppClientConnectionConfig
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MucPublisher(
    val executor: ScheduledExecutorService,
    val interval: Duration,
    val xmppConnection: XmppConnection
) {
    val logger = createLogger()

    var task: ScheduledFuture<*>? = null
    fun start() {
        logger.info("Starting with interval $interval.")
        task = executor.scheduleAtFixedRate(
            { publishPresence() },
            0,
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }
    fun stop() {
        task?.cancel(true)
        task = null
    }

    private fun publishPresence() {
        val statsExt: ColibriStatsExtension = if (XmppClientConnectionConfig.config.statsFilterEnabled) {
            VideobridgeStatisticsShim.getColibriStatsExtension(XmppClientConnectionConfig.config.statsWhitelist)
        } else {
            VideobridgeStatisticsShim.getColibriStatsExtension()
        }
        logger.debug { "Publishing statistics in presence: $statsExt" }

        xmppConnection.setPresenceExtension(statsExt)
    }
}