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

import org.ice4j.util.Buffer
import org.jitsi.config.JitsiConfig
import org.jitsi.dcsctp4j.DcSctpMessage
import org.jitsi.dcsctp4j.ErrorKind
import org.jitsi.dcsctp4j.SendPacketStatus
import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.metaconfig.config
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
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
import org.jitsi.nlj.transform.node.ToggleablePcapWriter
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.NEVER
import org.jitsi.utils.concurrent.PeriodicRunnable
import org.jitsi.utils.concurrent.RecurringRunnableExecutor
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.mins
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.sumOf
import org.jitsi.videobridge.cc.BandwidthProbing
import org.jitsi.videobridge.cc.allocation.BandwidthAllocation
import org.jitsi.videobridge.cc.allocation.BitrateController
import org.jitsi.videobridge.cc.allocation.EffectiveConstraintsMap
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.datachannel.DataChannelStack
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket
import org.jitsi.videobridge.dcsctp.DcSctpBaseCallbacks
import org.jitsi.videobridge.dcsctp.DcSctpHandler
import org.jitsi.videobridge.dcsctp.DcSctpTransport
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ConnectionStats
import org.jitsi.videobridge.message.ForwardedSourcesMessage
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.message.SenderSourceConstraintsMessage
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.RelayedEndpoint
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
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.json.simple.JSONObject
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import org.jitsi.videobridge.sctp.SctpConfig.Companion.config as sctpConfig

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

    /** The time at which this endpoint was created */
    val creationTime = clock.instant()

    private val sctpHandler = if (sctpConfig.enabled) DcSctpHandler() else null

    /** The [DcSctpTransport] instance we'll use to manage the SCTP connection */
    private var sctpTransport: DcSctpTransport? = null

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

    /* TODO: do we ever want to support useUniquePort for an Endpoint? */
    private val iceTransport = IceTransport(id, iceControlling, false, supportsPrivateAddresses, logger)
    private val dtlsTransport = DtlsTransport(logger, id).also { it.cryptex = CryptexConfig.endpoint }

    private var cryptex: Boolean = CryptexConfig.endpoint

    private val diagnosticContext = conference.newDiagnosticContext().apply {
        put("endpoint_id", id)
    }

    private val timelineLogger = logger.createChildLogger("timeline.${this.javaClass.name}")

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
        rtpSender.preProcesor = { packetInfo -> preProcess(packetInfo) }
    }

    /**
     * Perform processing of the packet before it goes through the rest of the [transceiver] send pipeline:
     * 1. Update the bitrate controller state and apply the source projection logic
     * 2. Perform SSRC re-writing if [doSsrcRewriting] is set.
     */
    private fun preProcess(packetInfo: PacketInfo): PacketInfo? {
        when (val packet = packetInfo.packet) {
            is VideoRtpPacket -> {
                if (!bitrateController.transformRtp(packetInfo)) {
                    logger.warn("Dropping a packet which was supposed to be accepted:$packet")
                    return null
                }
                // The original packet was transformed in place.
                if (doSsrcRewriting) {
                    val start = packet !is ParsedVideoPacket || (packet.isKeyframe && packet.isStartOfFrame)
                    if (!videoSsrcs.rewriteRtp(packet, start)) {
                        return null
                    }
                }
            }
            is AudioRtpPacket -> if (doSsrcRewriting) audioSsrcs.rewriteRtp(packet)
            is RtcpSrPacket -> {
                // Allow the BC to update the timestamp (in place).
                bitrateController.transformRtcp(packet)
                if (doSsrcRewriting) {
                    // Just check both tables instead of looking up the type first.
                    if (!videoSsrcs.rewriteRtcp(packet) && !audioSsrcs.rewriteRtcp(packet)) {
                        return null
                    }
                }
            }
        }
        return packetInfo
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

    /**
     * Latest bandwidth received from bandwidth estimator.
     */
    private var latestBandwidth: Bandwidth? = null

    /**
     * Recurring event to send connection stats messages.
     */
    private val connectionStatsSender =
        if (ConnectionStatsConfig.enabled) {
            object : PeriodicRunnable(ConnectionStatsConfig.interval.toMillis()) {
                override fun run() {
                    super.run()
                    latestBandwidth?.let { sendMessage(ConnectionStats(it.bps)) }
                }
            }.also {
                recurringRunnableExecutor.registerRecurringRunnable(it)
            }
        } else {
            null
        }

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        VideobridgeMetrics.totalEndpoints.inc()
        if (visitor) {
            VideobridgeMetrics.totalVisitors.inc()
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
    override var audioSources: List<AudioSourceDesc> = ArrayList()
        set(newValue) {
            val oldValue = field
            val removedDescs = oldValue.filterNot { newValue.contains(it) }.toSet()
            conference.removeAudioSources(removedDescs)
            val addedDescs = newValue.filterNot { oldValue.contains(it) }.toSet()
            conference.addAudioSources(addedDescs)
            field = newValue
        }

    private fun setupIceTransport() {
        iceTransport.incomingDataHandler = object : IceTransport.IncomingDataHandler {
            override fun dataReceived(buffer: Buffer) {
                if (looksLikeDtls(buffer.buffer, buffer.offset, buffer.length)) {
                    // DTLS transport is responsible for making its own copy, because it will manage its own buffers
                    dtlsTransport.enqueueBuffer(buffer)
                } else {
                    val pktInfo =
                        PacketInfo(UnparsedPacket(buffer.buffer, buffer.offset, buffer.length)).apply {
                            this.receivedTime = buffer.receivedTime
                        }
                    pktInfo.packetOrigin = PacketOrigin.Routed
                    transceiver.handleIncomingPacket(pktInfo)
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

            override fun connected() {
            }

            override fun failed() {
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

        iceTransport.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
        packetInfo.addEvent(SENT_OVER_ICE_TRANSPORT_EVENT)
        PacketTransitStats.packetSent(packetInfo)
        ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
        packetInfo.sent()
        if (timelineLogger.isTraceEnabled && logTimeline()) {
            timelineLogger.trace { packetInfo.timeline.toString() }
        }
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
        sctpPipeline.processPacket(PacketInfo(UnparsedPacket(data, off, len)))

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
                newEffectiveConstraints.entries.filter {
                    !it.value.isDisabled() && it.key.videoType.isEnabled()
                }.map { it.key }.toMutableList()

            // Populate any currently-disabled sources with an enabled video type into the ssrc cache, if there's room,
            // so we have mappings for them already if they become enabled later (e.g. because of lastN).
            val remainingMapSpace = videoSsrcs.size - newActiveSources.size
            if (remainingMapSpace > 0) {
                newActiveSources += newEffectiveConstraints.entries.filter {
                    it.value.isDisabled() && it.key.videoType.isEnabled()
                }.take(remainingMapSpace).map { it.key }.toList()
            }

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
     * Create an SCTP connection for this Endpoint. If [OPEN_DATA_CHANNEL_LOCALLY] is true,
     * we will create the data channel locally, otherwise we will wait for the remote side
     * to open it.
     */
    fun createSctpConnection() {
        if (sctpConfig.enabled) {
            logger.cdebug { "Creating SCTP transport" }
            sctpTransport = DcSctpTransport(id, logger).also {
                it.start(SctpCallbacks(it))
                sctpHandler?.setSctpTransport(it)
            }
        } else {
            logger.error("Not creating SCTP connection, SCTP is disabled in configuration.")
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
                        VideobridgeMetrics.numEndpointsNoMessageTransportAfterDelay.inc()
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
        val remoteFingerprints = mutableMapOf<String, MutableList<String>>()
        val fingerprintExtensions = transportInfo.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java)
        fingerprintExtensions.forEach { fingerprintExtension ->
            if (fingerprintExtension.hash != null && fingerprintExtension.fingerprint != null) {
                remoteFingerprints.getOrPut(fingerprintExtension.hash.lowercase()) { mutableListOf() }
                    .add(fingerprintExtension.fingerprint)
            } else {
                logger.info("Ignoring empty DtlsFingerprint extension: ${transportInfo.toXML()}")
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

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }

        return iceUdpTransportPacketExtension
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     */
    fun handleIncomingPacket(packetInfo: PacketInfo) {
        if (visitor) {
            if (packetInfo.packet !is RtcpFbPliPacket && packetInfo.packet !is RtcpFbFirPacket) {
                /* Never forward RTP/RTCP from a visitor, except PLI/FIR. */
                ByteBufferPool.returnBuffer(packetInfo.packet.buffer)
                return
            }
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

    override fun send(packetInfo: PacketInfo) = transceiver.sendPacket(packetInfo)

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

    override fun otherEndpointExpired(expired: AbstractEndpoint) = super.otherEndpointExpired(expired).also {
        if (doSsrcRewriting) {
            // Remove the expired endpoint's sources. We don't want duplicate entries in case the endpoint is
            // re-created (e.g. after an ICE restart).
            audioSsrcs.removeByOwner(expired.id)
            videoSsrcs.removeByOwner(expired.id)
        }
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
            is AudioRtpPacket -> acceptAudio && conference.isEndpointAudioWanted(id, packet.ssrc)
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
        val transceiverStats = transceiver.getTransceiverStats()

        val incomingStats = transceiverStats.rtpReceiverStats.packetStreamStats
        val outgoingStats = transceiverStats.outgoingPacketStreamStats
        VideobridgeMetrics.totalBytesReceived.add(incomingStats.bytes)
        VideobridgeMetrics.totalBytesSent.add(outgoingStats.bytes)
        VideobridgeMetrics.packetsReceived.addAndGet(incomingStats.packets)
        VideobridgeMetrics.packetsSent.addAndGet(outgoingStats.packets)
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

        if (iceTransport.isConnected() && !dtlsTransport.isConnected) {
            logger.info("Expiring an endpoint with ICE connected, but not DTLS.")
            VideobridgeMetrics.endpointsDtlsFailed.inc()
        }
    }

    override fun debugState(mode: DebugStateMode): JSONObject = super.debugState(mode).apply {
        put("bitrate_controller", bitrateController.debugState(mode))
        put("bandwidth_probing", bandwidthProbing.getDebugState())
        put("ice_transport", iceTransport.getDebugState())
        put("dtls_transport", dtlsTransport.getDebugState())
        put("transceiver", transceiver.debugState(mode))
        put("accept_audio", acceptAudio)
        put("accept_video", acceptVideo)
        put("visitor", visitor)
        put("message_transport", messageTransport.debugState)
        if (doSsrcRewriting) {
            put("audio_ssrcs", audioSsrcs.getDebugState())
            put("video_ssrcs", videoSsrcs.getDebugState())
        }
        sctpTransport?.let {
            put("sctp", it.getDebugState())
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
            logger.cdebug { transceiver.debugState(DebugStateMode.FULL).toJSONString() }
            logger.cdebug { bitrateController.debugState(DebugStateMode.FULL).toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            transceiver.teardown()
            messageTransport.close()
            sctpHandler?.stop()
            sctpTransport?.stop()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        bandwidthProbing.enabled = false
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing)
        connectionStatsSender?.let {
            recurringRunnableExecutor.deRegisterRecurringRunnable(it)
        }
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
        private val droppedPacketsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "srtp_send_queue_dropped_packets",
            "Number of packets dropped out of the Endpoint SRTP send queue."
        )

        private val exceptionsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "srtp_send_queue_exceptions",
            "Number of exceptions from the Endpoint SRTP send queue."
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
        private val timelineFraction: Long by config {
            "jmt.debug.packet-timeline.log-fraction".from(JitsiConfig.newConfig)
        }

        fun logTimeline() = timelineCounter.getAndIncrement() % timelineFraction == 0L

        private const val SRTP_QUEUE_ENTRY_EVENT = "Entered Endpoint SRTP sender outgoing queue"
        private const val SRTP_QUEUE_EXIT_EVENT = "Exited Endpoint SRTP sender outgoing queue"
        private const val SENT_OVER_ICE_TRANSPORT_EVENT = "Sent over the ICE transport"

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
                this@Endpoint.dataChannelStack = dataChannelStack
                // This handles if the remote side will be opening the data channel
                dataChannelStack.onDataChannelStackEvents { dataChannel ->
                    logger.info("Remote side opened a data channel.")
                    messageTransport.setDataChannel(dataChannel)
                }
                dataChannelHandler.setDataChannelStack(dataChannelStack)
                logger.info("Will wait for the remote side to open the data channel.")
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
        override fun audioLevelReceived(sourceSsrc: Long, level: Long): Boolean =
            conference.levelChanged(this@Endpoint, level)

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            latestBandwidth = newValue
            bitrateController.bandwidthChanged(newValue.bps.toLong())
            bandwidthProbing.bandwidthEstimationChanged(newValue)
        }
    }
}
