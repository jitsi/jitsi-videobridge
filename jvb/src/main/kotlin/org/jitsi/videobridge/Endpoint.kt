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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Transceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.nlj.util.sumOf
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.concurrent.RecurringRunnableExecutor
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.mins
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.cc.BandwidthProbing
import org.jitsi.videobridge.cc.allocation.BandwidthAllocation
import org.jitsi.videobridge.cc.allocation.BitrateController
import org.jitsi.videobridge.cc.allocation.EffectiveConstraintsMap
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.datachannel.DataChannelStack
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket
import org.jitsi.videobridge.datachannel.protocol.DataChannelProtocolConstants
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ForwardedSourcesMessage
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.message.SenderSourceConstraintsMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.RelayedEndpoint
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.videobridge.sctp.DataChannelHandler
import org.jitsi.videobridge.sctp.SctpHandler
import org.jitsi.videobridge.sctp.SctpManager
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
import org.jitsi.xmpp.util.XmlStringBuilderUtil.Companion.toStringOpt
import org.jitsi_modified.sctp4j.SctpDataCallback
import org.jitsi_modified.sctp4j.SctpServerSocket
import org.jitsi_modified.sctp4j.SctpSocket
import org.json.simple.JSONObject
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private val doSsrcRewriting: Boolean,
    /**
     * Whether this endpoint is in "visitor" mode, i.e. should be invisible to other endpoints.
     */
    override val visitor: Boolean,
    supportsPrivateAddresses: Boolean,
    private val clock: Clock = Clock.systemUTC()
) : AbstractEndpoint(conference, id, parentLogger),
    PotentialPacketHandler,
    EncodingsManager.EncodingsUpdateListener,
    SsrcRewriter {
    /**
     * The time at which this endpoint was created
     */
    val creationTime = clock.instant()

    private val sctpHandler = SctpHandler()
    private val dataChannelHandler = DataChannelHandler()

    /* TODO: do we ever want to support useUniquePort for an Endpoint? */
    private val iceTransport = IceTransport(id, iceControlling, false, supportsPrivateAddresses, logger)
    private val dtlsTransport = DtlsTransport(logger).also { it.cryptex = CryptexConfig.endpoint }

    private var cryptex: Boolean = CryptexConfig.endpoint

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("endpoint_id", id)
    }

    private val timelineLogger = logger.createChildLogger("timeline.${this.javaClass.name}")

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
     * Whether this endpoint should accept audio packets. We set this according
     * to whether the endpoint has an audio Colibri channel whose direction
     * allows sending.
     */
    var acceptAudio = false

    /**
     * Whether this endpoint should accept video packets. We set this according
     * to whether the endpoint has a video Colibri channel whose direction
     * allows sending.
     */
    var acceptVideo = false

    /**
     * Whether a [ReceiverVideoConstraintsMessage] has been received from the endpoint. Setting initial-last-n only
     * has an effect prior to constraints being received on the message transport.
     */
    var initialReceiverConstraintsReceived = false

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

            override fun forwardedSourcesChanged(forwardedSources: Set<String>) {
                sendForwardedSourcesMessage(forwardedSources)
            }

            override fun effectiveVideoConstraintsChanged(
                oldEffectiveConstraints: EffectiveConstraintsMap,
                newEffectiveConstraints: EffectiveConstraintsMap,
            ) = this@Endpoint.effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints)

            override fun keyframeNeeded(endpointId: String?, ssrc: Long) = conference.requestKeyframe(endpointId, ssrc)
        },
        { getOrderedEndpoints() },
        diagnosticContext,
        logger
    )

    /** Whether any sources are suspended from being sent to this endpoint because of BWE. */
    fun hasSuspendedSources() = bitrateController.hasSuspendedSources()

    /**
     * The instance which manages the Colibri messaging (over a data channel
     * or web sockets).
     */
    override val messageTransport = EndpointMessageTransport(
        this,
        { conference.videobridge.statistics },
        conference,
        logger
    )

    /**
     * Gets the endpoints in the conference in LastN order, with this {@link Endpoint} removed.
     */
    fun getOrderedEndpoints(): List<AbstractEndpoint> = conference.orderedEndpoints.filterNot { it == this }

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
        setLocalSsrc(MediaType.AUDIO, conference.localAudioSsrc)
        setLocalSsrc(MediaType.VIDEO, conference.localVideoSsrc)
    }

    private val bandwidthProbing = BandwidthProbing(
        object : BandwidthProbing.ProbingDataSender {
            override fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int {
                return transceiver.sendProbing(mediaSsrcs, numBytes)
            }
        },
        { bitrateController.getStatusSnapshot() }
    ).apply {
        diagnosticsContext = this@Endpoint.diagnosticContext
        enabled = true
    }.also {
        recurringRunnableExecutor.registerRecurringRunnable(it)
    }

    /**
     * Manages remapping of video SSRCs when enabled.
     */
    private val videoSsrcs = VideoSsrcCache(SsrcLimitConfig.config.maxVideoSsrcs, this, logger)

    /**
     * Manages remapping of audio SSRCs when enabled.
     */
    private val audioSsrcs = AudioSsrcCache(SsrcLimitConfig.config.maxAudioSsrcs, this, logger)

    /**
     * Last advertised forwarded-sources in remapping mode.
     */
    private var activeSources: Set<String> = emptySet()

    /**
     * Track allocated send SSRCs to avoid collisions.
     */
    private val sendSsrcs = mutableSetOf<Long>()

    /**
     * Next allocated send SSRC, when allocating serially (off by default).
     */
    private var nextSendSsrc = 777000001L

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        conference.videobridge.statistics.totalEndpoints.inc()
        if (visitor) {
            conference.videobridge.statistics.totalVisitors.inc()
        }

        logger.info("Created new endpoint, iceControlling=$iceControlling")
    }

    override var mediaSources: Array<MediaSourceDesc>
        get() = transceiver.getMediaSources()
        set(value) {
            applyVideoTypeCache(value)
            val wasEmpty = transceiver.getMediaSources().isEmpty()
            if (transceiver.setMediaSources(value)) {
                eventEmitter.fireEvent { sourcesChanged() }
            }
            if (wasEmpty) {
                sendAllVideoConstraints()
            }
        }

    /**
     *  Keep track of this endpoint's audio sources.
     */
    var audioSources: ArrayList<AudioSourceDesc> = ArrayList()

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
                            this.receivedTime = receivedTime
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
                        packetInfo.addEvent(SRTP_QUEUE_ENTRY_EVENT)
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
     * Return the list of sources the endpoint has selected as "on stage". We just concatenate with the old
     * "onStageEndpoints" since even with the old API we have matching source names.
     */
    fun getOnStageSources() =
        bitrateController.allocationSettings.onStageEndpoints + bitrateController.allocationSettings.onStageSources

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
                transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial, cryptex)
                // TODO(brian): the old code would work even if the sctp connection was created after
                //  the handshake had completed, but this won't (since this is a one-time event).  do
                //  we need to worry about that case?
                sctpSocket.ifPresent(::acceptSctpConnection)
                scheduleEndpointMessageTransportTimeout()
            }
        }
    }

    fun updateForceMute(audioForceMuted: Boolean, videoForceMuted: Boolean) {
        transceiver.forceMuteAudio(audioForceMuted)
        transceiver.forceMuteVideo(videoForceMuted)
    }

    override fun addPayloadType(payloadType: PayloadType) {
        transceiver.addPayloadType(payloadType)
        bitrateController.addPayloadType(payloadType)
    }

    override fun addRtpExtension(rtpExtension: RtpExtension) = transceiver.addRtpExtension(rtpExtension)

    override fun setExtmapAllowMixed(allow: Boolean) = transceiver.setExtmapAllowMixed(allow)

    fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        logger.cdebug { "Adding receive ssrc $ssrc of type $mediaType" }
        transceiver.addReceiveSsrc(ssrc, mediaType)
        conference.addEndpointSsrc(this, ssrc)
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

    override val isSendingAudio: Boolean
        get() =
            // The endpoint is sending audio if we (the transceiver) are receiving audio.
            transceiver.isReceivingAudio()

    override val isSendingVideo: Boolean
        get() =
            // The endpoint is sending video if we (the transceiver) are receiving video.
            transceiver.isReceivingVideo()

    private fun doSendSrtp(packetInfo: PacketInfo): Boolean {
        packetInfo.addEvent(SRTP_QUEUE_EXIT_EVENT)
        PacketTransitStats.packetSent(packetInfo)

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
     * {@inheritDoc}
     */
    override fun sendMessage(msg: BridgeChannelMessage) = messageTransport.sendMessage(msg)

    // TODO: this should be part of an EndpointMessageTransport.EventHandler interface
    fun endpointMessageTransportConnected() {
        sendAllVideoConstraints()
        sendForwardedSourcesMessage(bitrateController.forwardedSources)
        videoSsrcs.sendAllMappings()
        audioSsrcs.sendAllMappings()
    }

    private fun sendAllVideoConstraints() {
        maxReceiverVideoConstraints.forEach { (sourceName, constraints) ->
            sendVideoConstraints(sourceName, constraints)
        }
    }

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     */
    // TODO(brian): change sctp handler to take buf, off, len
    fun dtlsAppPacketReceived(data: ByteArray, off: Int, len: Int) =
        sctpHandler.processPacket(PacketInfo(UnparsedPacket(data, off, len)))

    private fun effectiveVideoConstraintsChanged(
        oldEffectiveConstraints: EffectiveConstraintsMap,
        newEffectiveConstraints: EffectiveConstraintsMap
    ) {
        val removedSources = oldEffectiveConstraints.keys.filterNot { it in newEffectiveConstraints.keys }

        // Sources that "this" endpoint no longer receives.
        removedSources.map { it.sourceName }.forEach { removedSourceName ->
            // Remove ourself as a receiver from that endpoint
            conference.findSourceOwner(removedSourceName)?.removeSourceReceiver(removedSourceName, id)
        }

        // Added or updated
        newEffectiveConstraints.forEach { (source, effectiveConstraints) ->
            conference.findSourceOwner(source.sourceName)?.addReceiver(id, source.sourceName, effectiveConstraints)
        }

        if (doSsrcRewriting) {
            val newActiveSources =
                newEffectiveConstraints.entries.filter { !it.value.isDisabled() }.map { it.key }.toList()
            val newActiveSourceNames = newActiveSources.map { it.sourceName }.toSet()
            /* safe unlocked access of activeSources. BitrateController will not overlap calls to this method. */
            if (activeSources != newActiveSourceNames) {
                activeSources = newActiveSourceNames
                videoSsrcs.activate(newActiveSources)
            }
        }
    }

    override fun sendVideoConstraints(sourceName: String, maxVideoConstraints: VideoConstraints) {
        // Note that it's up to the client to respect these constraints.
        if (findMediaSourceDesc(sourceName) == null) {
            logger.warn {
                "Suppressing sending a SenderVideoConstraints message, endpoint has no such source: $sourceName"
            }
        } else {
            val senderSourceConstraintsMessage =
                SenderSourceConstraintsMessage(sourceName, maxVideoConstraints.maxHeight)
            logger.cdebug { "Sender constraints changed: ${senderSourceConstraintsMessage.toJson()}" }
            sendMessage(senderSourceConstraintsMessage)
        }
    }

    /**
     * Create an SCTP connection for this Endpoint.  If [OPEN_DATA_CHANNEL_LOCALLY] is true,
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
        val socket = sctpManager!!.createServerSocket(logger)
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
                    messageTransport.setDataChannel(dataChannel)
                }
                dataChannelHandler.setDataChannelStack(dataChannelStack!!)
                if (OPEN_DATA_CHANNEL_LOCALLY) {
                    // This logic is for opening the data channel locally
                    logger.info("Will open the data channel.")
                    val dataChannel = dataChannelStack!!.createDataChannel(
                        DataChannelProtocolConstants.RELIABLE,
                        0,
                        0,
                        0,
                        "default"
                    )
                    messageTransport.setDataChannel(dataChannel)
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
                        conference.videobridge.statistics.numEndpointsNoMessageTransportAfterDelay.inc()
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
     * Sends a message to this endpoint in order to notify it that the set of media sources for which the bridge
     * is sending video has changed.
     *
     * @param forwardedSources the collection of forwarded media sources (by name).
     */
    fun sendForwardedSourcesMessage(forwardedSources: Collection<String>) {
        val msg = ForwardedSourcesMessage(forwardedSources)
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
                logger.info("Ignoring empty DtlsFingerprint extension: ${transportInfo.toStringOpt()}")
            }
            if (CryptexConfig.endpoint) {
                cryptex = cryptex && fingerprintExtension.cryptex
            }
        }
        dtlsTransport.setRemoteFingerprints(remoteFingerprints)
        if (fingerprintExtensions.isNotEmpty()) {
            val setup = fingerprintExtensions.first().setup
            dtlsTransport.setSetupAttribute(setup)
        }
        iceTransport.startConnectivityEstablishment(transportInfo)
    }

    fun describeTransport(): IceUdpTransportPacketExtension {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)
        colibriWebSocketServiceSupplier.get()?.let { colibriWebsocketService ->
            colibriWebsocketService.getColibriWebSocketUrls(
                conference.id,
                id,
                iceTransport.icePassword
            ).forEach { wsUrl ->
                val wsPacketExtension = WebSocketPacketExtension(wsUrl)
                iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension)
            }
        }

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toStringOpt()}" }

        return iceUdpTransportPacketExtension
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     */
    fun handleIncomingPacket(packetInfo: PacketInfo) {
        if (visitor) {
            /* Never forward RTP/RTCP from a visitor. */
            ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
            return
        }

        packetInfo.endpointId = id
        conference.handleIncomingPacket(packetInfo)
    }

    override fun receivesSsrc(ssrc: Long): Boolean = transceiver.receivesSsrc(ssrc)

    fun doesSsrcRewriting(): Boolean = doSsrcRewriting

    fun unmapRtcpFbSsrc(packet: RtcpFbPacket) = videoSsrcs.unmapRtcpFbSsrc(packet)

    override val ssrcs
        get() = HashSet(transceiver.receiveSsrcs)

    override val lastIncomingActivity
        get() = transceiver.packetIOActivity.lastIncomingActivityInstant

    override fun requestKeyframe() = transceiver.requestKeyFrame()

    override fun requestKeyframe(mediaSsrc: Long) = transceiver.requestKeyFrame(mediaSsrc)

    /** Whether we are currently oversending to this endpoint. */
    fun isOversending(): Boolean = bitrateController.isOversending()

    /**
     * Returns how many video sources are currently forwarding to this endpoint.
     */
    fun numForwardedSources(): Int = bitrateController.numForwardedSources()

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage) {
        initialReceiverConstraintsReceived = true
        bitrateController.setBandwidthAllocationSettings(message)
    }

    /**
     * {@inheritDoc}
     */
    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? {
        conference.getEndpointBySsrc(ssrc)?.let { ep ->
            ep.mediaSources.forEach { s ->
                if (s.findRtpEncodingDesc(ssrc) != null) {
                    return s
                }
            }
        }
        logger.error { "No properties found for SSRC $ssrc." }
        return null
    }

    /**
     * {@inheritDoc}
     */
    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? {
        conference.getEndpointBySsrc(ssrc)?.let { ep ->
            return when (ep) {
                is Endpoint -> ep.audioSources
                is RelayedEndpoint -> ep.audioSources
                else -> emptyList()
            }.find { it.ssrc == ssrc }
        }
        logger.error { "No properties found for SSRC $ssrc." }
        return null
    }

    /**
     * {@inheritDoc}
     */
    override fun getNextSendSsrc(): Long {
        synchronized(sendSsrcs) {
            while (true) {
                val ssrc = if (useRandomSendSsrcs) {
                    random.nextLong().and(0xFFFF_FFFFL)
                } else {
                    nextSendSsrc++
                }
                if (sendSsrcs.add(ssrc)) {
                    return ssrc
                }
            }
        }
    }

    override fun send(packetInfo: PacketInfo) {
        when (val packet = packetInfo.packet) {
            is VideoRtpPacket -> {
                if (bitrateController.transformRtp(packetInfo)) {
                    // The original packet was transformed in place.
                    if (doSsrcRewriting) {
                        val start = packet !is ParsedVideoPacket || (packet.isKeyframe && packet.isStartOfFrame)
                        if (!videoSsrcs.rewriteRtp(packet, start)) {
                            return
                        }
                    }
                    transceiver.sendPacket(packetInfo)
                } else {
                    logger.warn("Dropping a packet which was supposed to be accepted:$packet")
                }
                return
            }
            is AudioRtpPacket -> if (doSsrcRewriting) audioSsrcs.rewriteRtp(packet)
            is RtcpSrPacket -> {
                // Allow the BC to update the timestamp (in place).
                bitrateController.transformRtcp(packet)
                if (doSsrcRewriting) {
                    // Just check both tables instead of looking up the type first.
                    if (!videoSsrcs.rewriteRtcp(packet) && !audioSsrcs.rewriteRtcp(packet)) {
                        return
                    }
                }
                logger.trace {
                    "relaying an sr from ssrc=${packet.senderSsrc}, timestamp=${packet.senderInfo.rtpTimestamp}"
                }
            }
        }
        transceiver.sendPacket(packetInfo)
    }

    /**
     * To find out whether the endpoint should be expired, we check the activity timestamps from the transceiver.
     */
    override fun shouldExpire(): Boolean {
        if (iceTransport.hasFailed()) {
            logger.warn("Allowing to expire because ICE failed.")
            return true
        }

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

        val expireTimeout = VideobridgeExpireThreadConfig.config.inactivityTimeout
        if (Duration.between(lastActivity, now) > expireTimeout) {
            logger.info("Allowing to expire because of no activity in over $expireTimeout")
            return true
        }
        return false
    }

    fun setLastN(lastN: Int) {
        bitrateController.lastN = lastN
    }

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
        // Always forward stats in small conferences
        if (conference.endpointCount <= statsFilterThreshold) {
            return true
        }
        logger.debug {
            buildString {
                append("wantsStatsFrom(${ep.id}): isRecentSpeaker=${conference.speechActivity.isRecentSpeaker(ep)} ")
                append("isRankedSpeaker=${conference.isRankedSpeaker(ep)} ")
                if (ep.mediaSources.isEmpty()) {
                    append("(no media sources)")
                }
                ep.mediaSources.forEach { source ->
                    val name = source.sourceName
                    append("isOnStageOrSelected($name)=${bitrateController.isOnStageOrSelected(source)} ")
                    append("hasNonZeroEffectiveConstraints($name)=")
                    append("${bitrateController.hasNonZeroEffectiveConstraints(source)} ")
                }
            }
        }

        if (conference.speechActivity.isRecentSpeaker(ep) || conference.isRankedSpeaker(ep)) {
            return true
        }
        return ep.mediaSources.any { source ->
            bitrateController.isOnStageOrSelected(source) ||
                bitrateController.hasNonZeroEffectiveConstraints(source)
        }
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
            bweStats.getNumber("incomingEstimateExpirations")?.toLong()?.let {
                incomingBitrateExpirations.addAndGet(it)
            }
            keyframesReceived.addAndGet(transceiverStats.rtpReceiverStats.videoParserStats.numKeyframes.toLong())
            layeringChangesReceived.addAndGet(
                transceiverStats.rtpReceiverStats.videoParserStats.numLayeringChanges.toLong()
            )

            val durationActiveVideo = transceiverStats.rtpReceiverStats.incomingStats.ssrcStats.values.filter {
                it.mediaType == MediaType.VIDEO
            }.sumOf { it.durationActive }
            totalVideoStreamMillisecondsReceived.addAndGet(durationActiveVideo.toMillis())
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

    override val debugState: JSONObject
        get() = super.debugState.apply {
            put("bitrateController", bitrateController.debugState)
            put("bandwidthProbing", bandwidthProbing.getDebugState())
            put("iceTransport", iceTransport.getDebugState())
            put("dtlsTransport", dtlsTransport.getDebugState())
            put("transceiver", transceiver.getNodeStats().toJson())
            put("acceptAudio", acceptAudio)
            put("acceptVideo", acceptVideo)
            put("visitor", visitor)
            put("messageTransport", messageTransport.debugState)
            if (doSsrcRewriting) {
                put("audioSsrcs", audioSsrcs.getDebugState())
                put("videoSsrcs", videoSsrcs.getDebugState())
            }
        }

    override fun expire() {
        if (super.isExpired) {
            return
        }
        super.expire()

        try {
            bitrateController.expire()
            updateStatsOnExpire()
            transceiver.stop()
            logger.cdebug { transceiver.getNodeStats().prettyPrint(0) }
            logger.cdebug { bitrateController.debugState.toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            transceiver.teardown()
            messageTransport.close()
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

    fun setInitialLastN(initialLastN: Int) {
        if (initialReceiverConstraintsReceived) {
            logger.info("Ignoring initialLastN, message transport already connected.")
        } else {
            logger.info("Setting initialLastN = $initialLastN")
            bitrateController.lastN = initialLastN
        }
    }

    companion object {
        /**
         * Whether or not the bridge should be the peer which opens the data channel
         * (as opposed to letting the far peer/client open it).
         */
        private const val OPEN_DATA_CHANNEL_LOCALLY = false

        /**
         * Count the number of dropped packets and exceptions.
         */
        @JvmField
        val queueErrorCounter = CountingErrorHandler()

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

        private val timelineCounter = AtomicLong()
        private val TIMELINE_FRACTION = 10000L

        fun logTimeline() = timelineCounter.getAndIncrement() % TIMELINE_FRACTION == 0L

        private const val SRTP_QUEUE_ENTRY_EVENT = "Entered Endpoint SRTP sender outgoing queue"
        private const val SRTP_QUEUE_EXIT_EVENT = "Exited Endpoint SRTP sender outgoing queue"

        private val statsFilterThreshold: Int by config {
            "videobridge.stats-filter-threshold".from(JitsiConfig.newConfig)
        }

        /**
         * This can be switched off to ease debugging.
         */
        private val useRandomSendSsrcs = true

        /**
         * Used for generating send SSRCs.
         */
        private val random = SecureRandom()
    }

    private inner class TransceiverEventHandlerImpl : TransceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long): Boolean =
            conference.levelChanged(this@Endpoint, level)

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            bitrateController.bandwidthChanged(newValue.bps.toLong())
            bandwidthProbing.bandwidthEstimationChanged(newValue)
        }
    }
}
