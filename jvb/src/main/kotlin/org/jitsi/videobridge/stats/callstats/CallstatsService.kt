/*
 * Copyright @ 2020 - Present, 8x8 Inc
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

package org.jitsi.videobridge.stats.callstats

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.stats.media.StatsService
import org.jitsi.stats.media.StatsServiceFactory
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.stats.StatsManager
import org.jitsi.videobridge.stats.config.StatsTransportConfig
import java.time.Duration

class CallstatsService(
    private val videobridge: Videobridge,
    private val statsManager: StatsManager?
) {
    private val logger = createLogger()

    /**
     * The entry point into the jitsi-stats library used to send stats to callstats. It is initialized asynchronously.
     */
    private var statsService: StatsService? = null

    /**
     * The handler for conference created/expired events, which enables sending of per-conference statistics.
     * Initialized asynchronously.
     */
    private var conferenceManager: CallstatsConferenceManager? = null

    /**
     * The [StatsTransport] used to send global stats to callstats. Initialized asynchronously, and only if the stats
     * manager is available to provide stats.
     */
    private var statsTransport: CallstatsTransport? = null

    init {
        logger.info("Initializing CallstatsService with config: $config")

        // as we create only one instance of StatsService
        StatsServiceFactory.getInstance().createStatsService(
            videobridge.versionService.currentVersion,
            config.appId,
            config.appSecret,
            config.keyId,
            config.keyPath,
            config.bridgeId,
            /* isClient = */ false,
            object : StatsServiceFactory.InitCallback {
                override fun error(reason: String, message: String) {
                    logger.error(
                        "Jitsi-stats service failed to initialize with reason: $reason and error message: $message "
                    )
                }

                override fun onInitialized(statsService: StatsService, message: String) {
                    logger.info("Jitsi-stats service initialized: $message")
                    statsServiceInitialized(statsService)
                }
            })
    }

    fun statsServiceInitialized(statsService: StatsService) {
        // Now that the callstats/jitsi-stats service has been initialized, we can hook up to global statistics and
        // conference create/expire events from [Videobridge]

        this.statsService = statsService

        statsManager?.let { statsManager ->
            logger.info("Subscribing to global stats with interval ${config.interval}")
            statsTransport = CallstatsTransport(statsService)
                .also { statsTransport ->
                statsManager.addTransport(statsTransport, config.interval.toMillis())
            }
        }

        conferenceManager =
            CallstatsConferenceManager(
                statsService,
                config.bridgeId,
                config.interval.toMillis(),
                config.conferenceIdPrefix
            )

        videobridge.addEventHandler(conferenceManager)
    }

    fun stop() {
        logger.info("Stopping CallstatsService")
        conferenceManager?.let {
            videobridge.removeEventHandler(it)
            it.stop()
        }
        statsManager?.let { statsManager ->
            statsTransport?.let {
                statsManager.removeTransport(it)
            }
        }

        conferenceManager = null
        statsTransport = null
        statsService = null
    }

    companion object {
        val config = CallstatsConfig()
    }
}

class CallstatsConfig {
    /**
     * The callstats AppID.
     */
    val appId: Int by config {
        "io.callstats.sdk.CallStats.appId".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.app-id".from(JitsiConfig.newConfig)
    }

    /**
     * Shared Secret for authentication on Callstats.io
     */
    val appSecret: String? by optionalconfig {
        "io.callstats.sdk.CallStats.appSecret".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.app-secret".from(JitsiConfig.newConfig)
    }

    /**
     * ID of the key that was used to generate token.
     */
    val keyId: String? by optionalconfig {
        "io.callstats.sdk.CallStats.keyId".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.key-id".from(JitsiConfig.newConfig)
    }

    /**
     * The path to private key file.
     */
    val keyPath: String? by optionalconfig {
        "io.callstats.sdk.CallStats.keyPath".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.key-path".from(JitsiConfig.newConfig)
    }

    val bridgeId: String by config {
        "io.callstats.sdk.CallStats.bridgeId".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.bridge-id".from(JitsiConfig.newConfig)
    }

    /**
     * The bridge conference prefix to report to callstats.io.
     */
    val conferenceIdPrefix: String? by optionalconfig {
        "io.callstats.sdk.CallStats.conferenceIDPrefix".from(JitsiConfig.legacyConfig)
        "videobridge.callstats.conference-id-prefix".from(JitsiConfig.newConfig)
    }

    private val intervalProperty: Duration by config {
        "videobridge.callstats.interval".from(JitsiConfig.newConfig)
    }

    /**
     * This is the interval at which stats are pushed to callstats. It affects both global and per-conference stats.
     *
     * For backwards compatibility, we read it from the stats manager "callstatsio" transport, if present.
     */
    val interval: Duration = StatsManager.config.transportConfigs.stream()
        .filter { tc -> tc is StatsTransportConfig.CallStatsIoStatsTransportConfig }
        .map(StatsTransportConfig::interval)
        .findFirst()
        .orElse(intervalProperty)

    val enabled: Boolean = appId > 0

    override fun toString() = "appId=$appId, appSecret is ${if (appSecret == null) "unset" else "set"}, keyId=$keyId," +
        " keyPath=$keyPath, bridgeId=$bridgeId, conferenceIdPrefix=$conferenceIdPrefix, interval=$interval"
}
