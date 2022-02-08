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

import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Transceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.nlj.util.sumOf
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.EncodingsManager
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.TransportConfig
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.octo.OctoPacketInfo
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.videobridge.stats.PacketTransitStats
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.jitsi.videobridge.websocket.colibriWebSocketServiceSupplier
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
     * True if the ICE agent for this [Relay] will be initialized to serve as a controlling ICE agent, false otherwise.
     */
    iceControlling: Boolean,
    useUniquePort: Boolean,
    clock: Clock = Clock.systemUTC()
) : EncodingsManager.EncodingsUpdateListener, PotentialPacketHandler {

    private val eventEmitter: EventEmitter<AbstractEndpoint.EventHandler> = SyncEventEmitter()

    /**
     * The [Logger] used by the [Relay] class to print debug information.
     */
    private val logger = createChildLogger(parentLogger).apply { addContext("relayId", id) }

    /**
     * A cache of the signaled payload types, since these are only signaled
     * at the top level but apply to all relayed endpoints
     */
    private val payloadTypes: MutableList<PayloadType> = ArrayList()

    /**
     * A cache of the signaled rtp extensions, since these are only signaled
     * at the top level but apply to all relayed endpoints
     */
    private val rtpExtensions: MutableList<RtpExtension> = ArrayList()

    /**
     * The indicator which determines whether [expire] has been called on this [Relay].
     */
    private var expired = false

    private val iceTransport = IceTransport(id, iceControlling, useUniquePort, logger, clock)
    private val dtlsTransport = DtlsTransport(logger)

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("relay_id", id)
    }

    private val timelineLogger = logger.createChildLogger("timeline.${this.javaClass.name}")

    private val relayedEndpoints = HashMap<String, RelayedEndpoint>()
    private val endpointsBySsrc = HashMap<Long, RelayedEndpoint>()
    private val endpointsLock = Any()

    val statistics = Statistics()

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
        setLocalSsrc(MediaType.AUDIO, conference.localAudioSsrc)
        setLocalSsrc(MediaType.VIDEO, conference.localVideoSsrc)
    }

    /**
     * The instance which manages the Colibri messaging (over web sockets).
     */
    private val messageTransport = RelayMessageTransport(
        this,
        Supplier { conference.videobridge.statistics },
        conference,
        logger
    )

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        conference.videobridge.statistics.totalRelays.incrementAndGet()
    }

    fun getMessageTransport(): RelayMessageTransport = messageTransport

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
        setErrorHandler(queueErrorCounter)
    }

    val debugState: JSONObject
        get() = JSONObject().apply {
            put("iceTransport", iceTransport.getDebugState())
            put("dtlsTransport", dtlsTransport.getDebugState())
            put("transceiver", transceiver.getNodeStats().toJson())
            put("messageTransport", messageTransport.debugState)
            val remoteEndpoints = JSONObject()
            for (r in relayedEndpoints.values) {
                remoteEndpoints[r.id] = r.debugState
            }
            put("remoteEndpoints", remoteEndpoints)
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
                            this.receivedTime = receivedTime
                        }
                    handleMediaPacket(pktInfo)
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
                setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
                scheduleRelayMessageTransportTimeout()
            }
        }
    }

    var srtpTransformers: SrtpTransformers? = null

    private fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        logger.cdebug {
            "Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "tls role: $tlsRole"
        }
        val srtpTransformers = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            tlsRole,
            logger
        )
        this.srtpTransformers = srtpTransformers

        transceiver.setSrtpInformation(srtpTransformers)
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { it.setSrtpInformation(srtpTransformers) }
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
        websocketExtension?.url?.let { messageTransport.connectTo(it) }
    }

    fun describeTransport(): IceUdpTransportPacketExtension {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)
        val wsPacketExtension = WebSocketPacketExtension()

        /* TODO: this should be dependent on videobridge.websockets.enabled, if we support that being
         *  disabled for relay.
         */
        if (messageTransport.isActive) {
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

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }

        return iceUdpTransportPacketExtension
    }

    fun setFeature(feature: EndpointDebugFeatures, enabled: Boolean) {
        when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> transceiver.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled)
        }
    }

    fun isFeatureEnabled(feature: EndpointDebugFeatures): Boolean {
        return when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> transceiver.isFeatureEnabled(Features.TRANSCEIVER_PCAP_DUMP)
        }
    }

    fun isSendingAudio(): Boolean = transceiver.isReceivingAudio()

    fun isSendingVideo(): Boolean = transceiver.isReceivingVideo()

    /**
     * Handle media packets that have arrived, using the appropriate endpoint's transceiver.
     */
    private fun handleMediaPacket(packetInfo: OctoPacketInfo) {
        if (packetInfo.packet.looksLikeRtp()) {
            val ssrc = RtpHeader.getSsrc(packetInfo.packet.buffer, packetInfo.packet.offset)
            val ep = getEndpointBySsrc(ssrc)
            if (ep != null) {
                ep.handleIncomingPacket(packetInfo)
                return
            } else {
                logger.warn { "RTP Packet received for unknown endpoint SSRC $ssrc" }
                BufferPool.returnBuffer(packetInfo.packet.buffer)
            }
        } else if (packetInfo.packet.looksLikeRtcp()) {
            val ssrc = RtcpHeader.getSenderSsrc(packetInfo.packet.buffer, packetInfo.packet.offset)
            val ep = getEndpointBySsrc(ssrc)
            if (ep != null) {
                ep.handleIncomingPacket(packetInfo)
                return
            } else {
                /* Handle RTCP from non-endpoint senders on the generic transceiver - it's probably
                 * from a feedback source.
                 */
                transceiver.handleIncomingPacket(packetInfo)
            }
        }
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
        endpointId: String,
        primarySsrc: Long,
        secondarySsrc: Long,
        type: SsrcAssociationType
    ) {
        if (synchronized(endpointsLock) { relayedEndpoints.containsKey(endpointId) }) {
            transceiver.addSsrcAssociation(LocalSsrcAssociation(primarySsrc, secondarySsrc, type))
        } else {
            transceiver.addSsrcAssociation(RemoteSsrcAssociation(primarySsrc, secondarySsrc, type))
        }
    }

    private fun doSendSrtp(packetInfo: PacketInfo): Boolean {
        PacketTransitStats.packetSent(packetInfo)

        packetInfo.sent()

        if (timelineLogger.isTraceEnabled && Endpoint.logTimeline()) {
            timelineLogger.trace { packetInfo.timeline.toString() }
        }

        iceTransport.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
        ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        return true
    }

    /**
     * Sends a specific message to the remote side.
     */
    fun sendMessage(msg: BridgeChannelMessage) = messageTransport.sendMessage(msg)

    fun relayMessageTransportConnected() {
        relayedEndpoints.values.forEach { e -> e.relayMessageTransportConnected() }
    }

    fun addRemoteEndpoint(
        id: String,
        statsId: String?,
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
            ep.statsId = statsId
            ep.audioSources = audioSources.toTypedArray()
            ep.mediaSources = videoSources.toTypedArray()

            relayedEndpoints[id] = ep

            ep.ssrcs.forEach { ssrc -> endpointsBySsrc[ssrc] = ep }
        }

        conference.addEndpoints(setOf(ep))

        srtpTransformers?.let { ep.setSrtpInformation(it) }
        payloadTypes.forEach { payloadType -> ep.addPayloadType(payloadType) }
        rtpExtensions.forEach { rtpExtension -> ep.addRtpExtension(rtpExtension) }

        setEndpointMediaSources(ep, audioSources, videoSources)
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
        setEndpointMediaSources(ep, audioSources, videoSources)
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

    fun addPayloadType(payloadType: PayloadType) {
        transceiver.addPayloadType(payloadType)
        payloadTypes.add(payloadType)
        relayedEndpoints.values.forEach { ep -> ep.addPayloadType(payloadType) }
    }

    fun addRtpExtension(rtpExtension: RtpExtension) {
        transceiver.addRtpExtension(rtpExtension)
        rtpExtensions.add(rtpExtension)
        relayedEndpoints.values.forEach { ep -> ep.addRtpExtension(rtpExtension) }
    }

    private fun setEndpointMediaSources(
        ep: RelayedEndpoint,
        audioSources: Collection<AudioSourceDesc>,
        videoSources: Collection<MediaSourceDesc>
    ) {
        ep.audioSources = audioSources.toTypedArray()
        ep.mediaSources = videoSources.toTypedArray()
    }

    fun getEndpoint(id: String): RelayedEndpoint? = synchronized(endpointsLock) { relayedEndpoints[id] }

    fun getEndpointBySsrc(ssrc: Long): RelayedEndpoint? = synchronized(endpointsLock) { endpointsBySsrc[ssrc] }

    /**
     * Schedule a timeout to fire log a message and track a stat if we don't
     * have a relay message transport connected within the timeout.
     */
    fun scheduleRelayMessageTransportTimeout() {
        TaskPools.SCHEDULED_POOL.schedule(
            {
                if (!expired) {
                    if (!messageTransport.isConnected) {
                        logger.error("RelayMessageTransport still not connected.")
                        conference.videobridge.statistics.numRelaysNoMessageTransportAfterDelay.incrementAndGet()
                    }
                }
            },
            30,
            TimeUnit.SECONDS
        )
    }

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
    override fun wants(packet: PacketInfo): Boolean = isTransportConnected() && packet !is OctoPacketInfo

    override fun send(packet: PacketInfo) = transceiver.sendPacket(packet)

    /**
     * Updates the conference statistics with value from this endpoint. Since
     * the values are cumulative this should execute only once when the endpoint
     * expires.
     */
    private fun updateStatsOnExpire() {
        val conferenceStats = conference.statistics
        val transceiverStats = transceiver.getTransceiverStats()

        // Add stats from the local transceiver
        val incomingStats = transceiverStats.rtpReceiverStats.packetStreamStats
        val outgoingStats = transceiverStats.outgoingPacketStreamStats

        statistics.bytesReceived.getAndAdd(incomingStats.bytes)
        statistics.packetsReceived.getAndAdd(incomingStats.packets)
        statistics.bytesSent.getAndAdd(outgoingStats.bytes)
        statistics.packetsSent.getAndAdd(outgoingStats.packets)

        conferenceStats.apply {
            totalRelayBytesReceived.addAndGet(statistics.bytesReceived.get())
            totalRelayPacketsReceived.addAndGet(statistics.packetsReceived.get())
            totalRelayBytesSent.addAndGet(statistics.bytesSent.get())
            totalRelayPacketsSent.addAndGet(statistics.packetsSent.get())
        }

        conference.videobridge.statistics.apply {
            /* TODO: should these be separate stats from the endpoint stats? */
            totalKeyframesReceived.addAndGet(transceiverStats.rtpReceiverStats.videoParserStats.numKeyframes)
            totalLayeringChangesReceived.addAndGet(
                transceiverStats.rtpReceiverStats.videoParserStats.numLayeringChanges
            )

            val durationActiveVideo = transceiverStats.rtpReceiverStats.incomingStats.ssrcStats.values.filter {
                it.mediaType == MediaType.VIDEO
            }.sumOf { it.durationActive }
            totalVideoStreamMillisecondsReceived.addAndGet(durationActiveVideo.toMillis())
        }
    }

    fun expire() {
        expired = true
        logger.info("Expiring.")
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { conference.endpointExpired(it) }
        }
        conference.relayExpired(this)

        try {
            updateStatsOnExpire()
            transceiver.stop()
            logger.cdebug { transceiver.getNodeStats().prettyPrint(0) }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            transceiver.teardown()
            messageTransport.close()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        conference.encodingsManager.unsubscribe(this)

        dtlsTransport.stop()
        iceTransport.stop()
        outgoingSrtpPacketQueue.close()

        logger.info("Expired.")
    }

    companion object {
        /**
         * Count the number of dropped packets and exceptions.
         */
        @JvmField
        val queueErrorCounter = CountingErrorHandler()
    }

    class Statistics {
        val bytesReceived = AtomicLong(0)
        val packetsReceived = AtomicLong(0)
        val bytesSent = AtomicLong(0)
        val packetsSent = AtomicLong(0)

        private fun getJson(): JSONObject {
            val jsonObject = JSONObject()
            jsonObject["bytes_received"] = bytesReceived.get()
            jsonObject["bytes_sent"] = bytesSent.get()
            jsonObject["packets_received"] = packetsReceived.get()
            jsonObject["packets_sent"] = packetsSent.get()
            return jsonObject
        }
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
            /* We don't use BWE for relay connections. */
        }
    }

    interface IncomingRelayPacketHandler {
        fun handleIncomingPacket(packetInfo: OctoPacketInfo)
    }
}
