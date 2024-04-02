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
        // We read the stats from the prometheus metrics and send updated presence. None of these are blocking.
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
