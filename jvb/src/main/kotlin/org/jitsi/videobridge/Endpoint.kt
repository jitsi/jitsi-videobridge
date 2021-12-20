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

import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Transceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.BridgeJitterStats
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.PacketDelayStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.concurrent.RecurringRunnableExecutor
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.mins
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.cc.BandwidthProbing
import org.jitsi.videobridge.cc.allocation.BandwidthAllocation
import org.jitsi.videobridge.cc.allocation.BitrateController
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.datachannel.DataChannelStack
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket
import org.jitsi.videobridge.datachannel.protocol.DataChannelProtocolConstants
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ForwardedEndpointsMessage
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.message.SenderVideoConstraintsMessage
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.videobridge.sctp.SctpConfig
import org.jitsi.videobridge.sctp.SctpManager
import org.jitsi.videobridge.shim.ChannelShim
import org.jitsi.videobridge.stats.DoubleAverage
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.jitsi.videobridge.websocket.colibriWebSocketServiceSupplier
import org.jitsi.videobridge.xmpp.MediaSourceFactory
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi_modified.sctp4j.SctpDataCallback
import org.jitsi_modified.sctp4j.SctpServerSocket
import org.jitsi_modified.sctp4j.SctpSocket
import org.json.simple.JSONObject
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/**
 * Models a local endpoint (participant) in a [Conference]
 */
class Endpoint @JvmOverloads constructor(
    id: String,
    conference: Conference,
    parentLogger: Logger,
    /**
     * True if the ICE agent for this Endpoint will be initialized to serve
     * as a controlling ICE agent, false otherwise
     */
    iceControlling: Boolean,
    private val clock: Clock = Clock.systemUTC()
) : AbstractEndpoint(conference, id, parentLogger), PotentialPacketHandler, EncodingsManager.EncodingsUpdateListener {
    /**
     * The time at which this endpoint was created
     */
    private val creationTime = clock.instant()

    private val sctpHandler = SctpHandler()
    private val dataChannelHandler = DataChannelHandler()

    private val iceTransport = IceTransport(id, iceControlling, logger)
    private val dtlsTransport = DtlsTransport(logger)

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("endpoint_id", id)
    }

    private val timelineLogger = logger.createChildLogger("timeline.${this.javaClass.name}")

    /**
     * Measures the jitter introduced by the bridge itself (i.e. jitter calculated between
     * packets based on the time they were received by the bridge and the time they
     * are sent).  This jitter value is calculated independently, per packet, by every
     * individual [Endpoint] and their jitter values are averaged together.
     */
    private val bridgeJitterStats = BridgeJitterStats()

    /**
     * The [SctpManager] instance we'll use to manage the SCTP connection
     */
    private var sctpManager: SctpManager? = null

    private var dataChannelStack: DataChannelStack? = null

    /**
     * The [SctpSocket] for this endpoint, if an SCTP connection was
     * negotiated.
     */
    private var sctpSocket: Optional<SctpServerSocket> = Optional.empty()

    /**
     * The set of [ChannelShim]s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed. The set of channels shims allows to determine if endpoint
     * can accept audio or video.
     */
    private val channelShims = mutableSetOf<ChannelShim>()

    /**
     * Whether this endpoint should accept audio packets. We set this according
     * to whether the endpoint has an audio Colibri channel whose direction
     * allows sending.
     */
    var acceptAudio = false
        private set

    /**
     * Whether this endpoint should accept video packets. We set this according
     * to whether the endpoint has a video Colibri channel whose direction
     * allows sending.
     */
    var acceptVideo = false
        private set

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

    /**
     * The queue which enforces sequential processing of incoming data channel messages
     * to maintain processing order.
     */
    private val incomingDataChannelMessagesQueue = PacketInfoQueue(
        "${javaClass.simpleName}-incoming-data-channel-queue",
        TaskPools.IO_POOL,
        { packetInfo ->
            dataChannelHandler.consume(packetInfo)
            true
        },
        TransportConfig.queueSize
    )

    private val bitrateController = BitrateController(
        object : BitrateController.EventHandler {
            override fun allocationChanged(allocation: BandwidthAllocation) {
                // Intentional no-op
            }

            override fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>) =
                sendForwardedEndpointsMessage(forwardedEndpoints)

            override fun effectiveVideoConstraintsChanged(
                oldEffectiveConstraints: Map<String, VideoConstraints>,
                newEffectiveConstraints: Map<String, VideoConstraints>
            ) = this@Endpoint.effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints)

            override fun keyframeNeeded(endpointId: String?, ssrc: Long) =
                conference.requestKeyframe(endpointId, ssrc)
        },
        Supplier { getOrderedEndpoints() },
        diagnosticContext,
        logger
    )

    /**
     * The instance which manages the Colibri messaging (over a data channel
     * or web sockets).
     */
    private val _messageTransport = EndpointMessageTransport(
        this,
        Supplier { conference.videobridge.statistics },
        conference,
        logger
    )

    override fun getMessageTransport(): EndpointMessageTransport = _messageTransport

    /**
     * Gets the endpoints in the conference in LastN order, with this {@link Endpoint} removed.
     */
    fun getOrderedEndpoints(): List<AbstractEndpoint> =
        conference.orderedEndpoints.filterNot { it == this }

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
                this@Endpoint.handleIncomingPacket(packetInfo)
            }

            override fun trace(f: () -> Unit) = f.invoke()
        })
        addEndpointConnectionStatsListener(rttListener)
    }

    private val bandwidthProbing = BandwidthProbing(
        object : BandwidthProbing.ProbingDataSender {
            override fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int {
                return transceiver.sendProbing(mediaSsrcs, numBytes)
            }
        },
        Supplier { bitrateController.getStatusSnapshot() }
    ).apply {
        diagnosticsContext = this@Endpoint.diagnosticContext
        enabled = true
    }.also {
        recurringRunnableExecutor.registerRecurringRunnable(it)
    }

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        conference.videobridge.statistics.totalEndpoints.incrementAndGet()
    }

    override var mediaSources: Array<MediaSourceDesc>
        get() = transceiver.getMediaSources()
        private set(value) {
            val wasEmpty = transceiver.getMediaSources().isEmpty()
            if (transceiver.setMediaSources(value)) {
                eventEmitter.fireEvent { sourcesChanged() }
            }
            if (wasEmpty) {
                sendVideoConstraints(maxReceiverVideoConstraints)
            }
        }

    override val mediaSource: MediaSourceDesc?
        get() = mediaSources.firstOrNull()

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
                        PacketInfo(UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length)).apply {
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

    /**
     * Whether this endpoint has any endpoints "on-stage".
     */
    fun isInStageView() = bitrateController.allocationSettings.onStageEndpoints.isNotEmpty()

    private fun setupDtlsTransport() {
        dtlsTransport.incomingDataHandler = object : DtlsTransport.IncomingDataHandler {
            override fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int) {
                this@Endpoint.dtlsAppPacketReceived(buf, off, len)
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
                // TODO(brian): the old code would work even if the sctp connection was created after
                //  the handshake had completed, but this won't (since this is a one-time event).  do
                //  we need to worry about that case?
                sctpSocket.ifPresent(::acceptSctpConnection)
                scheduleEndpointMessageTransportTimeout()
            }
        }
    }

    fun updateForceMute() {
        var audioForceMuted = false
        var videoForceMuted = false
        channelShims.forEach { channelShim ->
            if (!channelShim.allowIncomingMedia()) {
                when (channelShim.mediaType) {
                    MediaType.AUDIO -> audioForceMuted = true
                    MediaType.VIDEO -> videoForceMuted = true
                    else -> Unit
                }
            }
        }

        transceiver.forceMuteAudio(audioForceMuted)
        transceiver.forceMuteVideo(videoForceMuted)
    }

    override fun addPayloadType(payloadType: PayloadType) {
        transceiver.addPayloadType(payloadType)
        bitrateController.addPayloadType(payloadType)
    }

    override fun addRtpExtension(rtpExtension: RtpExtension) = transceiver.addRtpExtension(rtpExtension)

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        logger.cdebug { "Adding receive ssrc $ssrc of type $mediaType" }
        transceiver.addReceiveSsrc(ssrc, mediaType)
    }

    override fun onNewSsrcAssociation(
        endpointId: String,
        primarySsrc: Long,
        secondarySsrc: Long,
        type: SsrcAssociationType
    ) {
        if (endpointId.equals(id, ignoreCase = true)) {
            transceiver.addSsrcAssociation(LocalSsrcAssociation(primarySsrc, secondarySsrc, type))
        } else {
            transceiver.addSsrcAssociation(RemoteSsrcAssociation(primarySsrc, secondarySsrc, type))
        }
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

    override fun isSendingAudio(): Boolean {
        // The endpoint is sending audio if we (the transceiver) are receiving audio.
        return transceiver.isReceivingAudio()
    }
    override fun isSendingVideo(): Boolean {
        // The endpoint is sending video if we (the transceiver) are receiving video.
        return transceiver.isReceivingVideo()
    }

    private fun doSendSrtp(packetInfo: PacketInfo): Boolean {
        if (packetInfo.packet.looksLikeRtp()) {
            rtpPacketDelayStats.addPacket(packetInfo)
            bridgeJitterStats.packetSent(packetInfo)
        } else if (packetInfo.packet.looksLikeRtcp()) {
            rtcpPacketDelayStats.addPacket(packetInfo)
        }

        packetInfo.sent()
        if (timelineLogger.isTraceEnabled && logTimeline()) {
            timelineLogger.trace { packetInfo.timeline.toString() }
        }
        iceTransport.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
        ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        return true
    }

    /**
     * Notifies this endpoint that the ordered list of last-n endpoints has changed
     */
    fun lastNEndpointsChanged() = bitrateController.endpointOrderingChanged()

    /**
     * Sends a specific msg to this endpoint over its bridge channel
     */
    override fun sendMessage(msg: BridgeChannelMessage) = messageTransport.sendMessage(msg)

    /**
     * Adds [channelShim] channel to this endpoint.
     */
    fun addChannel(channelShim: ChannelShim) {
        if (channelShims.add(channelShim)) {
            updateAcceptedMediaTypes()
        }
    }

    /**
     * Removes a specific [ChannelShim] from this endpoint.
     */
    fun removeChannel(channelShim: ChannelShim) {
        if (channelShims.remove(channelShim)) {
            if (channelShims.isEmpty()) {
                expire()
            } else {
                updateAcceptedMediaTypes()
            }
        }
    }

    // TODO: this should be part of an EndpointMessageTransport.EventHandler interface
    fun endpointMessageTransportConnected() =
        sendVideoConstraints(maxReceiverVideoConstraints)

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     */
    // TODO(brian): change sctp handler to take buf, off, len
    fun dtlsAppPacketReceived(data: ByteArray, off: Int, len: Int) =
        sctpHandler.processPacket(PacketInfo(UnparsedPacket(data, off, len)))

    fun effectiveVideoConstraintsChanged(
        oldEffectiveConstraints: Map<String, VideoConstraints>,
        newEffectiveConstraints: Map<String, VideoConstraints>
    ) {
        val removedEndpoints = oldEffectiveConstraints.keys.filterNot { it in newEffectiveConstraints.keys }

        // Sources that "this" endpoint no longer receives.
        for (removedEpId in removedEndpoints) {
            // Remove ourself as a receiver from that endpoint
            conference.getEndpoint(removedEpId)?.removeReceiver(id)
        }

        // Added or updated
        newEffectiveConstraints.forEach { (epId, effectiveConstraints) ->
            conference.getEndpoint(epId)?.addReceiver(id, effectiveConstraints)
        }
    }

    override fun sendVideoConstraints(maxVideoConstraints: VideoConstraints) {
        // Note that it's up to the client to respect these constraints.
        if (mediaSource == null) {
            logger.cdebug { "Suppressing sending a SenderVideoConstraints message, endpoint has no streams." }
        } else {
            val senderVideoConstraintsMessage = SenderVideoConstraintsMessage(maxVideoConstraints.maxHeight)
            logger.cdebug { "Sender constraints changed: ${senderVideoConstraintsMessage.toJson()}" }
            sendMessage(senderVideoConstraintsMessage)
        }
    }

    /**
     * Create an SCTP connection for this Endpoint.  If [openDataChannelLocally] is true,
     * we will create the data channel locally, otherwise we will wait for the remote side
     * to open it.
     */
    fun createSctpConnection() {
        logger.cdebug { "Creating SCTP manager" }
        // Create the SctpManager and provide it a method for sending SCTP data
        sctpManager = SctpManager(
            { data, offset, length ->
                dtlsTransport.sendDtlsData(data, offset, length)
                0
            },
            logger
        )
        sctpHandler.setSctpManager(sctpManager!!)
        // NOTE(brian): as far as I know we always act as the 'server' for sctp
        // connections, but if not we can make which type we use dynamic
        val socket = sctpManager!!.createServerSocket()
        socket.eventHandler = object : SctpSocket.SctpSocketEventHandler {
            override fun onReady() {
                logger.info("SCTP connection is ready, creating the Data channel stack")
                dataChannelStack = DataChannelStack(
                    { data, sid, ppid -> socket.send(data, true, sid, ppid) },
                    logger
                )
                // This handles if the remote side will be opening the data channel
                dataChannelStack!!.onDataChannelStackEvents { dataChannel ->
                    logger.info("Remote side opened a data channel.")
                    _messageTransport.setDataChannel(dataChannel)
                }
                dataChannelHandler.setDataChannelStack(dataChannelStack!!)
                if (openDataChannelLocally) {
                    // This logic is for opening the data channel locally
                    logger.info("Will open the data channel.")
                    val dataChannel = dataChannelStack!!.createDataChannel(
                        DataChannelProtocolConstants.RELIABLE,
                        0,
                        0,
                        0,
                        "default"
                    )
                    _messageTransport.setDataChannel(dataChannel)
                    dataChannel.open()
                } else {
                    logger.info("Will wait for the remote side to open the data channel.")
                }
            }

            override fun onDisconnected() {
                logger.info("SCTP connection is disconnected")
            }
        }
        socket.dataCallback = SctpDataCallback { data, sid, ssn, tsn, ppid, context, flags ->
            // We assume all data coming over SCTP will be datachannel data
            val dataChannelPacket = DataChannelPacket(data, 0, data.size, sid, ppid.toInt())
            // Post the rest of the task here because the current context is
            // holding a lock inside the SctpSocket which can cause a deadlock
            // if two endpoints are trying to send datachannel messages to one
            // another (with stats broadcasting it can happen often)
            incomingDataChannelMessagesQueue.add(PacketInfo(dataChannelPacket))
        }
        socket.listen()
        sctpSocket = Optional.of(socket)
    }

    fun acceptSctpConnection(sctpServerSocket: SctpServerSocket) {
        TaskPools.IO_POOL.execute {
            // We don't want to block the thread calling
            // onDtlsHandshakeComplete so run the socket acceptance in an IO
            // pool thread
            // FIXME: This runs forever once the socket is closed (
            // accept never returns true).
            logger.info("Attempting to establish SCTP socket connection")
            var attempts = 0
            while (!sctpServerSocket.accept()) {
                attempts++
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                if (attempts > 100) {
                    logger.error("Timed out waiting for SCTP connection from remote side")
                    break
                }
            }
            logger.cdebug { "SCTP socket ${sctpServerSocket.hashCode()} accepted connection" }
        }
    }

    /**
     * Schedule a timeout to fire log a message and track a stat if we don't
     * have an endpoint message transport connected within the timeout.
     */
    fun scheduleEndpointMessageTransportTimeout() {
        TaskPools.SCHEDULED_POOL.schedule(
            {
                if (!isExpired) {
                    if (!messageTransport.isConnected) {
                        logger.error("EndpointMessageTransport still not connected.")
                        conference.videobridge.statistics.numEndpointsNoMessageTransportAfterDelay.incrementAndGet()
                    }
                }
            },
            30,
            TimeUnit.SECONDS
        )
    }

    /**
     * Checks whether a WebSocket connection with a specific password string
     * should be accepted for this endpoint.
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
     * Sends a message to this endpoint in order to notify it that the set of endpoints for which the bridge
     * is sending video has changed.
     *
     * @param forwardedEndpoints the collection of forwarded endpoints.
     */
    fun sendForwardedEndpointsMessage(forwardedEndpoints: Collection<String>) {
        val msg = ForwardedEndpointsMessage(forwardedEndpoints)
        TaskPools.IO_POOL.execute {
            try {
                sendMessage(msg)
            } catch (t: Throwable) {
                logger.warn("Failed to send message:", t)
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
    }

    override fun describe(channelBundle: ColibriConferenceIQ.ChannelBundle) {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)
        colibriWebSocketServiceSupplier.get()?.let { colibriWebsocketService ->
            colibriWebsocketService.getColibriWebSocketUrl(
                conference.id,
                id,
                iceTransport.icePassword
            )?.let { wsUrl ->
                val wsPacketExtension = WebSocketPacketExtension(wsUrl)
                iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension)
            }
        }

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }
        channelBundle.transport = iceUdpTransportPacketExtension
    }

    /**
     * Update media direction of the [ChannelShim]s associated
     * with this Endpoint.
     *
     * When media direction is set to 'sendrecv' JVB will
     * accept incoming media from endpoint and forward it to
     * other endpoints in a conference. Other endpoint's media
     * will also be forwarded to current endpoint.
     * When media direction is set to 'sendonly' JVB will
     * NOT accept incoming media from this endpoint (not yet implemented), but
     * media from other endpoints will be forwarded to this endpoint.
     * When media direction is set to 'recvonly' JVB will
     * accept incoming media from this endpoint, but will not forward
     * other endpoint's media to this endpoint.
     * When media direction is set to 'inactive' JVB will
     * neither accept incoming media nor forward media from other endpoints.
     *
     * @param type media type.
     * @param direction desired media direction:
     *                       'sendrecv', 'sendonly', 'recvonly', 'inactive'
     */
    @Suppress("unused") // Used by plugins (Yuri)
    fun updateMediaDirection(type: MediaType, direction: String) {
        when (direction) {
            "sendrecv", "sendonly", "recvonly", "inactive" -> {
                channelShims.firstOrNull { it.mediaType == type }?.setDirection(direction)
            }
            else -> {
                throw IllegalArgumentException("Media direction unknown: $direction")
            }
        }
    }

    /**
     * Re-creates this endpoint's media sources based on the sources
     * and source groups that have been signaled.
     */
    override fun recreateMediaSources() {
        val videoChannels = channelShims.filter { c -> c.mediaType == MediaType.VIDEO }

        val sources = videoChannels
            .mapNotNull(ChannelShim::getSources)
            .flatten()
            .toList()

        val sourceGroups = videoChannels
            .mapNotNull(ChannelShim::getSourceGroups)
            .flatten()
            .toList()

        if (sources.isNotEmpty() || sourceGroups.isNotEmpty()) {
            mediaSources = MediaSourceFactory.createMediaSources(sources, sourceGroups)
        }
    }

    /**
     * Update accepted media types based on [ChannelShim] permission to receive media
     */
    fun updateAcceptedMediaTypes() {
        var acceptAudio = false
        var acceptVideo = false
        channelShims.forEach { channelShim ->
            // The endpoint accepts audio packets (in the sense of accepting
            // packets from other endpoints being forwarded to it) if it has
            // an audio channel whose direction allows sending packets.
            if (channelShim.allowsSendingMedia()) {
                when (channelShim.mediaType) {
                    MediaType.AUDIO -> acceptAudio = true
                    MediaType.VIDEO -> acceptVideo = true
                    else -> Unit
                }
            }
        }
        this.acceptAudio = acceptAudio
        this.acceptVideo = acceptVideo
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     */
    fun handleIncomingPacket(packetInfo: PacketInfo) {
        packetInfo.endpointId = id
        conference.handleIncomingPacket(packetInfo)
    }

    /**
     * Return the timestamp of the most recently created [ChannelShim] on this endpoint
     */
    fun getMostRecentChannelCreatedTime(): Instant {
        return channelShims
            .map(ChannelShim::getCreationTimestamp)
            .maxOrNull() ?: NEVER
    }

    override fun receivesSsrc(ssrc: Long): Boolean = transceiver.receivesSsrc(ssrc)

    override fun getLastIncomingActivity(): Instant = transceiver.packetIOActivity.lastIncomingActivityInstant

    override fun requestKeyframe() = transceiver.requestKeyFrame()

    override fun requestKeyframe(mediaSsrc: Long) = transceiver.requestKeyFrame(mediaSsrc)

    /** Whether we are currently oversending to this endpoint. */
    fun isOversending(): Boolean = bitrateController.isOversending()

    fun setSelectedEndpoints(selectedEndpoints: List<String>) =
        bitrateController.setSelectedEndpoints(selectedEndpoints)

    /**
     * Returns how many endpoints this Endpoint is currently forwarding video for
     */
    fun numForwardedEndpoints(): Int = bitrateController.numForwardedEndpoints()

    fun setMaxFrameHeight(maxFrameHeight: Int) = bitrateController.setMaxFrameHeight(maxFrameHeight)

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage) =
        bitrateController.setBandwidthAllocationSettings(message)

    override fun send(packetInfo: PacketInfo) {
        when (val packet = packetInfo.packet) {
            is VideoRtpPacket -> {
                if (bitrateController.transformRtp(packetInfo)) {
                    // The original packet was transformed in place.
                    transceiver.sendPacket(packetInfo)
                } else {
                    logger.warn("Dropping a packet which was supposed to be accepted:$packet")
                }
                return
            }
            is RtcpSrPacket -> {
                // Allow the BC to update the timestamp (in place).
                bitrateController.transformRtcp(packet)
                logger.trace {
                    "relaying an sr from ssrc=${packet.senderSsrc}, timestamp=${packet.senderInfo.rtpTimestamp}"
                }
            }
        }
        transceiver.sendPacket(packetInfo)
    }

    /**
     * Previously, an endpoint expired when all of its channels did.  Channels
     * now only exist in their 'shim' form for backwards compatibility, so to
     * find out whether or not the endpoint expired, we'll check the activity
     * timestamps from the transceiver and use the largest of the expire times
     * set in the channel shims.
     */
    override fun shouldExpire(): Boolean {
        if (iceTransport.hasFailed()) {
            logger.warn("Allowing to expire because ICE failed.")
            return true
        }

        val maxExpireTimeFromChannelShims = channelShims
            .map(ChannelShim::getExpire)
            .map { Duration.ofSeconds(it.toLong()) }
            .maxOrNull() ?: Duration.ZERO

        val lastActivity = lastIncomingActivity
        val now = clock.instant()

        if (lastActivity == NEVER) {
            val timeSinceCreation = Duration.between(creationTime, now)
            if (timeSinceCreation > epTimeout) {
                logger.info(
                    "Endpoint's ICE connection has neither failed nor connected " +
                        "after $timeSinceCreation expiring"
                )
                return true
            }
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
            return false
        }
        if (Duration.between(lastActivity, now) > maxExpireTimeFromChannelShims) {
            logger.info("Allowing to expire because of no activity in over $maxExpireTimeFromChannelShims")
            return true
        }
        return false
    }

    fun setLastN(lastN: Int) {
        bitrateController.lastN = lastN
    }

    fun getLastN(): Int = bitrateController.lastN

    /**
     * Set the local SSRC for [mediaType] to [ssrc] for this endpoint.
     */
    fun setLocalSsrc(mediaType: MediaType, ssrc: Long) = transceiver.setLocalSsrc(mediaType, ssrc)

    /**
     * Returns true if this endpoint's transport is 'fully' connected (both ICE and DTLS), false otherwise
     */
    private fun isTransportConnected(): Boolean = iceTransport.isConnected() && dtlsTransport.isConnected

    fun getRtt(): Double = transceiver.getTransceiverStats().endpointConnectionStats.rtt

    override fun wants(packetInfo: PacketInfo): Boolean {
        if (!isTransportConnected()) {
            return false
        }

        return when (val packet = packetInfo.packet) {
            is VideoRtpPacket -> acceptVideo && bitrateController.accept(packetInfo)
            is AudioRtpPacket -> acceptAudio
            is RtcpSrPacket -> {
                // TODO: For SRs we're only interested in the ntp/rtp timestamp
                //  association, so we could only accept srs from the main ssrc
                bitrateController.accept(packet)
            }
            is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                // We assume that we are only given PLIs/FIRs destined for this
                // endpoint. This is because Conference has to find the target
                // endpoint (this endpoint) anyway, and we would essentially be
                // performing the same check twice.
                true
            }
            else -> {
                logger.warn("Ignoring an unknown packet type:" + packet.javaClass.simpleName)
                false
            }
        }
    }

    /**
     * Determine whether to forward endpoint stats from another endpoint to this one.
     */
    fun wantsStatsFrom(ep: AbstractEndpoint): Boolean {
        return conference.speechActivity.isRecentSpeaker(ep) ||
            conference.isRankedSpeaker(ep) ||
            bitrateController.isOnStageOrSelected(ep) ||
            bitrateController.hasNonZeroEffectiveConstraints(ep)
    }

    /**
     * Updates the conference statistics with value from this endpoint. Since
     * the values are cumulative this should execute only once when the endpoint
     * expires.
     */
    private fun updateStatsOnExpire() {
        val conferenceStats = conference.statistics
        val transceiverStats = transceiver.getTransceiverStats()

        conferenceStats.apply {
            val incomingStats = transceiverStats.rtpReceiverStats.packetStreamStats
            val outgoingStats = transceiverStats.outgoingPacketStreamStats
            totalBytesReceived.addAndGet(incomingStats.bytes)
            totalPacketsReceived.addAndGet(incomingStats.packets)
            totalBytesSent.addAndGet(outgoingStats.bytes)
            totalPacketsSent.addAndGet(outgoingStats.packets)
        }

        conference.videobridge.statistics.apply {
            val bweStats = transceiverStats.bandwidthEstimatorStats
            bweStats.getNumber("incomingEstimateExpirations")?.toInt()?.let {
                incomingBitrateExpirations.addAndGet(it)
            }
            totalKeyframesReceived.addAndGet(transceiverStats.rtpReceiverStats.videoParserStats.numKeyframes)
            totalLayeringChangesReceived.addAndGet(
                transceiverStats.rtpReceiverStats.videoParserStats.numLayeringChanges
            )

            val durationActiveVideoMs = transceiverStats.rtpReceiverStats.incomingStats.ssrcStats.values.filter {
                it.mediaType == MediaType.VIDEO
            }.sumOf { it.durationActiveMs }
            totalVideoStreamMillisecondsReceived.addAndGet(durationActiveVideoMs)
        }

        run {
            val bweStats = transceiverStats.bandwidthEstimatorStats
            val lossLimitedMs = bweStats.getNumber("lossLimitedMs")?.toLong() ?: return@run
            val lossDegradedMs = bweStats.getNumber("lossDegradedMs")?.toLong() ?: return@run
            val lossFreeMs = bweStats.getNumber("lossFreeMs")?.toLong() ?: return@run

            val participantMs = lossFreeMs + lossDegradedMs + lossLimitedMs
            conference.videobridge.statistics.apply {
                totalLossControlledParticipantMs.addAndGet(participantMs)
                totalLossLimitedParticipantMs.addAndGet(lossLimitedMs)
                totalLossDegradedParticipantMs.addAndGet(lossDegradedMs)
            }
        }

        if (iceTransport.isConnected() && !dtlsTransport.isConnected) {
            logger.info("Expiring an endpoint with ICE connected, but not DTLS.")
            conferenceStats.dtlsFailedEndpoints.incrementAndGet()
        }
    }

    override fun getDebugState(): JSONObject {
        return super.getDebugState().apply {
            put("bitrateController", bitrateController.debugState)
            put("bandwidthProbing", bandwidthProbing.getDebugState())
            put("iceTransport", iceTransport.getDebugState())
            put("dtlsTransport", dtlsTransport.getDebugState())
            put("transceiver", transceiver.getNodeStats().toJson())
            put("acceptAudio", acceptAudio)
            put("acceptVideo", acceptVideo)
            put("messageTransport", messageTransport.debugState)
        }
    }

    override fun expire() {
        if (super.isExpired()) {
            return
        }
        super.expire()

        try {
            val channelShimsCopy = channelShims.toSet()
            channelShims.clear()
            channelShimsCopy.forEach { channelShim ->
                if (!channelShim.isExpired) {
                    channelShim.expire = 0
                }
            }
            updateStatsOnExpire()
            transceiver.stop()
            logger.cdebug { transceiver.getNodeStats().prettyPrint(0) }
            logger.cdebug { bitrateController.debugState.toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            transceiver.teardown()
            _messageTransport.close()
            sctpHandler.stop()
            sctpManager?.closeConnection()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        bandwidthProbing.enabled = false
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing)
        conference.encodingsManager.unsubscribe(this)

        dtlsTransport.stop()
        iceTransport.stop()
        outgoingSrtpPacketQueue.close()

        logger.info("Expired.")
    }

    companion object {
        /**
         * Whether or not the bridge should be the peer which opens the data channel
         * (as opposed to letting the far peer/client open it).
         */
        private const val openDataChannelLocally = false
        /**
         * Track how long it takes for all RTP and RTCP packets to make their way through the bridge.
         * Since [Endpoint] is the 'last place' that is aware of [PacketInfo] in the outgoing
         * chain, we track this stats here.  Since they're static, these members will track the delay
         * for packets going out to all endpoints.
         */
        private val rtpPacketDelayStats = PacketDelayStats()
        private val rtcpPacketDelayStats = PacketDelayStats()

        /**
         * Count the number of dropped packets and exceptions.
         */
        @JvmField
        val queueErrorCounter = CountingErrorHandler()

        /**
         * An average of all of the individual bridge jitter values calculated by the
         * [bridgeJitterStats] instance variables
         */
        @JvmField
        val overallAverageBridgeJitter = DoubleAverage("overall_bridge_jitter")

        /**
         * The executor which runs bandwidth probing.
         *
         * TODO (brian): align the recurringRunnable stuff with whatever we end up
         * doing with all the other executors.
         */
        private val recurringRunnableExecutor = RecurringRunnableExecutor(Endpoint::class.java.simpleName)

        /**
         * How long we'll give an endpoint to either successfully establish
         * an ICE connection or fail before we expire it.
         */
        // TODO: make this configurable
        private val epTimeout = 2.mins

        @JvmStatic
        fun getPacketDelayStats() = OrderedJsonObject().apply {
            put("rtp", rtpPacketDelayStats.toJson())
            put("rtcp", rtcpPacketDelayStats.toJson())
        }

        private val timelineCounter = AtomicLong()
        private val TIMELINE_FRACTION = 10000L

        fun logTimeline() = timelineCounter.getAndIncrement() % TIMELINE_FRACTION == 0L
    }

    private inner class TransceiverEventHandlerImpl : TransceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long) =
            conference.speechActivity.levelChanged(this@Endpoint, level)

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            bitrateController.bandwidthChanged(newValue.bps.toLong())
            bandwidthProbing.bandwidthEstimationChanged(newValue)
        }
    }

    /**
     * A node which can be placed in the pipeline to cache Data channel packets
     * until the DataChannelStack is ready to handle them.
     */
    private class DataChannelHandler : ConsumerNode("Data channel handler") {
        private val dataChannelStackLock = Any()
        private var dataChannelStack: DataChannelStack? = null
        private val cachedDataChannelPackets = LinkedBlockingQueue<PacketInfo>()

        public override fun consume(packetInfo: PacketInfo) {
            synchronized(dataChannelStackLock) {
                when (val packet = packetInfo.packet) {
                    is DataChannelPacket -> {
                        dataChannelStack?.onIncomingDataChannelPacket(
                            ByteBuffer.wrap(packet.buffer), packet.sid, packet.ppid
                        ) ?: run {
                            cachedDataChannelPackets.add(packetInfo)
                        }
                    }
                    else -> Unit
                }
            }
        }

        fun setDataChannelStack(dataChannelStack: DataChannelStack) {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well

            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.execute {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via
                // #doProcessPackets and processing packets at the same time in
                // another thread, which would be a problem.
                synchronized(dataChannelStackLock) {
                    this.dataChannelStack = dataChannelStack
                    cachedDataChannelPackets.forEach {
                        val dcp = it.packet as DataChannelPacket
                        dataChannelStack.onIncomingDataChannelPacket(
                            ByteBuffer.wrap(dcp.buffer), dcp.sid, dcp.ppid
                        )
                    }
                }
            }
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    /**
     * A node which can be placed in the pipeline to cache SCTP packets until
     * the SCTPManager is ready to handle them.
     */
    private class SctpHandler : ConsumerNode("SCTP handler") {
        private val sctpManagerLock = Any()
        private var sctpManager: SctpManager? = null
        private val numCachedSctpPackets = AtomicLong(0)
        private val cachedSctpPackets = LinkedBlockingQueue<PacketInfo>(100)

        override fun consume(packetInfo: PacketInfo) {
            synchronized(sctpManagerLock) {
                if (SctpConfig.config.enabled) {
                    sctpManager?.handleIncomingSctp(packetInfo) ?: run {
                        numCachedSctpPackets.incrementAndGet()
                        cachedSctpPackets.add(packetInfo)
                    }
                }
            }
        }

        override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
            addNumber("num_cached_packets", numCachedSctpPackets.get())
        }

        fun setSctpManager(sctpManager: SctpManager) {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.execute {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via
                // #doProcessPackets and processing packets at the same time in
                // another thread, which would be a problem.
                synchronized(sctpManagerLock) {
                    this.sctpManager = sctpManager
                    cachedSctpPackets.forEach { sctpManager.handleIncomingSctp(it) }
                    cachedSctpPackets.clear()
                }
            }
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }
}
