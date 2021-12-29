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
package org.jitsi.videobridge.relay

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Transceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.EncodingsManager
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.TransportConfig
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.octo.OctoPacketInfo
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.jitsi.videobridge.websocket.colibriWebSocketServiceSupplier
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier

/**
 * Models a relay (remote videobridge) in a [Conference].
 */
/* TODO: figure out how best to share code between this and [Endpoint], without multiple inheritance. */
class Relay @JvmOverloads constructor(
    /**
     * The unique identifier of this [Relay]
     */
    val id: String,
    /**
     * The [Conference] this [Relay] belongs to.
     */
    val conference: Conference,
    parentLogger: Logger,
    /**
     * True if the ICE agent for this [Relay] will be initialized to serve
     * as a controlling ICE agent, false otherwise
     */
    iceControlling: Boolean,
    useUniquePort: Boolean,
    private val clock: Clock = Clock.systemUTC()
) : EncodingsManager.EncodingsUpdateListener, PotentialPacketHandler {

    private val eventEmitter: EventEmitter<AbstractEndpoint.EventHandler> = SyncEventEmitter()

    /**
     * The [Logger] used by the [Relay] class to print debug
     * information.
     */
    private val logger: Logger

    init {
        val context = HashMap<String, String>()
        context["relayId"] = id
        logger = parentLogger.createChildLogger(this.javaClass.name, context)
    }

    private val iceTransport = IceTransport(id, iceControlling, useUniquePort, logger)
    private val dtlsTransport = DtlsTransport(logger)

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("relay_id", id)
    }

    private val relayedEndpoints = HashMap<String, RelayedEndpoint>()
    private val endpointsBySsrc = HashMap<Long, RelayedEndpoint>()
    private val endpointsLock = Any()

    /**
     * The instance which manages the Colibri messaging (over web sockets).
     */
    private val _messageTransport = RelayMessageTransport(
        this,
        Supplier { conference.videobridge.statistics },
        conference,
        logger
    )

    fun getMessageTransport(): RelayMessageTransport = _messageTransport
    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        conference.videobridge.statistics.totalRelays.incrementAndGet()
    }

    /**
     * The queue we put outgoing SRTP packets onto so they can be sent
     * out via the [IceTransport] on an IO thread.
     */
    private val outgoingSrtpPacketQueue = PacketInfoQueue(
        "${javaClass.simpleName}-outgoing-packet-queue",
        TaskPools.IO_POOL,
        this::doSendSrtp,
        TransportConfig.queueSize
    ).apply {
        setErrorHandler(Endpoint.queueErrorCounter)
    }

    private fun setupIceTransport() {
        iceTransport.incomingDataHandler = object : IceTransport.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant) {
                // DTLS data will be handled by the DtlsTransport, but SRTP data can go
                // straight to the transceiver
                if (looksLikeDtls(data, offset, length)) {
                    // DTLS transport is responsible for making its own copy, because it will manage its own
                    // buffers
                    dtlsTransport.dtlsDataReceived(data, offset, length)
                } else {
                    val copy = ByteBufferPool.getBuffer(
                        length +
                            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                            Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                    )
                    System.arraycopy(data, offset, copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length)
                    val pktInfo =
                        OctoPacketInfo(
                            UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length)
                        ).apply {
                            this.receivedTime = receivedTime.toEpochMilli()
                        }
                    transceiver.handleIncomingPacket(pktInfo)
                }
            }
        }
        iceTransport.eventHandler = object : IceTransport.EventHandler {
            override fun connected() {
                logger.info("ICE connected")
                eventEmitter.fireEvent { iceSucceeded() }
                transceiver.setOutgoingPacketHandler(object : PacketHandler {
                    override fun processPacket(packetInfo: PacketInfo) {
                        outgoingSrtpPacketQueue.add(packetInfo)
                    }
                })
                TaskPools.IO_POOL.execute(iceTransport::startReadingData)
                TaskPools.IO_POOL.execute(dtlsTransport::startDtlsHandshake)
            }

            override fun failed() {
                eventEmitter.fireEvent { iceFailed() }
            }

            override fun consentUpdated(time: Instant) {
                transceiver.packetIOActivity.lastIceActivityInstant = time
            }
        }
    }

    private fun setupDtlsTransport() {
        dtlsTransport.incomingDataHandler = object : DtlsTransport.IncomingDataHandler {
            override fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int) {
                // TODO this@Relay.dtlsAppPacketReceived(buf, off, len)
            }
        }
        dtlsTransport.outgoingDataHandler = object : DtlsTransport.OutgoingDataHandler {
            override fun sendData(buf: ByteArray, off: Int, len: Int) {
                iceTransport.send(buf, off, len)
            }
        }
        dtlsTransport.eventHandler = object : DtlsTransport.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                logger.info("DTLS handshake complete")
                transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
                // TODO scheduleEndpointMessageTransportTimeout()
            }
        }
    }

    /**
     * Sets the remote transport information (ICE candidates, DTLS fingerprints).
     *
     * @param transportInfo the XML extension which contains the remote
     * transport information.
     */
    fun setTransportInfo(transportInfo: IceUdpTransportPacketExtension) {
        val remoteFingerprints = mutableMapOf<String, String>()
        val fingerprintExtensions = transportInfo.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java)
        fingerprintExtensions.forEach { fingerprintExtension ->
            if (fingerprintExtension.hash != null && fingerprintExtension.fingerprint != null) {
                remoteFingerprints[fingerprintExtension.hash] = fingerprintExtension.fingerprint
            } else {
                logger.info("Ignoring empty DtlsFingerprint extension: ${transportInfo.toXML()}")
            }
        }
        dtlsTransport.setRemoteFingerprints(remoteFingerprints)
        if (fingerprintExtensions.isNotEmpty()) {
            val setup = fingerprintExtensions.first().setup
            dtlsTransport.setSetupAttribute(setup)
        }
        iceTransport.startConnectivityEstablishment(transportInfo)

        val websocketExtension = transportInfo.getFirstChildOfType(WebSocketPacketExtension::class.java)
        if (websocketExtension?.url != null) {
            _messageTransport.connectTo(websocketExtension.url)
        }
    }

    /** Whether [describeTransport] has been called for this relay.
     */
    var transportDescribed: Boolean = false
        private set

    fun describeTransport(): IceUdpTransportPacketExtension {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)
        val wsPacketExtension = WebSocketPacketExtension()

        /* TODO: this should be dependent on videobridge.websockets.enabled, if we support that being
         *  disabled for relay.
         */
        if (_messageTransport.isActive) {
            wsPacketExtension.active = true
        } else {
            colibriWebSocketServiceSupplier.get()?.let { colibriWebsocketService ->
                colibriWebsocketService.getColibriRelayWebSocketUrl(
                    conference.id,
                    id,
                    iceTransport.icePassword
                )?.let { wsUrl ->
                    wsPacketExtension.url = wsUrl
                }
            }
        }
        iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension)

        transportDescribed = true

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }

        return iceUdpTransportPacketExtension
    }

    /**
     * Listen for RTT updates from [transceiver] and update the ICE stats the first time an RTT is available. Note that
     * the RTT is measured via RTCP, since we don't expose response time for STUN requests.
     */
    private val rttListener: EndpointConnectionStats.EndpointConnectionStatsListener =
        object : EndpointConnectionStats.EndpointConnectionStatsListener {
            override fun onRttUpdate(newRttMs: Double) {
                if (newRttMs > 0) {
                    transceiver.removeEndpointConnectionStatsListener(this)
                    iceTransport.updateStatsOnInitialRtt(newRttMs)
                }
            }
        }

    /* TODO: we eventually want a smarter Transceiver implementation, that splits processing by
     *  source endpoint or something similar, but for the initial implementation this should work.
     */
    val transceiver = Transceiver(
        id,
        TaskPools.CPU_POOL,
        TaskPools.CPU_POOL,
        TaskPools.SCHEDULED_POOL,
        diagnosticContext,
        logger,
        TransceiverEventHandlerImpl(),
        clock
    ).apply {
        setIncomingPacketHandler(object : ConsumerNode("receiver chain handler") {
            override fun consume(packetInfo: PacketInfo) {
                this@Relay.handleIncomingPacket(packetInfo)
            }

            override fun trace(f: () -> Unit) = f.invoke()
        })
        addEndpointConnectionStatsListener(rttListener)
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     */
    fun handleIncomingPacket(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        if (packet is RtpPacket) {
            val ep = synchronized(endpointsLock) { endpointsBySsrc[packet.ssrc] }
            if (ep != null) {
                packetInfo.endpointId = ep.id
            }
        }
        // TODO do we need to set endpointId for RTCP packets?  Otherwise handle them?
        conference.handleIncomingPacket(packetInfo)
    }

    override fun onNewSsrcAssociation(
        endpointId: String?,
        primarySsrc: Long,
        secondarySsrc: Long,
        type: SsrcAssociationType?
    ) {
        // TODO("Not yet implemented")
    }

    private fun doSendSrtp(packetInfo: PacketInfo): Boolean {
        /* TODO
        if (packetInfo.packet.looksLikeRtp()) {
            Endpoint.rtpPacketDelayStats.addPacket(packetInfo)
            bridgeJitterStats.packetSent(packetInfo)
        } else if (packetInfo.packet.looksLikeRtcp()) {
            Endpoint.rtcpPacketDelayStats.addPacket(packetInfo)
        }
         */

        packetInfo.sent()
        /* TODO
        if (timelineLogger.isTraceEnabled && Endpoint.logTimeline()) {
            timelineLogger.trace { packetInfo.timeline.toString() }
        }
         */
        iceTransport.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
        ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        return true
    }

    /**
     * Sends a specific msg to this endpoint over its bridge channel
     */
    fun sendMessage(msg: BridgeChannelMessage) = _messageTransport.sendMessage(msg)

    fun addRemoteEndpoint(
        id: String,
        audioSources: Collection<AudioSourceDesc>,
        videoSources: Collection<MediaSourceDesc>
    ) {
        val ep: RelayedEndpoint
        synchronized(endpointsLock) {
            if (relayedEndpoints.containsKey(id)) {
                logger.warn("Relay already contains remote endpoint with ID $id")
                updateRemoteEndpoint(id, audioSources, videoSources)
                return
            }
            ep = RelayedEndpoint(conference, this, id, logger)
            relayedEndpoints[id] = ep

            ep.ssrcs.forEach { ssrc -> endpointsBySsrc[ssrc] = ep }
        }

        conference.addEndpoints(setOf(ep))
    }

    fun updateRemoteEndpoint(
        id: String,
        audioSources: Collection<AudioSourceDesc>,
        videoSources: Collection<MediaSourceDesc>
    ) {
        val ep: RelayedEndpoint
        synchronized(endpointsLock) {
            ep = relayedEndpoints[id] ?: run {
                logger.warn("Endpoint with ID $id not found in relay")
                return
            }
            val oldSsrcs = ep.ssrcs

            ep.audioSources = audioSources.toTypedArray()
            ep.mediaSources = videoSources.toTypedArray()

            val newSsrcs = ep.ssrcs
            val removedSsrcs = oldSsrcs.minus(newSsrcs)
            val addedSsrcs = newSsrcs.minus(oldSsrcs)

            endpointsBySsrc.keys.removeAll(removedSsrcs)
            addedSsrcs.forEach { ssrc -> endpointsBySsrc[ssrc] = ep }
        }
    }

    fun removeRemoteEndpoint(id: String) {
        val ep: RelayedEndpoint?
        synchronized(endpointsLock) {
            ep = relayedEndpoints.remove(id)
            if (ep != null) {
                endpointsBySsrc.keys.removeAll(ep.ssrcs)
            }
        }
        if (ep != null) {
            conference.endpointExpired(ep)
        }
    }

    fun getEndpoint(id: String): RelayedEndpoint? = synchronized(endpointsLock) { relayedEndpoints[id] }

    /**
     * Checks whether a WebSocket connection with a specific password string
     * should be accepted for this relay.
     * @param password the
     * @return {@code true} iff the password matches.
     */
    fun acceptWebSocket(password: String): Boolean {
        if (iceTransport.icePassword != password) {
            logger.warn(
                "Incoming web socket request with an invalid password. " +
                    "Expected: ${iceTransport.icePassword} received $password"
            )
            return false
        }
        return true
    }

    /**
     * Returns true if this endpoint's transport is 'fully' connected (both ICE and DTLS), false otherwise
     */
    private fun isTransportConnected(): Boolean = iceTransport.isConnected() && dtlsTransport.isConnected

    /* If we're connected, forward everything that didn't come in over a relay.
        TODO: worry about bandwidth limits on relay links? */
    override fun wants(packet: PacketInfo): Boolean = isTransportConnected() && !(packet is OctoPacketInfo)

    override fun send(packet: PacketInfo) = transceiver.sendPacket(packet)

    fun expire() {
        logger.info("Expiring.")
        conference.relayExpired(this)

        try {
            // TODO updateStatsOnExpire()
            transceiver.stop()
            logger.cdebug { transceiver.getNodeStats().prettyPrint(0) }
            // TODO logger.cdebug { bitrateController.debugState.toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            // TODO logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            transceiver.teardown()
            _messageTransport.close()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        // bandwidthProbing.enabled = false
        // recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing)
        conference.encodingsManager.unsubscribe(this)

        dtlsTransport.stop()
        iceTransport.stop()
        outgoingSrtpPacketQueue.close()

        logger.info("Expired.")
    }

    private inner class TransceiverEventHandlerImpl : TransceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long) {
            val ep = synchronized(endpointsLock) { endpointsBySsrc[sourceSsrc] }
            if (ep != null) {
                conference.speechActivity.levelChanged(ep, level)
            }
        }

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            /* TODO
            bitrateController.bandwidthChanged(newValue.bps.toLong())
            bandwidthProbing.bandwidthEstimationChanged(newValue)
             */
        }
    }
}
