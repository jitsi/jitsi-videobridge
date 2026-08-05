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

package org.jitsi.videobridge.transport.ice

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.net.InetAddresses
import org.ice4j.Transport
import org.ice4j.TransportAddress
import org.ice4j.ice.Agent
import org.ice4j.ice.CandidateType
import org.ice4j.ice.Component
import org.ice4j.ice.HostCandidate
import org.ice4j.ice.IceMediaStream
import org.ice4j.ice.IceProcessingState
import org.ice4j.ice.LocalCandidate
import org.ice4j.ice.RemoteCandidate
import org.ice4j.ice.harvest.MappingCandidateHarvesters
import org.ice4j.util.Buffer
import org.ice4j.util.BufferHandler
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.ice.Harvesters
import org.jitsi.videobridge.ice.IceConfig
import org.jitsi.videobridge.ice.TransportUtils
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension
import org.jitsi.xmpp.extensions.jingle.IceCandidatePacketExtension
import org.jitsi.xmpp.extensions.jingle.IceRtcpmuxPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet6Address
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

class IceTransport @JvmOverloads constructor(
    id: String,
    /**
     * Whether the ICE agent created by this transport should be the
     * 'controlling' role.
     */
    controlling: Boolean,
    /**
     * Whether the ICE agent created by this transport should use
     * unique local ports, rather than the configured port.
     */
    val useUniquePort: Boolean,
    /**
     * Use private addresses for this [IceTransport] even if [IceConfig.advertisePrivateCandidates] is false.
     */
    private val advertisePrivateAddresses: Boolean,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * The handler which will be invoked when data is received.
     * This field should be set by some other entity which wishes to handle the incoming data
     * received over the ICE connection.
     */
    @JvmField
    var incomingDataHandler: IncomingDataHandler? = null

    /**
     * The handler which will be invoked when events fired by [IceTransport]
     * occur.  This field should be set by another entity who wishes to handle
     * the events.  Handlers will only be notified of events which occur
     * *after* the handler has been set.
     */
    @JvmField
    var eventHandler: EventHandler? = null

    /**
     * Whether or not it is possible to write to this [IceTransport].
     *
     * This happens as soon as any candidate pair is validated, and happens (usually) before iceConnected.
     */
    private val iceWriteable = AtomicBoolean(false)

    /**
     * Whether or not this [IceTransport] has connected.
     */
    private val iceConnected = AtomicBoolean(false)

    /**
     * Whether or not this [IceTransport] has failed to connect.
     */
    private val iceFailed = AtomicBoolean(false)

    fun hasFailed(): Boolean = iceFailed.get()

    fun isWriteable(): Boolean = iceWriteable.get()

    fun isConnected(): Boolean = iceConnected.get()

    /**
     * Whether or not this transport is 'running'.  If it is not
     * running, no more data will be read from the socket or sent out.
     */
    private val running = AtomicBoolean(true)

    private val packetStats = PacketStats()

    /**
     * Whether the ice4j [Agent]s created by this transport take the 'controlling' role.
     */
    private val controlling = controlling

    /**
     * Guards transitions between [currentBundle] and [pendingBundle].
     */
    private val restartLock = Any()

    /**
     * The [AgentBundle] that media is currently sent on and whose credentials we advertise. Replaced (via
     * [cutOver]) when an ICE restart's new Agent connects.
     */
    @Volatile
    private var currentBundle: AgentBundle

    /**
     * The [AgentBundle] of an ICE restart that is in progress but has not connected yet, if any. It runs
     * alongside [currentBundle], which keeps sending until the cutover (make-before-break).
     */
    @Volatile
    private var pendingBundle: AgentBundle? = null

    /**
     * The ICE generation of the most recent restart we started. The bridge owns this counter: each accepted
     * restart request gets the next value, it is advertised on the transport we return (as `ice-generation`),
     * and the peer echoes it on the transport-info carrying its own new credentials. That lets us match the
     * peer's response to the right pending bundle and discard responses from a superseded round.
     *
     * Restart generations start at 1. Generation 0 means "the initial allocation, never restarted" and
     * [IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED] means the attribute is absent altogether: the initial
     * bundle carries the latter, so nothing is stamped on the wire until a restart actually happens. Clients reject
     * a restart transport whose generation is not >= 1, so this must not start any lower.
     *
     * Guarded by [restartLock].
     */
    private var restartGeneration = 0

    init {
        currentBundle = AgentBundle(IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED)
        logger.addContext("local_ufrag", currentBundle.agent.localUfrag)
    }

    /**
     * The password used to authenticate the colibri WebSocket for this transport.
     *
     * This is deliberately pinned to the *initial* Agent's password and is NOT rotated by an ICE restart:
     * the client is handed a WebSocket URL containing this password once (when its transport is described)
     * and re-dials that same URL when the WebSocket reconnects — which is precisely what happens on the
     * network change that triggers a restart. Rotating it would invalidate the URL the client already holds.
     * The ICE-level password (advertised in [describe] and used for connectivity checks) does rotate; these
     * are separate concerns that happened to share a field before ICE restarts existed.
     */
    val webSocketPassword: String = currentBundle.agent.localPassword

    /**
     * An ice4j [Agent] together with the single stream and component we create on it and the listeners we
     * attach to them. An [IceTransport] has exactly one of these normally, and briefly two while an ICE
     * restart is in flight: the established one (which keeps sending) and the new one (which is running
     * connectivity checks with freshly rotated local credentials).
     *
     * @param generation the `ice-generation` of the restart round that created this bundle, or
     * [IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED] for the initial bundle.
     */
    private inner class AgentBundle(val generation: Int) {
        private val stateChangeListener = PropertyChangeListener { ev -> iceStateChanged(this@AgentBundle, ev) }
        private val pairChangeListener = PropertyChangeListener { ev -> iceStreamPairChanged(this@AgentBundle, ev) }

        /** When this bundle was created, used to report how long a restart took. */
        val createdAt: Instant = clock.instant()

        /**
         * Whether connectivity establishment has been started on [agent]. For a restart's bundle this only
         * happens once the peer's new remote credentials arrive, and it must happen at most once.
         */
        val checksStarted = AtomicBoolean(false)

        val agent: Agent = Agent(IceConfig.config.ufragPrefix, logger).apply {
            if (useUniquePort) {
                setUseDynamicPorts(true)
            } else {
                appendHarvesters(this)
            }
            isControlling = this@IceTransport.controlling
            performConsentFreshness = true
            nominationStrategy = IceConfig.config.nominationStrategy
            addStateChangeListener(stateChangeListener)
        }

        val stream: IceMediaStream = agent.createMediaStream("stream").apply {
            addPairChangeListener(pairChangeListener)
        }

        val component: Component = agent.createComponent(stream, IceConfig.config.keepAliveStrategy, false).apply {
            setBufferCallback(object : BufferHandler {
                override fun handleBuffer(buffer: Buffer) {
                    incomingDataHandler?.dataReceived(buffer) ?: run {
                        packetStats.numIncomingPacketsDroppedNoHandler.increment()
                        ByteBufferPool.returnBuffer(buffer.buffer)
                    }
                }
            })
        }

        fun free() {
            agent.removeStateChangeListener(stateChangeListener)
            stream.removePairStateChangeListener(pairChangeListener)
            agent.free()
        }

        override fun toString(): String = "AgentBundle[generation=$generation, ufrag=${agent.localUfrag}]"
    }

    /**
     * Tell this [IceTransport] to start ICE connectivity establishment.
     */
    fun startConnectivityEstablishment(transportPacketExtension: IceUdpTransportPacketExtension) {
        if (!running.get()) {
            logger.warn("Not starting connectivity establishment, transport is not running")
            return
        }

        // While an ICE restart is in flight every transport update for this endpoint belongs to that restart:
        // it is the peer answering with the new credentials that our new Agent needs in order to address its
        // own connectivity checks. Route it to the pending bundle instead of the established one.
        pendingBundle?.let { pending ->
            applyRemoteCredentialsToPendingRestart(pending, transportPacketExtension)
            return
        }

        val bundle = currentBundle
        if (bundle.agent.state.isEstablished) {
            logger.cdebug { "Connection already established" }
            return
        }
        logger.cdebug { "Starting ICE connectivity establishment" }

        // Set the remote ufrag/password
        bundle.stream.remoteUfrag = transportPacketExtension.ufrag
        bundle.stream.remotePassword = transportPacketExtension.password

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        val iceAgentStateIsRunning = IceProcessingState.RUNNING == bundle.agent.state

        val remoteCandidates = transportPacketExtension.getChildExtensionsOfType(CandidatePacketExtension::class.java)
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty()) {
            logger.cdebug {
                "Ignoring transport extensions with no candidates, " +
                    "the Agent is already running."
            }
            return
        }

        val remoteCandidateCount = addRemoteCandidates(bundle, remoteCandidates, iceAgentStateIsRunning)
        if (iceAgentStateIsRunning) {
            when (remoteCandidateCount) {
                0 -> {
                    // XXX Effectively, the check above but realizing that all
                    // candidates were ignored:
                    // iceAgentStateIsRunning && candidates.isEmpty().
                }
                else -> bundle.component.updateRemoteCandidates()
            }
        } else if (remoteCandidateCount != 0) {
            // Once again, because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            if (bundle.component.remoteCandidateCount > 0) {
                logger.debug("Starting the agent with remote candidates.")
                bundle.agent.startConnectivityEstablishment()
                bundle.checksStarted.set(true)
            }
        } else if (bundle.stream.remoteUfragAndPasswordKnown()) {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.debug("Starting the Agent without remote candidates.")
            bundle.agent.startConnectivityEstablishment()
            bundle.checksStarted.set(true)
        } else {
            logger.cdebug { "Not starting ICE, no ufrag and pwd yet. ${transportPacketExtension.toXML()}" }
        }
    }

    /**
     * Starts an ICE restart, as explicitly requested by the peer (colibri2 `<transport ice-restart="true"/>`).
     *
     * We create a second [Agent] with freshly rotated local credentials of our own (RFC 8445 section 9, so that
     * checks belonging to different restart generations can be told apart by their credentials) and assign it
     * the next [restartGeneration]. Connectivity checks are deliberately *not* started yet: we are the
     * controlling agent, so our own checks need the peer's new remote credentials to build their USERNAME and
     * MESSAGE-INTEGRITY, and those only arrive later (see [applyRemoteCredentialsToPendingRestart]). The new
     * Agent does answer incoming checks in the meantime — ice4j starts its connectivity check server when the
     * component is created and queues pre-RUNNING checks until we start.
     *
     * The established Agent keeps its selected pair and keeps sending throughout, so media is not interrupted
     * (make-before-break); we only [cutOver] once the new Agent connects, and keep the old one alive for
     * [IceConfig.restartTransitionWindow] after that so old-generation checks still in flight are answered.
     *
     * @return true if a restart was started, in which case the caller must signal our new transport (rotated
     * credentials plus the new `ice-generation`) back to the peer. False if the request was rejected, in which
     * case nothing changed and the established Agent keeps running.
     */
    fun requestIceRestart(): Boolean {
        if (!running.get()) {
            logger.warn("Rejecting ICE restart request: the transport is not running.")
            iceRestartsRejected.inc()
            return false
        }
        if (!IceConfig.config.restartEnabled) {
            logger.warn(
                "Rejecting ICE restart request: ICE restarts are disabled " +
                    "(videobridge.ice.restart.enabled=false)."
            )
            iceRestartsRejected.inc()
            return false
        }
        if (!currentBundle.agent.state.isEstablished) {
            // There is no established connectivity to preserve, so there is nothing for a make-before-break
            // restart to do. The initial Agent is still gathering/checking and the peer should keep using it.
            logger.warn(
                "Rejecting ICE restart request: the transport is not established yet " +
                    "(state=${currentBundle.agent.state})."
            )
            iceRestartsRejected.inc()
            return false
        }

        val newBundle: AgentBundle
        val generation: Int
        synchronized(restartLock) {
            // A newer restart supersedes one that has not connected yet.
            pendingBundle?.let { superseded ->
                logger.info("Superseding an in-flight ICE restart: $superseded")
                pendingBundle = null
                TaskPools.IO_POOL.submit { superseded.free() }
            }

            generation = ++restartGeneration
            newBundle = AgentBundle(generation)
            pendingBundle = newBundle
        }

        logger.info(
            "ICE restart requested (generation=$generation): created a pending Agent with local ufrag=" +
                "${newBundle.agent.localUfrag} (current local ufrag=${currentBundle.agent.localUfrag}). " +
                "Waiting for the peer's new remote credentials before starting connectivity checks."
        )
        iceRestartsStarted.inc()

        val timeout = IceConfig.config.restartTimeout
        TaskPools.SCHEDULED_POOL.schedule(
            { abandonPendingRestart(newBundle, "it did not connect within $timeout") },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS
        )

        return true
    }

    /**
     * Handles a transport update that arrived while an ICE restart is pending. This is step two of a restart:
     * the peer has applied the transport we returned from [requestIceRestart] and is now signalling its own
     * new ICE credentials, tagged with the `ice-generation` we advertised. Apply them to the pending Agent and
     * start its connectivity checks, which is the first moment we can — the checks we send as the controlling
     * agent are authenticated with the peer's password.
     */
    private fun applyRemoteCredentialsToPendingRestart(
        pending: AgentBundle,
        transportPacketExtension: IceUdpTransportPacketExtension
    ) {
        val generation = transportPacketExtension.iceGeneration
        if (generation != IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED &&
            generation != pending.generation
        ) {
            logger.warn(
                "Ignoring remote credentials for ICE generation $generation, the pending ICE restart is " +
                    "generation ${pending.generation}."
            )
            iceRestartsRejected.inc()
            return
        }
        if (generation == IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED) {
            logger.info(
                "Remote credentials arrived with no ice-generation while ICE restart generation " +
                    "${pending.generation} is pending; assuming they belong to it."
            )
        }

        val ufrag = transportPacketExtension.ufrag
        val password = transportPacketExtension.password
        if (ufrag == null || password == null) {
            logger.warn(
                "Ignoring a transport update with no ufrag/pwd while ICE restart generation " +
                    "${pending.generation} is pending."
            )
            return
        }

        if (!pending.checksStarted.compareAndSet(false, true)) {
            logger.info(
                "Already started connectivity checks for ICE restart generation ${pending.generation}, " +
                    "ignoring a repeated transport update (remote ufrag=$ufrag)."
            )
            return
        }

        // Re-check under the lock: the restart may have been superseded or abandoned since we read it.
        synchronized(restartLock) {
            if (pendingBundle !== pending) {
                logger.info(
                    "ICE restart generation ${pending.generation} is no longer pending, dropping the " +
                        "remote credentials that arrived for it."
                )
                return
            }
            pending.stream.remoteUfrag = ufrag
            pending.stream.remotePassword = password
        }

        // Add any signalled remote candidates. Normally there are none: clients do not signal candidates to the
        // bridge and are discovered peer-reflexively from their incoming checks, which is also how a new address
        // after a real network change is learned.
        val remoteCandidates = transportPacketExtension.getChildExtensionsOfType(CandidatePacketExtension::class.java)
        val remoteCandidateCount = addRemoteCandidates(pending, remoteCandidates, iceAgentIsRunning = false)

        logger.info(
            "Applied the peer's new remote credentials to the pending ICE restart " +
                "(generation=${pending.generation}, remote ufrag=$ufrag, remoteCandidates=" +
                "$remoteCandidateCount, local ufrag=${pending.agent.localUfrag}), starting connectivity checks."
        )
        pending.agent.startConnectivityEstablishment()
    }

    /**
     * Switches [currentBundle] over to [newBundle], which has just connected, and schedules the old bundle to be
     * freed after [IceConfig.restartTransitionWindow]. Both bundles accept incoming connectivity checks during
     * that window (they have different local ufrags, so ice4j's single-port demultiplexing routes each
     * generation's checks to the right Agent), which is what keeps the handover from dropping checks that were
     * already in flight.
     */
    private fun cutOver(newBundle: AgentBundle) {
        val oldBundle = synchronized(restartLock) {
            if (pendingBundle !== newBundle) {
                // Superseded or abandoned while it was connecting.
                return
            }
            val old = currentBundle
            currentBundle = newBundle
            pendingBundle = null
            old
        }

        val transitionWindow = IceConfig.config.restartTransitionWindow
        val elapsedMs = Duration.between(newBundle.createdAt, clock.instant()).toMillis()
        logger.info(
            "ICE restart (generation=${newBundle.generation}) connected after ${elapsedMs}ms, cutting over " +
                "from local ufrag ${oldBundle.agent.localUfrag} to ${newBundle.agent.localUfrag}. Freeing the " +
                "old Agent in $transitionWindow."
        )
        iceRestartsSucceeded.inc()

        if (useUniquePort) {
            // ice4j's push API only works with the single port harvester, so with unique ports we have to read
            // from the new Agent's socket ourselves. The old bundle's reader exits when its socket is closed.
            TaskPools.IO_POOL.submit { startReadingData(newBundle) }
        }

        if (transitionWindow.isZero || transitionWindow.isNegative) {
            TaskPools.IO_POOL.submit { oldBundle.free() }
        } else {
            TaskPools.SCHEDULED_POOL.schedule(
                {
                    logger.info(
                        "ICE restart (generation=${newBundle.generation}) transition window elapsed, freeing " +
                            "the old Agent with local ufrag ${oldBundle.agent.localUfrag}."
                    )
                    oldBundle.free()
                },
                transitionWindow.toMillis(),
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * Gives up on an ICE restart whose new Agent never connected (it failed, or the timeout elapsed), keeping
     * the established Agent in place — the transport itself does not fail. A no-op if the restart already cut
     * over or was superseded.
     */
    private fun abandonPendingRestart(bundle: AgentBundle, reason: String) {
        synchronized(restartLock) {
            if (pendingBundle !== bundle) {
                return
            }
            pendingBundle = null
        }
        val elapsedMs = Duration.between(bundle.createdAt, clock.instant()).toMillis()
        logger.warn(
            "Abandoning ICE restart (generation=${bundle.generation}, local ufrag=${bundle.agent.localUfrag}) " +
                "after ${elapsedMs}ms: $reason. Keeping the established Agent with local ufrag " +
                "${currentBundle.agent.localUfrag}."
        )
        iceRestartsFailed.inc()
        // Not inline: this can run on the Agent's own state-change notification thread.
        TaskPools.IO_POOL.submit { bundle.free() }
    }

    private fun startReadingData(bundle: AgentBundle) {
        logger.cdebug { "Starting to read incoming data" }
        val socket = bundle.component.selectedPair.iceSocketWrapper
        val receiveBuf = ByteArray(1500)
        val packet = DatagramPacket(receiveBuf, 0, receiveBuf.size)
        var receivedTime: Instant

        while (running.get()) {
            try {
                socket.receive(packet)
                receivedTime = clock.instant()
            } catch (e: IOException) {
                logger.warn("Stopping reader", e)
                break
            }
            packetStats.numPacketsReceived.increment()
            try {
                val b = ByteBufferPool.getBuffer(
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET + packet.length + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                )
                System.arraycopy(
                    packet.data,
                    packet.offset,
                    b,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    packet.length
                )
                val buffer = Buffer(b, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, packet.length, receivedTime)

                incomingDataHandler?.dataReceived(buffer) ?: run {
                    logger.cdebug { "Data handler is null, dropping data" }
                    packetStats.numIncomingPacketsDroppedNoHandler.increment()
                }
            } catch (e: Throwable) {
                logger.error("Uncaught exception processing packet", e)
            }
        }
        logger.info("No longer running, stopped reading packets")
    }

    /**
     * Send data out via this transport
     */
    fun send(data: ByteArray, off: Int, length: Int) {
        if (running.get()) {
            try {
                // Always the established bundle: during a restart the new Agent is still running checks and we
                // keep sending on the old selected pair until cutOver() swaps it in (make-before-break).
                currentBundle.component.send(data, off, length)
                packetStats.numPacketsSent.increment()
            } catch (e: IOException) {
                logger.error("Error sending packet", e)
                throw RuntimeException()
            }
        } else {
            packetStats.numOutgoingPacketsDroppedStopped.increment()
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping")
            val bundles = synchronized(restartLock) {
                buildList {
                    add(currentBundle)
                    pendingBundle?.let { add(it) }
                    pendingBundle = null
                }
            }
            bundles.forEach { it.free() }
        }
    }

    fun getDebugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("keepAliveStrategy", IceConfig.config.keepAliveStrategy.toString())
        put("nominationStrategy", IceConfig.config.nominationStrategy.toString())
        put("advertisePrivateCandidates", IceConfig.config.advertisePrivateCandidates)
        put("closed", !running.get())
        put("iceWriteable", iceWriteable.get())
        put("iceConnected", iceConnected.get())
        put("iceFailed", iceFailed.get())
        put("localUfrag", currentBundle.agent.localUfrag)
        put("iceGeneration", currentBundle.generation)
        val pending = pendingBundle
        put("restartPending", pending != null)
        if (pending != null) {
            put("pendingIceGeneration", pending.generation)
            put("pendingLocalUfrag", pending.agent.localUfrag)
            put("pendingChecksStarted", pending.checksStarted.get())
        }
        setAll<ObjectNode>(packetStats.toJson())
    }

    fun describe(pe: IceUdpTransportPacketExtension) {
        if (!running.get()) {
            logger.warn("Not describing, transport is not running")
        }
        // Prefer a pending restart's bundle: once we have rolled a new Agent, its credentials are the ones the
        // peer must address its connectivity checks to (the USERNAME and MESSAGE-INTEGRITY of a check are
        // built from the *peer's* view of our ufrag/password). Describing the old ones would send the peer
        // checking against an Agent we are about to retire. With no restart in flight this is the established
        // bundle, so the initial allocation path is unchanged.
        val bundle = pendingBundle ?: currentBundle
        with(pe) {
            password = bundle.agent.localPassword
            ufrag = bundle.agent.localUfrag
            if (bundle.generation != IceUdpTransportPacketExtension.GENERATION_UNSPECIFIED) {
                // Stamp the generation of the restart round these credentials belong to. The peer echoes it
                // back on the transport-info carrying its own new credentials, which is how we match its
                // answer to this bundle and discard answers from a round we have since moved on from.
                iceGeneration = bundle.generation
            }
            bundle.component.localCandidates?.forEach { cand ->
                cand.toCandidatePacketExtension(advertisePrivateAddresses)?.let { pe.addChildExtension(it) }
            }
            addChildExtension(IceRtcpmuxPacketExtension())
        }
    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
    private fun addRemoteCandidates(
        bundle: AgentBundle,
        remoteCandidates: List<CandidatePacketExtension>,
        iceAgentIsRunning: Boolean
    ): Int {
        var remoteCandidateCount = 0
        // Sort the remote candidates (host < reflexive < relayed) in order to
        // create first the host, then the reflexive, the relayed candidates and
        // thus be able to set the relative-candidate matching the
        // rel-addr/rel-port attribute.
        remoteCandidates.sorted().forEach { candidate ->
            // Is the remote candidate from the current generation of the
            // iceAgent?
            if (candidate.generation != bundle.agent.generation) {
                return@forEach
            }
            if (candidate.ipNeedsResolution() && !IceConfig.config.resolveRemoteCandidates) {
                logger.cdebug { "Ignoring remote candidate with non-literal address: ${candidate.ip}" }
                return@forEach
            }
            val component = bundle.stream.getComponent(candidate.component)
            val remoteCandidate = RemoteCandidate(
                TransportAddress(candidate.ip, candidate.port, Transport.parse(candidate.protocol)),
                component,
                CandidateType.parse(candidate.type.toString()),
                candidate.foundation,
                candidate.priority.toLong(),
                null
            )
            // XXX IceTransport harvests host candidates only and the
            // ICE Components utilize the UDP protocol/transport only at the
            // time of this writing. The ice4j library will, of course, check
            // the theoretical reachability between the local and the remote
            // candidates. However, we would like (1) to not mess with a
            // possibly running iceAgent and (2) to return a consistent return
            // value.
            if (!TransportUtils.canReach(component, remoteCandidate)) {
                return@forEach
            }
            if (iceAgentIsRunning) {
                component.addUpdateRemoteCandidates(remoteCandidate)
            } else {
                component.addRemoteCandidate(remoteCandidate)
            }
            remoteCandidateCount++
        }

        return remoteCandidateCount
    }

    private fun iceStateChanged(bundle: AgentBundle, ev: PropertyChangeEvent) {
        val oldState = ev.oldValue as IceProcessingState
        val newState = ev.newValue as IceProcessingState
        val transition = IceProcessingStateTransition(oldState, newState)

        val isPending = bundle === pendingBundle
        val isCurrent = bundle === currentBundle
        logger.debug(
            "ICE state changed old=$oldState new=$newState for $bundle (pending=$isPending, current=$isCurrent)"
        )

        if (!isPending && !isCurrent) {
            // A bundle we have already moved on from: either the pre-restart Agent inside its transition
            // window, or one that was superseded and is being freed. It no longer speaks for the transport, so
            // in particular it must not fail it.
            logger.debug("Ignoring an ICE state change from a retired $bundle")
            return
        }

        when {
            transition.completed() -> {
                if (isPending) {
                    // A restart's new Agent connected: swap it in and retire the old one.
                    cutOver(bundle)
                } else if (iceConnected.compareAndSet(false, true)) {
                    eventHandler?.connected()
                    if (useUniquePort) {
                        // ice4j's push API only works with the single port harvester. With unique ports we still need
                        // to read from the socket.
                        TaskPools.IO_POOL.submit {
                            startReadingData(bundle)
                        }
                    }
                    if (bundle.component.selectedPair.remoteCandidate.type == CandidateType.RELAYED_CANDIDATE ||
                        bundle.component.selectedPair.localCandidate.type == CandidateType.RELAYED_CANDIDATE
                    ) {
                        iceSucceededRelayed.inc()
                    }
                    iceSucceeded.inc()
                }
            }
            transition.failed() -> {
                if (isPending) {
                    // Only the restart failed. The established Agent is untouched, so keep using it rather
                    // than failing the whole transport.
                    abandonPendingRestart(bundle, "the new Agent failed to connect")
                } else if (iceFailed.compareAndSet(false, true)) {
                    eventHandler?.failed()
                    Companion.iceFailed.inc()
                }
            }
        }
    }

    /** Update IceStatistics once an initial round-trip-time measurement is available. */
    fun updateStatsOnInitialRtt(rttMs: Double) {
        val selectedPair = currentBundle.component.selectedPair
        val localCandidate = selectedPair?.localCandidate ?: return
        val harvesterName = if (localCandidate is HostCandidate) {
            "host"
        } else {
            MappingCandidateHarvesters.findHarvesterForAddress(localCandidate.transportAddress)?.name ?: "other"
        }

        IceStatistics.stats.add(harvesterName, rttMs)
    }

    private fun iceStreamPairChanged(bundle: AgentBundle, ev: PropertyChangeEvent) {
        // Only the bundle we actually send on speaks for the connection's liveness. A lingering old bundle (in
        // its transition window) or a pending one still running checks must not report writeability or refresh
        // consent on the transport's behalf.
        if (bundle !== currentBundle) {
            return
        }
        if (IceMediaStream.PROPERTY_PAIR_VALIDATED == ev.propertyName) {
            if (iceWriteable.compareAndSet(false, true)) {
                eventHandler?.writeable()
            }
        } else if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED == ev.propertyName) {
            /* TODO: Currently ice4j only triggers this event for the selected
             * pair, but should we double-check the pair anyway?
             */
            val time = Instant.ofEpochMilli(ev.newValue as Long)
            eventHandler?.consentUpdated(time)
        }
    }

    companion object {
        fun appendHarvesters(iceAgent: Agent) {
            Harvesters.INSTANCE.singlePortHarvesters.forEach(iceAgent::addCandidateHarvester)
        }

        /**
         * The total number of times an ICE Agent failed to establish
         * connectivity.
         */
        val iceFailed = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_failed",
            "Number of times an ICE Agent failed to establish connectivity."
        )

        /**
         * The total number of times an ICE Agent succeeded.
         */
        val iceSucceeded = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_succeeded",
            "Number of times an ICE Agent succeeded."
        )

        /**
         * The total number of times an ICE Agent succeeded and the selected
         * candidate pair included a relayed candidate.
         */
        val iceSucceededRelayed = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_succeeded_relayed",
            "Number of times an ICE Agent succeeded and the selected pair included a relayed candidate."
        )

        /**
         * The total number of ICE restarts started (a new Agent was created for a peer-requested restart).
         */
        val iceRestartsStarted = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_restarts_started",
            "Number of ICE restarts started."
        )

        /**
         * The total number of ICE restart requests rejected before a new Agent was created: the feature is
         * disabled, the transport was not established (or not running), or the peer's credentials arrived
         * tagged with a generation that does not match the pending restart.
         */
        val iceRestartsRejected = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_restarts_rejected",
            "Number of ICE restart requests rejected (disabled, not established, or a stale generation)."
        )

        /**
         * The total number of ICE restarts whose new Agent connected and was cut over to.
         */
        val iceRestartsSucceeded = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_restarts_succeeded",
            "Number of ICE restarts whose new Agent connected and was cut over to."
        )

        /**
         * The total number of ICE restarts abandoned because the new Agent failed or timed out. The
         * established Agent is kept in these cases, so this does not imply the endpoint lost connectivity.
         */
        val iceRestartsFailed = VideobridgeMetricsContainer.instance.registerCounter(
            "ice_restarts_failed",
            "Number of ICE restarts abandoned because the new Agent failed to connect."
        )
    }

    private class PacketStats {
        val numPacketsReceived = LongAdder()
        val numIncomingPacketsDroppedNoHandler = LongAdder()
        val numPacketsSent = LongAdder()
        val numOutgoingPacketsDroppedStopped = LongAdder()

        fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
            put("num_packets_received", numPacketsReceived.sum())
            put("num_incoming_packets_dropped_no_handler", numIncomingPacketsDroppedNoHandler.sum())
            put("num_packets_sent", numPacketsSent.sum())
            put("num_outgoing_packets_dropped_stopped", numOutgoingPacketsDroppedStopped.sum())
        }
    }

    interface IncomingDataHandler {
        /**
         * Notify the handler that data was received (contained
         * within [data] at [offset] with [length]) at [receivedTime]
         */
        fun dataReceived(buffer: Buffer)
    }

    interface EventHandler {
        /**
         * Notify the event handler that it is possible to write to the ICE stack
         */
        fun writeable()

        /**
         * Notify the event handler that ICE connected successfully
         */
        fun connected()

        /**
         * Notify the event handler that ICE failed to connect
         */
        fun failed()

        /**
         * Notify the event handler that ICE consent was updated
         */
        fun consentUpdated(time: Instant)
    }
}

/**
 * Models a transition from one ICE state to another and provides convenience
 * functions to test the transition.
 */
private data class IceProcessingStateTransition(
    val oldState: IceProcessingState,
    val newState: IceProcessingState
) {
    // We should be using newState.isEstablished() here, but we see
    // transitions from RUNNING to TERMINATED, which can happen if the Agent is
    // free prior to being started, so we handle that case separately below.
    fun completed(): Boolean = newState == IceProcessingState.COMPLETED

    fun failed(): Boolean {
        return newState == IceProcessingState.FAILED ||
            (oldState == IceProcessingState.RUNNING && newState == IceProcessingState.TERMINATED)
    }
}

private fun IceMediaStream.remoteUfragAndPasswordKnown(): Boolean = remoteUfrag != null && remotePassword != null

private fun CandidatePacketExtension.ipNeedsResolution(): Boolean = !InetAddresses.isInetAddress(ip)

private fun TransportAddress.isPrivateAddress(): Boolean = address.isSiteLocalAddress ||
    /* 0xfc00::/7 */
    ((address is Inet6Address) && ((addressBytes[0].toInt() and 0xfe) == 0xfc))

private fun generateCandidateId(candidate: LocalCandidate): String = buildString {
    append(java.lang.Long.toHexString(hashCode().toLong()))
    append(java.lang.Long.toHexString(candidate.parentComponent.parentStream.parentAgent.hashCode().toLong()))
    append(java.lang.Long.toHexString(candidate.parentComponent.parentStream.parentAgent.generation.toLong()))
    append(java.lang.Long.toHexString(candidate.hashCode().toLong()))
}

private fun LocalCandidate.toCandidatePacketExtension(advertisePrivateAddresses: Boolean): CandidatePacketExtension? {
    if (transportAddress.isPrivateAddress() &&
        !advertisePrivateAddresses &&
        !IceConfig.config.advertisePrivateCandidates
    ) {
        return null
    }
    val cpe = IceCandidatePacketExtension()
    cpe.component = parentComponent.componentID
    cpe.foundation = foundation
    cpe.generation = parentComponent.parentStream.parentAgent.generation
    cpe.id = generateCandidateId(this)
    cpe.network = 0
    cpe.setPriority(priority)

    cpe.protocol = transport.toString()
    cpe.type = org.jitsi.xmpp.extensions.jingle.CandidateType.valueOf(type.toString())
    cpe.ip = transportAddress.hostAddress
    cpe.port = transportAddress.port

    relatedAddress?.let {
        if (!IceConfig.config.advertisePrivateCandidates && it.isPrivateAddress()) {
            cpe.relAddr = "0.0.0.0"
            cpe.relPort = 9
        } else {
            cpe.relAddr = it.hostAddress
            cpe.relPort = it.port
        }
    }

    return cpe
}
