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

import com.google.common.net.InetAddresses
import org.ice4j.Transport
import org.ice4j.TransportAddress
import org.ice4j.ice.Agent
import org.ice4j.ice.CandidateType
import org.ice4j.ice.IceMediaStream
import org.ice4j.ice.IceProcessingState
import org.ice4j.ice.LocalCandidate
import org.ice4j.ice.RemoteCandidate
import org.ice4j.ice.harvest.SinglePortUdpHarvester
import org.ice4j.socket.SocketClosedException
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.ice.Harvesters
import org.jitsi.videobridge.ice.IceConfig
import org.jitsi.videobridge.ice.TransportUtils
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtcpmuxPacketExtension
import java.beans.PropertyChangeEvent
import java.io.IOException
import java.net.DatagramPacket
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class IceTransport @JvmOverloads constructor(
    id: String,
    /**
     * Whether or not the ICE agent created by this transport should be the
     * 'controlling' role.
     */
    controlling: Boolean,
    isJvbClient: Boolean,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * The handler which will be invoked when data is received.  The handler
     * does *not* own the buffer passed to it, so a copy must be made if it wants
     * to use the data after the handler call finishes.  This field should be
     * set by some other entity which wishes to handle the incoming data
     * received over the ICE connection.
     * NOTE: we don't create a packet in [IceTransport] because
     * RTP packets want space before and after and [IceTransport]
     * has no notion of what kind of data is contained within the buffer.
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
     * Whether or not this [IceTransport] has connected.
     */
    private val iceConnected = AtomicBoolean(false)
    /**
     * Whether or not this [IceTransport] has failed to connect.
     */
    private val iceFailed = AtomicBoolean(false)

    fun hasFailed(): Boolean = iceFailed.get()

    fun isConnected(): Boolean = iceConnected.get()

    /**
     * Whether or not this transport is 'running'.  If it is not
     * running, no more data will be read from the socket or sent out.
     */
    private val running = AtomicBoolean(true)

    private val iceAgent = Agent(IceConfig.config.ufragPrefix, logger).apply {
        appendHarvesters(this, isJvbClient)
        isControlling = controlling
        performConsentFreshness = true
        nominationStrategy = IceConfig.config.nominationStrategy
        addStateChangeListener(this@IceTransport::iceStateChanged)
    }.also {
        logger.addContext("local_ufrag", it.localUfrag)
    }

    // TODO: Do we still need the id here now that we have logContext?
    private val iceStream = iceAgent.createMediaStream("stream-$id").apply {
        addPairChangeListener(this@IceTransport::iceStreamPairChanged)
    }

    private val iceComponent = iceAgent.createComponent(
        iceStream,
        Transport.UDP,
        -1,
        -1,
        -1,
        IceConfig.config.keepAliveStrategy,
        IceConfig.config.useComponentSocket
    )

    private val packetStats = PacketStats()

    val icePassword: String
        get() = iceAgent.localPassword

    /**
     * Tell this [IceTransport] to start ICE connectivity establishment.
     */
    fun startConnectivityEstablishment(transportPacketExtension: IceUdpTransportPacketExtension) {
        if (!running.get()) {
            logger.warn("Not starting connectivity establishment, transport is not running")
            return
        }
        if (iceAgent.state.isEstablished) {
            logger.cdebug { "Connection already established" }
            return
        }
        logger.cdebug { "Starting ICE connectivity establishment" }

        // Set the remote ufrag/password
        iceStream.remoteUfrag = transportPacketExtension.ufrag
        iceStream.remotePassword = transportPacketExtension.password

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        val iceAgentStateIsRunning = IceProcessingState.RUNNING == iceAgent.state

        val remoteCandidates = transportPacketExtension.getChildExtensionsOfType(CandidatePacketExtension::class.java)
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty()) {
            logger.cdebug {
                "Ignoring transport extensions with no candidates, " +
                    "the Agent is already running."
            }
            return
        }

        val remoteCandidateCount = addRemoteCandidates(remoteCandidates, iceAgentStateIsRunning)
        if (iceAgentStateIsRunning) {
            when (remoteCandidateCount) {
                0 -> {
                    // XXX Effectively, the check above but realizing that all
                    // candidates were ignored:
                    // iceAgentStateIsRunning && candidates.isEmpty().
                }
                else -> iceComponent.updateRemoteCandidates()
            }
        } else if (remoteCandidateCount != 0) {
            // Once again, because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            if (iceComponent.remoteCandidateCount > 0) {
                logger.info("Starting the agent with remote candidates.")
                iceAgent.startConnectivityEstablishment()
            }
        } else if (iceStream.remoteUfragAndPasswordKnown()) {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.info("Starting the Agent without remote candidates.")
            iceAgent.startConnectivityEstablishment()
        } else {
            logger.cdebug { "Not starting ICE, no ufrag and pwd yet. ${transportPacketExtension.toXML()}" }
        }
    }

    fun startReadingData() {
        logger.cdebug { "Starting to read incoming data" }
        val socket = iceComponent.socket
        val receiveBuf = ByteArray(1500)
        val packet = DatagramPacket(receiveBuf, 0, receiveBuf.size)
        var receivedTime: Instant

        while (running.get()) {
            try {
                socket.receive(packet)
                receivedTime = clock.instant()
            } catch (e: SocketClosedException) {
                logger.info("Socket closed, stopping reader")
                break
            } catch (e: IOException) {
                logger.warn("Stopping reader", e)
                break
            }
            packetStats.numPacketsReceived++
            incomingDataHandler?.dataReceived(receiveBuf, packet.offset, packet.length, receivedTime) ?: run {
                logger.cdebug { "Data handler is null, dropping data" }
                packetStats.numIncomingPacketsDroppedNoHandler++
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
                iceComponent.socket.send(DatagramPacket(data, off, length))
                packetStats.numPacketsSent++
            } catch (e: IOException) {
                logger.error("Error sending packet", e)
                throw RuntimeException()
            }
        } else {
            packetStats.numOutgoingPacketsDroppedStopped++
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping")
            iceAgent.removeStateChangeListener(this::iceStateChanged)
            iceStream.removePairStateChangeListener(this::iceStreamPairChanged)
            iceAgent.free()
        }
    }

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        put("useComponentSocket", IceConfig.config.useComponentSocket)
        put("keepAliveStrategy", IceConfig.config.keepAliveStrategy.toString())
        put("closed", !running.get())
        put("iceConnected", iceConnected.get())
        put("iceFailed", iceFailed.get())
        putAll(packetStats.toJson())
    }

    fun describe(pe: IceUdpTransportPacketExtension) {
        if (!running.get()) {
            logger.warn("Not describing, transport is not running")
        }
        with(pe) {
            password = iceAgent.localPassword
            ufrag = iceAgent.localUfrag
            iceComponent.localCandidates?.forEach { pe.addChildExtension(it.toCandidatePacketExtension()) }
            addChildExtension(RtcpmuxPacketExtension())
        }
    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
    private fun addRemoteCandidates(
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
            if (candidate.generation != iceAgent.generation) {
                return@forEach
            }
            if (candidate.ipNeedsResolution() && !IceConfig.config.resolveRemoteCandidates) {
                logger.cdebug { "Ignoring remote candidate with non-literal address: ${candidate.ip}" }
                return@forEach
            }
            val component = iceStream.getComponent(candidate.component)
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

    private fun iceStateChanged(ev: PropertyChangeEvent) {
        val oldState = ev.oldValue as IceProcessingState
        val newState = ev.newValue as IceProcessingState
        val transition = IceProcessingStateTransition(oldState, newState)

        logger.info("ICE state changed old=$oldState new=$newState")

        when {
            transition.completed() -> {
                if (iceConnected.compareAndSet(false, true)) {
                    eventHandler?.connected()
                }
            }
            transition.failed() -> {
                if (iceFailed.compareAndSet(false, true)) {
                    eventHandler?.failed()
                }
            }
        }
    }

    private fun iceStreamPairChanged(ev: PropertyChangeEvent) {
        if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED == ev.propertyName) {
            /* TODO: Currently ice4j only triggers this event for the selected
             * pair, but should we double-check the pair anyway?
             */
            val time = Instant.ofEpochMilli(ev.newValue as Long)
            eventHandler?.consentUpdated(time)
        }
    }

    companion object {
        fun appendHarvesters(iceAgent: Agent, isJvbClient: Boolean) {
            Harvesters.initializeStaticConfiguration()
            if (isJvbClient) {
                SinglePortUdpHarvester.createHarvesters(0).forEach(iceAgent::addCandidateHarvester)
            }
            Harvesters.tcpHarvester?.let {
                iceAgent.addCandidateHarvester(it)
            }
            Harvesters.singlePortHarvesters?.forEach(iceAgent::addCandidateHarvester)
        }
    }

    private data class PacketStats(
        var numPacketsReceived: Int = 0,
        var numIncomingPacketsDroppedNoHandler: Int = 0,
        var numPacketsSent: Int = 0,
        var numOutgoingPacketsDroppedStopped: Int = 0
    ) {
        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("num_packets_received", numPacketsReceived)
            put("num_incoming_packets_dropped_no_handler", numIncomingPacketsDroppedNoHandler)
            put("num_packets_sent", numPacketsSent)
            put("num_outgoing_packets_dropped_stopped", numOutgoingPacketsDroppedStopped)
        }
    }

    interface IncomingDataHandler {
        /**
         * Notify the handler that data was received (contained
         * within [data] at [offset] with [length]) at [receivedTime]
         */
        fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant)
    }

    interface EventHandler {
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

private fun IceMediaStream.remoteUfragAndPasswordKnown(): Boolean =
    remoteUfrag != null && remotePassword != null

private fun CandidatePacketExtension.ipNeedsResolution(): Boolean =
    !InetAddresses.isInetAddress(ip)

private fun Transport.isTcpType(): Boolean = this == Transport.TCP || this == Transport.SSLTCP

private fun generateCandidateId(candidate: LocalCandidate): String = buildString {
    append(java.lang.Long.toHexString(hashCode().toLong()))
    append(java.lang.Long.toHexString(candidate.parentComponent.parentStream.parentAgent.hashCode().toLong()))
    append(java.lang.Long.toHexString(candidate.parentComponent.parentStream.parentAgent.generation.toLong()))
    append(java.lang.Long.toHexString(candidate.hashCode().toLong()))
}

private fun LocalCandidate.toCandidatePacketExtension(): CandidatePacketExtension {
    val cpe = CandidatePacketExtension()
    cpe.component = parentComponent.componentID
    cpe.foundation = foundation
    cpe.generation = parentComponent.parentStream.parentAgent.generation
    cpe.id = generateCandidateId(this)
    cpe.network = 0
    cpe.setPriority(priority)

    // Advertise 'tcp' candidates for which SSL is enabled as 'ssltcp'
    // (although internally their transport protocol remains "tcp")
    cpe.protocol = if (transport == Transport.TCP && isSSL) {
        Transport.SSLTCP.toString()
    } else {
        transport.toString()
    }
    if (transport.isTcpType()) {
        cpe.tcpType = tcpType.toString()
    }
    cpe.type = org.jitsi.xmpp.extensions.jingle.CandidateType.valueOf(type.toString())
    cpe.ip = transportAddress.hostAddress
    cpe.port = transportAddress.port

    relatedAddress?.let {
        cpe.relAddr = it.hostAddress
        cpe.relPort = it.port
    }

    return cpe
}
