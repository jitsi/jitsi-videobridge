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
import org.jitsi.utils.OrderedJsonObject
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
import java.time.Instant
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

    private val iceStateChangeListener = PropertyChangeListener { ev -> iceStateChanged(ev) }
    private val iceStreamPairChangedListener = PropertyChangeListener { ev -> iceStreamPairChanged(ev) }

    private val iceAgent = Agent(IceConfig.config.ufragPrefix, logger).apply {
        if (useUniquePort) {
            setUseDynamicPorts(true)
        } else {
            appendHarvesters(this)
        }
        isControlling = controlling
        performConsentFreshness = true
        nominationStrategy = IceConfig.config.nominationStrategy
        addStateChangeListener(iceStateChangeListener)
    }.also {
        logger.addContext("local_ufrag", it.localUfrag)
    }

    private val iceStream = iceAgent.createMediaStream("stream").apply {
        addPairChangeListener(iceStreamPairChangedListener)
    }

    private val iceComponent = iceAgent.createComponent(iceStream, IceConfig.config.keepAliveStrategy, false).apply {
        setBufferCallback(object : BufferHandler {
            override fun handleBuffer(buffer: Buffer) {
                incomingDataHandler?.dataReceived(buffer) ?: run {
                    packetStats.numIncomingPacketsDroppedNoHandler.increment()
                    ByteBufferPool.returnBuffer(buffer.buffer)
                }
            }
        })
    }
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
                logger.debug("Starting the agent with remote candidates.")
                iceAgent.startConnectivityEstablishment()
            }
        } else if (iceStream.remoteUfragAndPasswordKnown()) {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.debug("Starting the Agent without remote candidates.")
            iceAgent.startConnectivityEstablishment()
        } else {
            logger.cdebug { "Not starting ICE, no ufrag and pwd yet. ${transportPacketExtension.toXML()}" }
        }
    }

    fun startReadingData() {
        logger.cdebug { "Starting to read incoming data" }
        val socket = iceComponent.selectedPair.iceSocketWrapper
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
                iceComponent.send(data, off, length)
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
            iceAgent.removeStateChangeListener(iceStateChangeListener)
            iceStream.removePairStateChangeListener(iceStreamPairChangedListener)
            iceAgent.free()
        }
    }

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        put("keepAliveStrategy", IceConfig.config.keepAliveStrategy.toString())
        put("nominationStrategy", IceConfig.config.nominationStrategy.toString())
        put("advertisePrivateCandidates", IceConfig.config.advertisePrivateCandidates)
        put("closed", !running.get())
        put("iceWriteable", iceWriteable.get())
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
            iceComponent.localCandidates?.forEach { cand ->
                cand.toCandidatePacketExtension(advertisePrivateAddresses)?.let { pe.addChildExtension(it) }
            }
            addChildExtension(IceRtcpmuxPacketExtension())
        }
    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
    private fun addRemoteCandidates(remoteCandidates: List<CandidatePacketExtension>, iceAgentIsRunning: Boolean): Int {
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

        logger.debug("ICE state changed old=$oldState new=$newState")

        when {
            transition.completed() -> {
                if (iceConnected.compareAndSet(false, true)) {
                    eventHandler?.connected()
                    if (useUniquePort) {
                        // ice4j's push API only works with the single port harvester. With unique ports we still need
                        // to read from the socket.
                        TaskPools.IO_POOL.submit {
                            startReadingData()
                        }
                    }
                    if (iceComponent.selectedPair.remoteCandidate.type == CandidateType.RELAYED_CANDIDATE ||
                        iceComponent.selectedPair.localCandidate.type == CandidateType.RELAYED_CANDIDATE
                    ) {
                        iceSucceededRelayed.inc()
                    }
                    iceSucceeded.inc()
                }
            }
            transition.failed() -> {
                if (iceFailed.compareAndSet(false, true)) {
                    eventHandler?.failed()
                    Companion.iceFailed.inc()
                }
            }
        }
    }

    /** Update IceStatistics once an initial round-trip-time measurement is available. */
    fun updateStatsOnInitialRtt(rttMs: Double) {
        val selectedPair = iceComponent.selectedPair
        val localCandidate = selectedPair?.localCandidate ?: return
        val harvesterName = if (localCandidate is HostCandidate) {
            "host"
        } else {
            MappingCandidateHarvesters.findHarvesterForAddress(localCandidate.transportAddress)?.name ?: "other"
        }

        IceStatistics.stats.add(harvesterName, rttMs)
    }

    private fun iceStreamPairChanged(ev: PropertyChangeEvent) {
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
    }

    private class PacketStats {
        val numPacketsReceived = LongAdder()
        val numIncomingPacketsDroppedNoHandler = LongAdder()
        val numPacketsSent = LongAdder()
        val numOutgoingPacketsDroppedStopped = LongAdder()

        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
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
