/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.isFinite
import org.jitsi.nlj.util.isInfinite
import org.jitsi.nlj.util.toRoundedEpochMilli
import org.jitsi.utils.logging2.createLogger
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Scenario is a class owning everything for a test scenario. It creates and
 * holds network nodes, call clients and media streams. It also provides methods
 * for changing behavior at runtime. Since it always keeps ownership of the
 * created components, it generally returns non-owning pointers. It maintains
 * the life of its objects until it is destroyed.
 * For methods accepting configuration structs, a modifier function interface is
 * generally provided. This allows simple partial overriding of the default
 * configuration.
 *
 * Based on WebRTC test/scenario/scenario.{h,cc} in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 *
 */

class Scenario(name: String, realTime: Boolean = false) {
    private val logger = createLogger().apply { addContext("name", name) }
    private val networkManager: NetworkEmulationManager = NetworkEmulationManagerImpl()
    private val clock = networkManager.timeController().getClock()
    private val clients = mutableListOf<CallClient>()
    private val clientPairs = mutableListOf<CallClientPair>()
    private val videoStreams = mutableListOf<VideoStreamPair>()
    private val simulationNodes = mutableListOf<SimulationNode>()
    private val printers = mutableListOf<StatesPrinter>()

    private var startTime = Instant.MAX
    private val taskQueue = networkManager.timeController().getTaskQueueFactory().createTaskQueue()

    fun createSimulationNode(config: NetworkSimulationConfig): EmulatedNetworkNode {
        return networkManager.createEmulatedNode(SimulationNode.createBehavior(config))
    }

    fun createSimulationNode(configModifier: (NetworkSimulationConfig) -> Unit): EmulatedNetworkNode {
        val config = NetworkSimulationConfig()
        configModifier(config)
        return createSimulationNode(config)
    }

    fun createMutableSimulationNode(configModifier: (NetworkSimulationConfig) -> Unit): SimulationNode {
        val config = NetworkSimulationConfig()
        configModifier(config)
        return createMutableSimulationNode(config)
    }

    fun createMutableSimulationNode(config: NetworkSimulationConfig): SimulationNode {
        val behavior = SimulationNode.createBehavior(config)
        val emulatedNode = networkManager.createEmulatedNode(behavior)
        simulationNodes.add(SimulationNode(config, behavior, emulatedNode))
        return simulationNodes.last()
    }

    fun createClient(name: String, config: CallClientConfig): CallClient {
        val client = CallClient(networkManager.timeController(), logger, name, config)
        if (config.transport.stateLogInterval.isFinite()) {
            every(config.transport.stateLogInterval) {
                client.networkControllerFactory.logCongestionControllerStats(now())
            }
        }
        clients.add(client)
        return client
    }

    fun createClient(name: String, configModifier: (CallClientConfig) -> Unit): CallClient {
        val config = CallClientConfig()
        configModifier(config)
        return createClient(name, config)
    }

    fun createRoutes(
        first: CallClient,
        sendLink: List<EmulatedNetworkNode>,
        second: CallClient,
        returnLink: List<EmulatedNetworkNode>
    ): CallClientPair = createRoutes(
        first,
        sendLink,
        PacketOverhead.kDefault.bytes,
        second,
        returnLink,
        PacketOverhead.kDefault.bytes
    )

    fun createRoutes(
        first: CallClient,
        sendLink: List<EmulatedNetworkNode>,
        firstOverhead: DataSize,
        second: CallClient,
        returnLink: List<EmulatedNetworkNode>,
        secondOverhead: DataSize
    ): CallClientPair {
        val clientPair = CallClientPair(first, second)
        changeRoute(clientPair.forward(), sendLink, firstOverhead)
        changeRoute(clientPair.reverse(), returnLink, secondOverhead)
        clientPairs.add(clientPair)
        return clientPair
    }

    fun changeRoute(clients: Pair<CallClient, CallClient>, overNodes: List<EmulatedNetworkNode>, overhead: DataSize) {
        val route = networkManager.createRoute(overNodes)
        val port = clients.second.bind(route.to)
        val addr = InetSocketAddress(route.to.getPeerLocalAddress(), port.toUShort().toInt())
        clients.first.transport.connect(route.from, addr, overhead)
    }

    fun createVideoStream(clients: Pair<CallClient, CallClient>, config: VideoStreamConfig): VideoStreamPair {
        val extensions = getVideoRtpExtensions(config)
        clients.first.setVideoReceiveRtpHeaderExtensions(extensions)
        clients.second.setVideoReceiveRtpHeaderExtensions(extensions)
        videoStreams.add(VideoStreamPair(clients.first, clients.second, config))
        return videoStreams.last()
    }

    fun every(interval: Duration, function: () -> Unit) {
        taskQueue
            .scheduleAtFixedRate(function, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun runFor(duration: Duration) {
        if (startTime.isInfinite()) {
            start()
        }
        networkManager.timeController().advanceTime(duration)
    }

    fun start() {
        startTime = clock.instant()
        for (streamPair in videoStreams) {
            streamPair.receive.start()
        }
        for (streamPair in videoStreams) {
            if (streamPair.config.autostart) {
                streamPair.send.start()
            }
        }
    }

    fun timePrinter(): ColumnPrinter {
        return ColumnPrinter(
            "time",
            { sb ->
                sb.append(String.format("%.3lf", now().toRoundedEpochMilli() / 1000.0))
            },
            32
        )
    }

    fun createPrinter(name: String, interval: Duration, printers: List<ColumnPrinter>): StatesPrinter {
        val allPrinters = mutableListOf(timePrinter())
        allPrinters.addAll(printers)
        val printer = StatesPrinter(logger.createChildLogger(name), allPrinters)
        this.printers.add(printer)
        printer.printHeaders()
        if (interval.isFinite()) {
            every(interval) { printer.printRow() }
        }
        return printer
    }

    fun now() = clock.instant()
}
