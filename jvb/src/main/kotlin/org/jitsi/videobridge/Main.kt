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
import org.ice4j.ice.harvest.AbstractUdpListener
import org.ice4j.ice.harvest.MappingCandidateHarvesters
import org.ice4j.util.Buffer
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.MetaconfigLogger
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.nlj.dtls.DtlsConfig
import org.jitsi.rest.JettyBundleActivatorConfig
import org.jitsi.rest.createServer
import org.jitsi.rest.enableCors
import org.jitsi.rest.isEnabled
import org.jitsi.rest.servletContextHandler
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.videobridge.ice.Harvesters
import org.jitsi.videobridge.metrics.Metrics
import org.jitsi.videobridge.metrics.VideobridgePeriodicMetrics
import org.jitsi.videobridge.rest.root.Application
import org.jitsi.videobridge.stats.MucPublisher
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.UlimitCheck
import org.jitsi.videobridge.version.JvbVersionService
import org.jitsi.videobridge.websocket.ColibriWebSocketService
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.videobridge.xmpp.config.XmppClientConnectionConfig
import sun.misc.Signal
import java.time.Clock
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import org.jitsi.videobridge.websocket.singleton as webSocketServiceSingleton

fun main() {
    val logger = LoggerImpl("org.jitsi.videobridge.Main")

    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        logger.error("An uncaught exception occurred in thread=$thread", exception)
    }

    setupMetaconfigLogger()

    setSystemPropertyDefaults()

    // Some of our dependencies bring in slf4j, which means Jetty will default to using
    // slf4j as its logging backend.  The version of slf4j brought in, however, is too old
    // for Jetty so it throws errors.  We use java.util.logging so tell Jetty to use that
    // as its logging backend.
    // TODO: Instead of setting this here, we should integrate it with the infra/debian scripts
    //  to be passed.
    System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.JavaUtilLog")

    Metrics.start()

    // Reload the Typesafe config used by ice4j, because the original was initialized before the new system
    // properties were set.
    JitsiConfig.reloadNewConfig()

    logger.info("Starting jitsi-videobridge version ${JvbVersionService.instance.currentVersion}")

    UlimitCheck.printUlimits()
    startIce4j()

    setupBufferPools()

    // Initialize, binding on the main ICE port.
    Harvesters.init()

    org.jitsi.videobridge.xmpp.Smack.initialize()
    PacketQueue.setEnableStatisticsDefault(true)

    // Trigger an exception early in case the DTLS cipher suites are misconfigured
    try {
        DtlsConfig.config.cipherSuites
    } catch (ce: ConfigException) {
        logger.error("Dtls configuration error: $ce")
        // According to https://freedesktop.org/software/systemd/man/systemd.exec…html#Process%20Exit%20Code
        // 78 means "configuration error"
        exitProcess(78)
    }

    val xmppConnection = XmppConnection().apply { start() }
    val shutdownService = ShutdownServiceImpl()
    val videobridge = Videobridge(
        xmppConnection,
        shutdownService,
        JvbVersionService.instance.currentVersion,
        Clock.systemUTC()
    )
    val videobridgeExpireThread = VideobridgeExpireThread(videobridge).apply { start() }
    Metrics.metricsUpdater.addUpdateTask {
        VideobridgePeriodicMetrics.update(videobridge)
    }
    val healthChecker = videobridge.jvbHealthChecker
    val presencePublisher = MucPublisher(
        TaskPools.SCHEDULED_POOL,
        XmppClientConnectionConfig.config.presenceInterval,
        xmppConnection
    ).apply { start() }

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
        val restApp = Application(
            videobridge,
            xmppConnection,
            JvbVersionService.instance.currentVersion,
            healthChecker
        )
        createServer(privateServerConfig).also {
            it.servletContextHandler.addServlet(
                ServletHolder(ServletContainer(restApp)),
                "/*"
            )
            it.servletContextHandler.enableCors()
            it.start()
        }
    } else {
        logger.info("Not starting private http server")
        null
    }

    var exitStatus = 0

    /* Catch signals and cause them to trigger a clean shutdown. */
    listOf("TERM", "HUP", "INT").forEach { signalName ->
        try {
            Signal.handle(Signal(signalName)) { signal ->
                exitStatus = signal.number + 128 // Matches java.lang.Terminator
                logger.info("Caught signal $signal, shutting down.")

                shutdownService.beginShutdown()
            }
        } catch (e: IllegalArgumentException) {
            /* Unknown signal on this platform, or not allowed to register this signal; that's fine. */
            logger.warn("Unable to register signal '$signalName'", e)
        }
    }

    // Block here until the bridge shuts down
    shutdownService.waitForShutdown()

    logger.info("Bridge shutting down")
    healthChecker.stop()
    presencePublisher.stop()
    xmppConnection.stop()

    try {
        publicHttpServer?.stop()
        privateHttpServer?.stop()
    } catch (t: Throwable) {
        logger.error("Error shutting down http servers", t)
    }
    videobridgeExpireThread.stop()
    videobridge.stop()
    stopIce4j()
    Metrics.stop()

    TaskPools.SCHEDULED_POOL.shutdownNow()
    TaskPools.CPU_POOL.shutdownNow()
    TaskPools.IO_POOL.shutdownNow()

    exitProcess(exitStatus)
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
    AbstractUdpListener.USE_PUSH_API = true
    // Start the initialization of the mapping candidate harvesters.
    // Asynchronous, because the AWS and STUN harvester may take a long
    // time to initialize.
    thread(start = true) {
        MappingCandidateHarvesters.initialize()
    }
}

private fun stopIce4j() {
    // Shut down harvesters.
    Harvesters.close()
}

/** Configure our libraries to use JVB's global [ByteBufferPool] */
private fun setupBufferPools() {
    org.jitsi.rtp.util.BufferPool.getArray = { ByteBufferPool.getBuffer(it) }
    org.jitsi.rtp.util.BufferPool.returnArray = { ByteBufferPool.returnBuffer(it) }
    org.jitsi.nlj.util.BufferPool.getBuffer = { ByteBufferPool.getBuffer(it) }
    org.jitsi.nlj.util.BufferPool.returnBuffer = { ByteBufferPool.returnBuffer(it) }
    org.ice4j.util.BufferPool.getBuffer = { len ->
        val b = ByteBufferPool.getBuffer(len)
        Buffer(b, 0, b.size)
    }
    org.ice4j.util.BufferPool.returnBuffer = { ByteBufferPool.returnBuffer(it.buffer) }
    org.ice4j.ice.harvest.AbstractUdpListener.BYTES_TO_LEAVE_AT_START_OF_PACKET =
        RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET
    org.ice4j.ice.harvest.AbstractUdpListener.BYTES_TO_LEAVE_AT_END_OF_PACKET =
        Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
}
