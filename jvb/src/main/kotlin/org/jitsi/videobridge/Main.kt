/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge

import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.servlet.ServletContainer
import org.ice4j.ice.harvest.MappingCandidateHarvesters
import org.jitsi.cmd.CmdLine
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.MetaconfigLogger
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.rest.JettyBundleActivatorConfig
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.stats.media.Utils
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.ice.Harvesters
import org.jitsi.videobridge.rest.root.Application
import org.jitsi.videobridge.stats.StatsManager
import org.jitsi.videobridge.stats.VideobridgeStatistics
import org.jitsi.videobridge.stats.callstats.CallstatsService
import org.jitsi.videobridge.stats.config.StatsTransportConfig
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.websocket.ColibriWebSocketService
import org.jitsi.videobridge.websocket.singleton as webSocketServiceSingleton
import org.jitsi.videobridge.xmpp.XmppConnection
import kotlin.concurrent.thread
import org.jitsi.videobridge.octo.singleton as octoRelayService

fun main(args: Array<String>) {
    val cmdLine = CmdLine().apply { parse(args) }
    val logger = LoggerImpl("org.jitsi.videobridge.Main")

    setupMetaconfigLogger()

    setSystemPropertyDefaults()

    // Some of our dependencies bring in slf4j, which means Jetty will default to using
    // slf4j as its logging backend.  The version of slf4j brought in, however, is too old
    // for Jetty so it throws errors.  We use java.util.logging so tell Jetty to use that
    // as its logging backend.
    // TODO: Instead of setting this here, we should integrate it with the infra/debian scripts
    //  to be passed.
    System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.JavaUtilLog")

    // Before initializing the application programming interfaces (APIs) of
    // Jitsi Videobridge, set any System properties which they use and which
    // may be specified by the command-line arguments.
    System.setProperty(
        Videobridge.REST_API_PNAME,
        cmdLine.getOptionValue("--apis").contains(Videobridge.REST_API).toString()
    )

    // Reload the Typesafe config used by ice4j, because the original was initialized before the new system
    // properties were set.
    JitsiConfig.reloadNewConfig()

    startIce4j()

    val xmppConnection = XmppConnection().apply { start() }
    val shutdownService = ShutdownServiceImpl()
    val videobridge = Videobridge(xmppConnection, shutdownService).apply { start() }
    val octoRelayService = octoRelayService().get()?.apply { start() }
    val statsMgr = if (StatsManager.config.enabled) {
        StatsManager(VideobridgeStatistics(videobridge, octoRelayService, xmppConnection)).apply {
            StatsManager.config.transportConfigs.forEach { transportConfig ->
                // TODO move this config and code to the Muc classes
                if (transportConfig is StatsTransportConfig.MucStatsTransportConfig) {
                    addTransport(
                        transportConfig.toStatsTransport(xmppConnection),
                        transportConfig.interval.toMillis()
                    )
                }
            }
            start()
        }
    } else {
        null
    }

    val callstats = if (CallstatsService.config.enabled) {
        CallstatsService(videobridge, statsMgr)
    } else {
        logger.info("Not starting CallstatsService, disabled in configuration.")
        null
    }

    val publicServerConfig = JettyBundleActivatorConfig(
        "org.jitsi.videobridge.rest",
        "videobridge.http-servers.public"
    )
    val publicHttpServer = if (publicServerConfig.isEnabled()) {
        logger.info("Starting public http server")

        val websocketService = ColibriWebSocketService(publicServerConfig.isTls)
        webSocketServiceSingleton().setColibriWebSocketService(websocketService)
        createServer(publicServerConfig).also {
            websocketService.registerServlet(it.servletContextHandler, videobridge)
            it.start()
        }
    } else {
        logger.info("Not starting public http server")
        null
    }

    val privateServerConfig = JettyBundleActivatorConfig(
        "org.jitsi.videobridge.rest.private",
        "videobridge.http-servers.private"
    )
    val privateHttpServer = if (privateServerConfig.isEnabled()) {
        logger.info("Starting private http server")
        val restApp = Application(videobridge, xmppConnection, statsMgr)
        createServer(privateServerConfig).also {
            it.servletContextHandler.addServlet(
                ServletHolder(ServletContainer(restApp)),
                "/*"
            )
            it.start()
        }
    } else {
        logger.info("Not starting private http server")
        null
    }

    // Block here until the bridge shuts down
    shutdownService.waitForShutdown()

    logger.info("Bridge shutting down")
    octoRelayService?.stop()
    xmppConnection.stop()
    callstats?.stop()
    statsMgr?.stop()

    try {
        publicHttpServer?.stop()
        privateHttpServer?.stop()
    } catch (t: Throwable) {
        logger.error("Error shutting down http servers", t)
    }
    videobridge.stop()
    stopIce4j()

    TaskPools.SCHEDULED_POOL.shutdownNow()
    TaskPools.CPU_POOL.shutdownNow()
    TaskPools.IO_POOL.shutdownNow()
}

private fun setupMetaconfigLogger() {
    val configLogger = LoggerImpl("org.jitsi.config")
    MetaconfigSettings.logger = object : MetaconfigLogger {
        override fun warn(block: () -> String) = configLogger.warn(block)
        override fun error(block: () -> String) = configLogger.error(block)
        override fun debug(block: () -> String) = configLogger.debug(block)
    }
}

private fun setSystemPropertyDefaults() {
    val defaults = getSystemPropertyDefaults()

    defaults.forEach { (key, value) ->
        if (System.getProperty(key) == null) {
            System.setProperty(key, value)
        }
    }
}

private fun getSystemPropertyDefaults(): Map<String, String> {
    val defaults = mutableMapOf<String, String>()
    Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults)

    // Make legacy ice4j properties system properties.
    val cfg = JitsiConfig.SipCommunicatorProps
    val ice4jPropNames = cfg.getPropertyNamesByPrefix("org.ice4j", false)
    ice4jPropNames?.forEach { key ->
        cfg.getString(key)?.let { value ->
            defaults.put(key, value)
        }
    }

    return defaults
}

private fun startIce4j() {
    // Start the initialization of the mapping candidate harvesters.
    // Asynchronous, because the AWS and STUN harvester may take a long
    // time to initialize.
    thread(start = true) {
        MappingCandidateHarvesters.initialize()
    }
}

private fun stopIce4j() {
    // Shut down harvesters.
    Harvesters.closeStaticConfiguration()

    System.getProperties().keys.forEach { key ->
        if (key.toString().startsWith("org.ice4j")) {
            System.clearProperty(key.toString())
        }
    }
}
