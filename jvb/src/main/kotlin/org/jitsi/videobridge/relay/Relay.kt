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

import org.ice4j.util.Buffer
import org.jitsi.dcsctp4j.DcSctpMessage
import org.jitsi.dcsctp4j.ErrorKind
import org.jitsi.dcsctp4j.SendPacketStatus
import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.Transceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.VideoType
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.ToggleablePcapWriter
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSdesPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.sumOf
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.CryptexConfig
import org.jitsi.videobridge.EncodingsManager
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.TransportConfig
import org.jitsi.videobridge.datachannel.DataChannelStack
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket
import org.jitsi.videobridge.datachannel.protocol.DataChannelProtocolConstants
import org.jitsi.videobridge.dcsctp.DcSctpBaseCallbacks
import org.jitsi.videobridge.dcsctp.DcSctpHandler
import org.jitsi.videobridge.dcsctp.DcSctpTransport
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.SourceVideoTypeMessage
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.videobridge.sctp.DataChannelHandler
import org.jitsi.videobridge.stats.PacketTransitStats
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.jitsi.videobridge.websocket.colibriWebSocketServiceSupplier
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.sumOf
import org.jitsi.videobridge.sctp.SctpConfig.Companion.config as sctpConfig

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
     * The ID of the mesh to which this [Relay] connection belongs.
     * (Note that a bridge can be a member of more than one mesh, but each relay link will belong to only one.)
     */
    val meshId: String?,
    /**
     * True if the ICE agent for this [Relay] will be initialized to serve as a controlling ICE agent, false otherwise.
     */
    iceControlling: Boolean,
    useUniquePort: Boolean,
    clock: Clock = Clock.systemUTC()
) : EncodingsManager.EncodingsUpdateListener, PotentialPacketHandler {

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
     * A cache of extmap-allow-mixed
     */
    private var extmapAllowMixed = false

    /**
     * The indicator which determines whether [expire] has been called on this [Relay].
     */
    private var expired = false

    private val sctpHandler = if (sctpConfig.enabled) DcSctpHandler() else null

    /** The [DcSctpTransport] instance we'll use to manage the SCTP connection */
    private var sctpTransport: DcSctpTransport? = null

    /** The role we'll play in the SCTP handshake, if negotiated */
    private var sctpRole: Sctp.Role? = null

    private val dataChannelHandler = DataChannelHandler()
    private var dataChannelStack: DataChannelStack? = null

    private val toggleablePcapWriter = ToggleablePcapWriter(logger, "$id-sctp")
    private val sctpRecvPcap = toggleablePcapWriter.newObserverNode(outbound = false, suffix = "rx_sctp")
    private val sctpSendPcap = toggleablePcapWriter.newObserverNode(outbound = true, suffix = "tx_sctp")

    private val sctpPipeline = pipeline {
        node(sctpRecvPcap)
        sctpHandler?.let {
            node(it)
        }
    }

    private val iceTransport = IceTransport(
        id = id,
        controlling = iceControlling,
        useUniquePort = useUniquePort,
        // There's no good reason to disable private addresses.
        advertisePrivateAddresses = true,
        parentLogger = logger,
        clock = clock
    )

    private val dtlsTransport = DtlsTransport(logger, id).also { it.cryptex = CryptexConfig.relay }

    private var cryptex = CryptexConfig.relay

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("relay_id", id)
    }

    private val timelineLogger = logger.createChildLogger("timeline.${this.javaClass.name}")

    private val relayedEndpoints = HashMap<String, RelayedEndpoint>()
    private val endpointsBySsrc = HashMap<Long, RelayedEndpoint>()
    private val endpointsLock = Any()

    private val senders = ConcurrentHashMap<String, RelayEndpointSender>()

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

    /* This transceiver is only for packets that are not handled by [RelayedEndpoint]s
     * or [RelayEndpointSender]s */
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
        rtcpEventNotifier.addRtcpEventListener(
            object : RtcpListener {
                override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?) {
                    this@Relay.rtcpPacketReceived(packet, receivedTime, null)
                }
                override fun rtcpPacketSent(packet: RtcpPacket) {
                    this@Relay.rtcpPacketSent(packet, null)
                }
            },
            external = true
        )
        addRtpExtensionToRetain(RtpExtensionType.VLA)
    }

    /**
     * The instance which manages the Colibri messaging (over web sockets).
     */
    private val messageTransport = RelayMessageTransport(
        this,
        conference,
        logger
    )

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        VideobridgeMetrics.totalRelays.inc()
    }

    fun getMessageTransport(): RelayMessageTransport = messageTransport

    /**
     * The queue we put outgoing SRTP packets onto so they can be sent
     * out via the [IceTransport] on an IO thread.  This queue is only
     * for packets that are not handled by [RelayEndpointSender]s.
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

    fun debugState(mode: DebugStateMode): JSONObject = JSONObject().apply {
        put("ice_transport", iceTransport.getDebugState())
        put("dtls_transport", dtlsTransport.getDebugState())
        put("transceiver", transceiver.debugState(mode))
        put("mesh_id", meshId)
        put("message_transport", messageTransport.debugState)
        sctpTransport?.let {
            put("sctp", it.getDebugState())
        }

        if (mode == DebugStateMode.FULL) {
            val remoteEndpoints = JSONObject()
            val endpointsBySsrcMap = JSONObject()
            synchronized(endpointsLock) {
                for (r in relayedEndpoints.values) {
                    remoteEndpoints[r.id] = r.debugState(mode)
                }
                for ((s, e) in endpointsBySsrc) {
                    endpointsBySsrcMap[s] = e.id
                }
            }
            put("remote_endpoints", remoteEndpoints)
            put("endpoints_by_ssrc", endpointsBySsrcMap)
            val endpointSenders = JSONObject()
            for (s in senders.values) {
                endpointSenders[s.id] = s.getDebugState(mode)
            }
            put("senders", endpointSenders)
        }
    }

    private fun setupIceTransport() {
        iceTransport.incomingDataHandler = object : IceTransport.IncomingDataHandler {
            override fun dataReceived(buffer: Buffer) {
                // DTLS data will be handled by the DtlsTransport, but SRTP data can go
                // straight to the transceiver
                if (looksLikeDtls(buffer.buffer, buffer.offset, buffer.length)) {
                    // DTLS transport is responsible for making its own copy, because it will manage its own buffers
                    dtlsTransport.enqueueBuffer(buffer)
                } else {
                    val pktInfo =
                        RelayedPacketInfo(
                            UnparsedPacket(buffer.buffer, buffer.offset, buffer.length),
                            meshId
                        ).apply {
                            this.receivedTime = buffer.receivedTime
                        }
                    pktInfo.packetOrigin = PacketOrigin.Routed
                    handleMediaPacket(pktInfo)
                }
            }
        }
        iceTransport.eventHandler = object : IceTransport.EventHandler {
            override fun writeable() {
                logger.info("ICE connected")
                transceiver.setOutgoingPacketHandler(object : PacketHandler {
                    override fun processPacket(packetInfo: PacketInfo) {
                        packetInfo.addEvent(SRTP_QUEUE_ENTRY_EVENT)
                        outgoingSrtpPacketQueue.add(packetInfo)
                    }
                })
                TaskPools.IO_POOL.execute(dtlsTransport::startDtlsHandshake)
            }

            override fun connected() {}

            override fun failed() {}

            override fun consentUpdated(time: Instant) {
                transceiver.packetIOActivity.lastIceActivityInstant = time
            }
        }
    }

    private fun setupDtlsTransport() {
        dtlsTransport.incomingDataHandler = object : DtlsTransport.IncomingDataHandler {
            override fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int) {
                dtlsAppPacketReceived(buf, off, len)
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
                if (sctpConfig.enabled) {
                    if (sctpRole == Sctp.Role.CLIENT) {
                        sctpTransport!!.connect()
                    }
                }
            }
        }
    }

    private var srtpTransformers: SrtpTransformers? = null

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
            cryptex,
            logger
        )
        this.srtpTransformers = srtpTransformers

        transceiver.setSrtpInformation(srtpTransformers)
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { it.setSrtpInformation(srtpTransformers) }
        }

        senders.values.forEach { it.setSrtpInformation(srtpTransformers) }
    }

    /**
     * Create an SCTP connection for this Relay.  If [sctpDesc.role] is [Sctp.Role.CLIENT],
     * we will create the data channel locally, otherwise we will wait for the remote side
     * to open it.
     */
    fun createSctpConnection(sctpDesc: Sctp) {
        if (sctpConfig.enabled) {
            createDcSctpConnection(sctpDesc)
        } else {
            logger.error("Not creating SCTP connection, SCTP is disabled in configuration.")
        }
    }

    private fun createDcSctpConnection(sctpDesc: Sctp) {
        sctpRole = sctpDesc.role

        logger.cdebug { "Creating SCTP transport" }
        sctpTransport = DcSctpTransport(id, logger).also {
            it.start(SctpCallbacks(it))
            sctpHandler!!.setSctpTransport(it)
            if (dtlsTransport.isConnected && sctpDesc.role == Sctp.Role.CLIENT) {
                it.connect()
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
        val remoteFingerprints = mutableMapOf<String, MutableList<String>>()
        val fingerprintExtensions = transportInfo.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java)
        fingerprintExtensions.forEach { fingerprintExtension ->
            if (fingerprintExtension.hash != null && fingerprintExtension.fingerprint != null) {
                remoteFingerprints.getOrPut(fingerprintExtension.hash.lowercase()) { mutableListOf() }
                    .add(fingerprintExtension.fingerprint)
            } else {
                logger.info("Ignoring empty DtlsFingerprint extension: ${transportInfo.toXML()}")
            }

            if (CryptexConfig.relay) {
                cryptex = cryptex && fingerprintExtension.cryptex
            }
        }
        dtlsTransport.setRemoteFingerprints(remoteFingerprints)
        if (fingerprintExtensions.isNotEmpty()) {
            val setup = fingerprintExtensions.first().setup
            dtlsTransport.setSetupAttribute(setup)
        }
        iceTransport.startConnectivityEstablishment(transportInfo)

        val websocketExtension = transportInfo.getFirstChildOfType(WebSocketPacketExtension::class.java)
        websocketExtension?.url?.let { messageTransport.connectToWebsocket(it) }
    }

    fun describeTransport(): IceUdpTransportPacketExtension {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)

        if (sctpTransport == null) {
            /* TODO: this should be dependent on videobridge.websockets.enabled, if we support that being
             *  disabled for relay.
             */
            if (messageTransport.isActive) {
                iceUdpTransportPacketExtension.addChildExtension(
                    WebSocketPacketExtension().apply { active = true }
                )
            } else {
                colibriWebSocketServiceSupplier.get()?.let { colibriWebsocketService ->
                    val urls = colibriWebsocketService.getColibriRelayWebSocketUrls(
                        conference.id,
                        id,
                        iceTransport.icePassword
                    )
                    if (urls.isEmpty()) {
                        logger.warn("No colibri relay URLs configured")
                    }
                    urls.forEach {
                        iceUdpTransportPacketExtension.addChildExtension(
                            WebSocketPacketExtension().apply {
                                url = it
                            }
                        )
                    }
                }
            }
        }

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }

        return iceUdpTransportPacketExtension
    }

    fun setFeature(feature: EndpointDebugFeatures, enabled: Boolean) {
        when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> {
                transceiver.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled)
                synchronized(endpointsLock) {
                    relayedEndpoints.values.forEach { e -> e.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled) }
                }
                senders.values.forEach { s -> s.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled) }
            }
            EndpointDebugFeatures.SCTP_PCAP_DUMP ->
                if (enabled) {
                    toggleablePcapWriter.enable()
                } else {
                    toggleablePcapWriter.disable()
                }
        }
    }

    fun isFeatureEnabled(feature: EndpointDebugFeatures): Boolean {
        return when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> transceiver.isFeatureEnabled(Features.TRANSCEIVER_PCAP_DUMP)
            EndpointDebugFeatures.SCTP_PCAP_DUMP -> toggleablePcapWriter.isEnabled()
        }
    }

    /**
     * Handle media packets that have arrived, using the appropriate endpoint's transceiver.
     */
    private fun handleMediaPacket(packetInfo: RelayedPacketInfo) {
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

    fun doSendSrtp(packetInfo: PacketInfo): Boolean {
        packetInfo.addEvent(SRTP_QUEUE_EXIT_EVENT)

        iceTransport.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
        PacketTransitStats.packetSent(packetInfo)
        ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        packetInfo.sent()
        if (timelineLogger.isTraceEnabled && Endpoint.logTimeline()) {
            timelineLogger.trace { packetInfo.timeline.toString() }
        }
        return true
    }

    /**
     * Sends a specific message to the remote side.
     */
    fun sendMessage(msg: BridgeChannelMessage) = messageTransport.sendMessage(msg)

    fun relayMessageTransportConnected() {
        relayedEndpoints.values.forEach { e -> e.relayMessageTransportConnected() }
        conference.endpoints.forEach { e ->
            if (e is Endpoint || (e is RelayedEndpoint && e.relay.meshId != meshId)) {
                e.mediaSources.forEach { msd: MediaSourceDesc ->
                    // Do not send the initial value for CAMERA, because it's the default
                    if (msd.videoType != VideoType.CAMERA) {
                        val videoTypeMsg = SourceVideoTypeMessage(
                            msd.videoType,
                            msd.sourceName,
                            e.id
                        )
                        sendMessage(videoTypeMsg)
                    }
                }
            }
        }
    }

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     */
    // TODO(brian): change sctp handler to take buf, off, len
    fun dtlsAppPacketReceived(data: ByteArray, off: Int, len: Int) =
        sctpPipeline.processPacket(PacketInfo(UnparsedPacket(data, off, len)))

    /**
     * Return the newly created endpoint, or null if an endpoint with that ID already existed. Note that the new
     * endpoint has to be added to the [Conference] separately.
     */
    fun addRemoteEndpoint(
        id: String,
        statsId: String?,
        audioSources: Collection<AudioSourceDesc>,
        videoSources: Collection<MediaSourceDesc>
    ): RelayedEndpoint? {
        val ep: RelayedEndpoint
        synchronized(endpointsLock) {
            if (relayedEndpoints.containsKey(id)) {
                logger.warn("Relay already contains remote endpoint with ID $id")
                updateRemoteEndpoint(id, audioSources, videoSources)
                return null
            }
            ep = RelayedEndpoint(
                conference,
                this,
                id,
                logger,
                conference.newDiagnosticContext().apply {
                    put("relay_id", this@Relay.id)
                    put("endpoint_id", id)
                }
            )
            ep.statsId = statsId
            ep.audioSources = audioSources.toList()
            ep.mediaSources = videoSources.toTypedArray()

            relayedEndpoints[id] = ep

            ep.ssrcs.forEach { ssrc -> endpointsBySsrc[ssrc] = ep }
        }

        srtpTransformers?.let { ep.setSrtpInformation(it) }
        payloadTypes.forEach { payloadType -> ep.addPayloadType(payloadType) }
        rtpExtensions.forEach { rtpExtension -> ep.addRtpExtension(rtpExtension) }
        ep.setExtmapAllowMixed(extmapAllowMixed)

        setEndpointMediaSources(ep, audioSources, videoSources)

        ep.setFeature(Features.TRANSCEIVER_PCAP_DUMP, transceiver.isFeatureEnabled(Features.TRANSCEIVER_PCAP_DUMP))
        return ep
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

            ep.audioSources = audioSources.toList()
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
        ep?.expire()
    }

    private fun getOrCreateRelaySender(endpointId: String): RelayEndpointSender {
        synchronized(senders) {
            senders[endpointId]?.let { return it }

            val s = RelayEndpointSender(
                this,
                endpointId,
                logger,
                conference.newDiagnosticContext().apply {
                    put("relay_id", id)
                    put("endpoint_id", endpointId)
                }
            )

            srtpTransformers?.let { s.setSrtpInformation(it) }
            payloadTypes.forEach { payloadType -> s.addPayloadType(payloadType) }
            rtpExtensions.forEach { rtpExtension -> s.addRtpExtension(rtpExtension) }
            s.setExtmapAllowMixed(extmapAllowMixed)
            s.setFeature(Features.TRANSCEIVER_PCAP_DUMP, transceiver.isFeatureEnabled(Features.TRANSCEIVER_PCAP_DUMP))

            senders[endpointId] = s

            return s
        }
    }

    fun addPayloadType(payloadType: PayloadType) {
        transceiver.addPayloadType(payloadType)
        payloadTypes.add(payloadType)
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { ep -> ep.addPayloadType(payloadType) }
        }
        senders.values.forEach { s -> s.addPayloadType(payloadType) }
    }

    fun addRtpExtension(rtpExtension: RtpExtension) {
        /* We don't want to do any BWE for relay-relay channels; also, the sender split confuses things. */
        if (rtpExtension.type == RtpExtensionType.TRANSPORT_CC ||
            rtpExtension.type == RtpExtensionType.ABS_SEND_TIME
        ) {
            return
        }
        transceiver.addRtpExtension(rtpExtension)
        rtpExtensions.add(rtpExtension)
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { ep -> ep.addRtpExtension(rtpExtension) }
        }
        senders.values.forEach { s -> s.addRtpExtension(rtpExtension) }
    }

    fun setExtmapAllowMixed(allow: Boolean) {
        transceiver.setExtmapAllowMixed(allow)
        extmapAllowMixed = allow

        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { ep -> ep.setExtmapAllowMixed(allow) }
        }
        senders.values.forEach { s -> s.setExtmapAllowMixed(allow) }
    }

    private fun setEndpointMediaSources(
        ep: RelayedEndpoint,
        audioSources: Collection<AudioSourceDesc>,
        videoSources: Collection<MediaSourceDesc>
    ) {
        ep.audioSources = audioSources.toList()
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
                        VideobridgeMetrics.numRelaysNoMessageTransportAfterDelay.inc()
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
     * Get a collection of all of the SSRCs mentioned in an RTCP packet.
     */
    private fun getRtcpSsrcs(packet: RtcpPacket): Collection<Long> {
        val ssrcs = HashSet<Long>()
        ssrcs.add(packet.senderSsrc)
        when (packet) {
            is CompoundRtcpPacket -> packet.packets.forEach { ssrcs.addAll(getRtcpSsrcs(it)) }
            is RtcpFbFirPacket -> ssrcs.add(packet.mediaSenderSsrc) // TODO: support multiple FIRs in a packet
            is RtcpFbPacket -> ssrcs.add(packet.mediaSourceSsrc)
            is RtcpSrPacket -> packet.reportBlocks.forEach { ssrcs.add(it.ssrc) }
            is RtcpRrPacket -> packet.reportBlocks.forEach { ssrcs.add(it.ssrc) }
            is RtcpSdesPacket -> packet.sdesChunks.forEach { ssrcs.add(it.ssrc) }
            is RtcpByePacket -> ssrcs.addAll(packet.ssrcs)
        }
        return ssrcs
    }

    /**
     * Call a callback on an [RtcpEventNotifier] once per [RelayedEndpoint] or [RelayEndpointSender] whose
     * SSRC is mentioned in an RTCP packet, plus on the Relay's local [Transceiver].
     * Do not call the event notifier which was the source of this packet, based on the [endpointId].
     */
    private fun doRtcpCallbacks(packet: RtcpPacket, endpointId: String?, callback: (RtcpEventNotifier) -> Unit) {
        val ssrcs = getRtcpSsrcs(packet)

        val eps = HashSet<String>()
        ssrcs.forEach { conference.getEndpointBySsrc(it)?.let { eps.add(it.id) } }
        endpointId?.let { eps.remove(it) }

        eps.forEach { epId ->
            /* Any given ID should only be in one or the other of these sets. */
            getEndpoint(epId)?.let { callback(it.rtcpEventNotifier) }
            senders[epId]?.let { callback(it.rtcpEventNotifier) }
        }

        if (endpointId != null) {
            callback(transceiver.rtcpEventNotifier)
        }
    }

    fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?, endpointId: String?) {
        doRtcpCallbacks(packet, endpointId) { it.notifyRtcpReceived(packet, receivedTime, external = true) }
    }

    fun rtcpPacketSent(packet: RtcpPacket, endpointId: String?) {
        doRtcpCallbacks(packet, endpointId) { it.notifyRtcpSent(packet, external = true) }
    }

    /**
     * Returns true if this endpoint's transport is 'fully' connected (both ICE and DTLS), false otherwise
     */
    private fun isTransportConnected(): Boolean = iceTransport.isConnected() && dtlsTransport.isConnected

    /* If we're connected, forward everything that didn't come in over a relay or that came from a different
       relay mesh.
        TODO: worry about bandwidth limits on relay links? */
    override fun wants(packet: PacketInfo): Boolean {
        if (!isTransportConnected()) {
            return false
        }
        if (packet is RelayedPacketInfo && packet.meshId == meshId) {
            return false
        }

        return when (packet.packet) {
            is VideoRtpPacket, is AudioRtpPacket, is RtcpSrPacket,
            is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                // We assume that we are only given PLIs/FIRs destined for this
                // endpoint. This is because Conference has to find the target
                // endpoint (belonging to this relay) anyway, and we would essentially be
                // performing the same check twice.
                true
            }
            else -> {
                logger.warn("Ignoring an unknown packet type:" + packet.packet.javaClass.simpleName)
                false
            }
        }
    }

    override fun send(packet: PacketInfo) {
        packet.endpointId?.let {
            getOrCreateRelaySender(it).sendPacket(packet)
        } ?: run {
            transceiver.sendPacket(packet)
        }
    }

    fun endpointExpired(id: String) {
        val s = senders.remove(id)
        s?.expire()
    }

    val incomingBitrateBps: Long
        get() = transceiver.getTransceiverStats().rtpReceiverStats.packetStreamStats.getBitrateBps() +
            synchronized(endpointsLock) {
                relayedEndpoints.values.sumOf { it.getIncomingStats().getBitrateBps() }
            }

    val incomingPacketRate: Long
        get() = transceiver.getTransceiverStats().rtpReceiverStats.packetStreamStats.packetRate +
            synchronized(endpointsLock) {
                relayedEndpoints.values.sumOf { it.getIncomingStats().packetRate }
            }

    val outgoingBitrateBps: Long
        get() = transceiver.getTransceiverStats().outgoingPacketStreamStats.getBitrateBps() +
            senders.values.sumOf { it.getOutgoingStats().getBitrateBps() }

    val outgoingPacketRate: Long
        get() = transceiver.getTransceiverStats().outgoingPacketStreamStats.packetRate +
            senders.values.sumOf { it.getOutgoingStats().packetRate }

    /**
     * Updates the conference statistics with value from this endpoint. Since
     * the values are cumulative this should execute only once when the endpoint
     * expires.
     */
    private fun updateStatsOnExpire() {
        val transceiverStats = transceiver.getTransceiverStats()

        // Add stats from the local transceiver
        val incomingStats = transceiverStats.rtpReceiverStats.packetStreamStats
        val outgoingStats = transceiverStats.outgoingPacketStreamStats

        statistics.bytesReceived.getAndAdd(incomingStats.bytes)
        statistics.packetsReceived.getAndAdd(incomingStats.packets)
        statistics.bytesSent.getAndAdd(outgoingStats.bytes)
        statistics.packetsSent.getAndAdd(outgoingStats.packets)

        VideobridgeMetrics.totalRelayBytesReceived.add(statistics.bytesReceived.get())
        VideobridgeMetrics.totalRelayBytesSent.add(statistics.bytesSent.get())
        VideobridgeMetrics.relayPacketsReceived.add(statistics.packetsReceived.get())
        VideobridgeMetrics.relayPacketsSent.add(statistics.packetsSent.get())

        VideobridgeMetrics.keyframesReceived.addAndGet(
            transceiverStats.rtpReceiverStats.videoParserStats.numKeyframes.toLong()
        )
        VideobridgeMetrics.layeringChangesReceived.addAndGet(
            transceiverStats.rtpReceiverStats.videoParserStats.numLayeringChanges.toLong()
        )
        val durationActiveVideo = transceiverStats.rtpReceiverStats.incomingStats.ssrcStats.values.filter {
            it.mediaType == MediaType.VIDEO
        }.sumOf { it.durationActive }
        VideobridgeMetrics.totalVideoStreamMillisecondsReceived.add(durationActiveVideo.toMillis())
    }

    fun expire() {
        expired = true
        logger.info("Expiring.")
        synchronized(endpointsLock) {
            relayedEndpoints.values.forEach { it.expire() }
        }
        senders.values.forEach { it.expire() }
        conference.relayExpired(this)

        try {
            updateStatsOnExpire()
            transceiver.stop()
            srtpTransformers?.close()
            logger.cdebug { transceiver.debugState(DebugStateMode.FULL).toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            transceiver.teardown()
            messageTransport.close()
            sctpHandler?.stop()
            sctpTransport?.stop()
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
        private val droppedPacketsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "relay_srtp_send_queue_dropped_packets",
            "Number of packets dropped out of the Relay SRTP send queue."
        )

        private val exceptionsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "relay_srtp_send_queue_exceptions",
            "Number of exceptions from the Relay SRTP send queue."
        )

        /** Count the number of dropped packets and exceptions. */
        @JvmField
        val queueErrorCounter = object : CountingErrorHandler() {
            override fun packetDropped() = super.packetDropped().also {
                droppedPacketsMetric.inc()
                QueueMetrics.droppedPackets.inc()
            }

            override fun packetHandlingFailed(t: Throwable?) = super.packetHandlingFailed(t).also {
                exceptionsMetric.inc()
                QueueMetrics.exceptions.inc()
            }
        }

        private const val SRTP_QUEUE_ENTRY_EVENT = "Entered Relay SRTP sender outgoing queue"
        private const val SRTP_QUEUE_EXIT_EVENT = "Exited Relay SRTP sender outgoing queue"
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

    private inner class SctpCallbacks(transport: DcSctpTransport) : DcSctpBaseCallbacks(transport) {
        override fun sendPacketWithStatus(packet: ByteArray): SendPacketStatus {
            try {
                val newBuf = ByteBufferPool.getBuffer(packet.size)
                System.arraycopy(packet, 0, newBuf, 0, packet.size)

                sctpSendPcap.observe(newBuf, 0, packet.size)
                dtlsTransport.sendDtlsData(newBuf, 0, packet.size)

                return SendPacketStatus.kSuccess
            } catch (e: Throwable) {
                logger.warn("Exception sending SCTP packet", e)
                return SendPacketStatus.kError
            }
        }

        override fun OnMessageReceived(message: DcSctpMessage) {
            try {
                // We assume all data coming over SCTP will be datachannel data
                val dataChannelPacket = DataChannelPacket(message)
                // Post the rest of the task here because the current context is
                // holding a lock inside the SctpSocket which can cause a deadlock
                // if two endpoints are trying to send datachannel messages to one
                // another (with stats broadcasting it can happen often)
                incomingDataChannelMessagesQueue.add(PacketInfo(dataChannelPacket))
            } catch (e: Throwable) {
                logger.warn("Exception processing SCTP message", e)
            }
        }

        override fun OnError(error: ErrorKind, message: String) {
            logger.warn("SCTP error $error: $message")
        }

        override fun OnAborted(error: ErrorKind, message: String) {
            logger.info("SCTP aborted with error $error: $message")
        }

        override fun OnConnected() {
            try {
                logger.info("SCTP connection is ready, creating the Data channel stack")
                val dataChannelStack = DataChannelStack(
                    { data, sid, ppid ->
                        val message = DcSctpMessage(sid.toShort(), ppid, data.array())
                        val status = sctpTransport?.send(message, DcSctpTransport.DEFAULT_SEND_OPTIONS)
                        return@DataChannelStack if (status == SendStatus.kSuccess) {
                            0
                        } else {
                            logger.error("Error sending to SCTP: $status")
                            -1
                        }
                    },
                    logger
                )
                this@Relay.dataChannelStack = dataChannelStack
                // This handles if the remote side will be opening the data channel
                dataChannelStack.onDataChannelStackEvents { dataChannel ->
                    logger.info("Remote side opened a data channel.")
                    messageTransport.setDataChannel(dataChannel)
                }
                dataChannelHandler.setDataChannelStack(dataChannelStack)
                if (sctpRole == Sctp.Role.CLIENT) {
                    // This logic is for opening the data channel locally
                    logger.info("Will open the data channel.")
                    val dataChannel = dataChannelStack.createDataChannel(
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
            } catch (e: Throwable) {
                logger.warn("Exception processing SCTP connected event", e)
            }
        }

        override fun OnClosed() {
            // I don't think this should happen, except during shutdown.
            logger.info("SCTP connection closed")
        }
    }

    private inner class TransceiverEventHandlerImpl : TransceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long): Boolean {
            /* We shouldn't receive audio levels from the local transceiver, since all media should be
             * processed by the media endpoints.
             */
            logger.warn { "Audio level reported by relay transceiver for source $sourceSsrc" }
            return false
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
        fun handleIncomingPacket(packetInfo: RelayedPacketInfo)
    }
}
