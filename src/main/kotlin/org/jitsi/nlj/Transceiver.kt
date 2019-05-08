/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj

import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateObserver
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.PacketIOActivity
import org.jitsi.nlj.stats.TransceiverStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.Logger
import org.jitsi.utils.MediaType
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.BandwidthEstimatorImpl
import org.jitsi_modified.service.neomedia.rtp.BandwidthEstimator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

// This is an API class, so its usages will largely be outside of this library
@Suppress("unused")
/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written via [handleIncomingPacket].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead (then we could have all senders push to a single queue and
 * have the one thread just read from the queue and send, rather than that thread
 * having to read from a bunch of individual queues)
 */
class Transceiver(
    private val id: String,
    receiverExecutor: ExecutorService,
    senderExecutor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    backgroundExecutor: ScheduledExecutorService,
    diagnosticContext: DiagnosticContext,
    logLevelDelegate: Logger? = null
) : Stoppable, NodeStatsProducer, RemoteBitrateObserver {
    private val logger = getLogger(this.javaClass, logLevelDelegate)
    private val rtpExtensions = mutableMapOf<Byte, RtpExtension>()
    private val payloadTypes = mutableMapOf<Byte, PayloadType>()
    private val receiveSsrcs = ConcurrentHashMap.newKeySet<Long>()
    val packetIOActivity = PacketIOActivity()
    private val endpointConnectionStats = EndpointConnectionStats()
    /**
     * A central place to subscribe to be notified on the reception or transmission of RTCP packets for
     * this transceiver.  This is intended to be used by internal entities: mainly logic for things like generating
     * SRs and RRs and calculating RTT.  Since it is used for both send and receive, it is held here and passed to
     * the sender and receive so each can push or subscribe to updates.
     */
    private val rtcpEventNotifier = RtcpEventNotifier()

    private var mediaStreamTracks = MediaStreamTracks()

    private val transportCcEngine = TransportCCEngine(diagnosticContext, this)

    private val bandwidthEstimator: BandwidthEstimatorImpl = BandwidthEstimatorImpl(diagnosticContext)

    private val rtpSender: RtpSender = RtpSenderImpl(
            id,
            transportCcEngine,
            rtcpEventNotifier,
            senderExecutor,
            backgroundExecutor,
            logLevelDelegate
    )
    private val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            id,
            { rtcpPacket ->
                rtpSender.sendPacket(PacketInfo(rtcpPacket))
            },
            transportCcEngine,
            rtcpEventNotifier,
            receiverExecutor,
            backgroundExecutor,
            logLevelDelegate
        )

    init {
        rtcpEventNotifier.addRtcpEventListener(endpointConnectionStats)

        endpointConnectionStats.addListener(bandwidthEstimator)
        rtcpEventNotifier.addRtcpEventListener(bandwidthEstimator)

        endpointConnectionStats.addListener(rtpSender)
    }

    override fun onReceiveBitrateChanged(ssrcs: MutableCollection<Long>?, bandwidth: Long) {
        bandwidthEstimator.updateReceiverEstimate(bandwidth)
        rtpReceiver.handleEvent(BandwidthEstimationChangedEvent(bandwidth))
    }

    fun onBandwidthEstimateChanged(listener: BandwidthEstimator.Listener) {
        bandwidthEstimator.addListener(listener)
    }

    // TODO(brian): we expose this because the bitratecontroller in jvb needs access to it but it just uses it
    // to get the latest estimate, maybe we can give it that information another way without having to expose this
    // (a getLatestAvailableBandwidthEstimate method on transceiver? or something else?  the whole bandwidth estimation
    // flow (abs send time -> transport cc engine -> bandwidthestimator -> sendsidebwe) feels a bit jumpy
    fun getBandwidthEstimator(): BandwidthEstimator = bandwidthEstimator

    /**
     * Handle an incoming [PacketInfo] (that is, a packet received by the endpoint
     * this transceiver is associated with) to be processed by the receiver pipeline.
     */
    fun handleIncomingPacket(p: PacketInfo) {
        packetIOActivity.lastPacketReceivedTimestampMs = System.currentTimeMillis()
        rtpReceiver.enqueuePacket(p)
    }

    /**
     * Send packets to the endpoint this transceiver is associated with by
     * passing them out the sender's outgoing pipeline
     */
    fun sendPacket(packetInfo: PacketInfo) {
        packetIOActivity.lastPacketSentTimestampMs = System.currentTimeMillis()
        rtpSender.sendPacket(packetInfo)
    }

    fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = rtpSender.sendProbing(mediaSsrc, numBytes)

    /**
     * Set a handler to be invoked when incoming RTP packets have finished
     * being processed.
     */
    fun setIncomingRtpHandler(rtpHandler: PacketHandler) {
        rtpReceiver.rtpPacketHandler = rtpHandler
    }

    /**
     * Set a handler to be invoked when outgoing packets have finished
     * being processed (and are ready to be sent)
     */
    fun setOutgoingPacketHandler(outgoingPacketHandler: PacketHandler) {
        rtpSender.onOutgoingPacket(outgoingPacketHandler)
    }

    /**
     * Set a handler to be invoked when incoming RTCP packets (which have not
     * bee terminated) have finished being processed.
     */
    fun setIncomingRtcpHandler(rtcpHandler: PacketHandler) {
        rtpReceiver.rtcpPacketHandler = rtcpHandler
    }

    fun addReceiveSsrc(ssrc: Long) {
        logger.cdebug { "${hashCode()} adding receive ssrc $ssrc" }
        receiveSsrcs.add(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcAddedEvent(ssrc))
        // TODO: fire events to rtp sender as well
    }

    fun removeReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} removing receive ssrc $ssrc" }
        receiveSsrcs.remove(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcRemovedEvent(ssrc))
    }

    /**
     * Set the 'local' bridge SSRC to [ssrc] for [mediaType]
     */
    fun setLocalSsrc(mediaType: MediaType, ssrc: Long) {
        val localSsrcSetEvent = SetLocalSsrcEvent(mediaType, ssrc)
        rtpSender.handleEvent(localSsrcSetEvent)
        rtpReceiver.handleEvent(localSsrcSetEvent)
    }

    fun receivesSsrc(ssrc: Long): Boolean = receiveSsrcs.contains(ssrc)

    fun setMediaStreamTracks(mediaStreamTracks: Array<MediaStreamTrackDesc>): Boolean {
        logger.cdebug { "$id setting media stream tracks: ${mediaStreamTracks.joinToString()}" }
        val ret = this.mediaStreamTracks.setMediaStreamTracks(mediaStreamTracks)
        rtpReceiver.handleEvent(SetMediaStreamTracksEvent(this.mediaStreamTracks.getMediaStreamTracks()))
        return ret
    }

    // TODO(brian): we should only expose an immutable version of this, but Array doesn't have that.  Go in
    // and change all the storage of the media stream tracks to use a list
    fun getMediaStreamTracks(): Array<MediaStreamTrackDesc> = mediaStreamTracks.getMediaStreamTracks()

    fun requestKeyFrame(mediaSsrc: Long) = rtpSender.requestKeyframe(mediaSsrc)

    fun addPayloadType(payloadType: PayloadType) {
        payloadTypes[payloadType.pt] = payloadType
        logger.cdebug { "Payload type added: $payloadType" }
        val rtpPayloadTypeAddedEvent = RtpPayloadTypeAddedEvent(payloadType)
        rtpReceiver.handleEvent(rtpPayloadTypeAddedEvent)
        rtpSender.handleEvent(rtpPayloadTypeAddedEvent)
    }

    fun clearPayloadTypes() {
        logger.cinfo { "All payload types being cleared" }
        val rtpPayloadTypeClearEvent = RtpPayloadTypeClearEvent()
        rtpReceiver.handleEvent(rtpPayloadTypeClearEvent)
        rtpSender.handleEvent(rtpPayloadTypeClearEvent)
        payloadTypes.clear()
    }

    fun addRtpExtension(rtpExtension: RtpExtension) {
        logger.cdebug { "Adding RTP extension: $rtpExtension" }
        rtpExtensions[rtpExtension.id] = rtpExtension
        val rtpExtensionAddedEvent = RtpExtensionAddedEvent(rtpExtension)
        rtpReceiver.handleEvent(rtpExtensionAddedEvent)
        rtpSender.handleEvent(rtpExtensionAddedEvent)
    }

    fun clearRtpExtensions() {
        logger.cinfo { "Clearing all RTP extensions" }
        // TODO: ignoring this for now, since we'll have conflicts from each channel calling it
//        val rtpExtensionClearEvent = RtpExtensionClearEvent()
//        rtpReceiver.handleEvent(rtpExtensionClearEvent)
//        rtpSender.handleEvent(rtpExtensionClearEvent)
//        rtpExtensions.clear()
    }

    fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        logger.cdebug { "Setting audio level listener $audioLevelListener" }
        rtpReceiver.setAudioLevelListener(audioLevelListener)
    }

    // TODO(brian): we may want to handle local and remote ssrc associations differently, as different parts of the
    // code care about one or the other, but currently there is no issue treating them the same.
    // note(george): we need to be careful with this not to cause any side-effects with rtx
    fun addSsrcAssociation(primarySsrc: Long, secondarySsrc: Long, type: SsrcAssociationType) {
        logger.cdebug { "Adding SSRC association: $primarySsrc <-> $secondarySsrc ($type)" }
        val ssrcAssociationEvent = SsrcAssociationEvent(primarySsrc, secondarySsrc, type)
        rtpReceiver.handleEvent(ssrcAssociationEvent)
        rtpSender.handleEvent(ssrcAssociationEvent)
    }

    fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        logger.cdebug { "Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "tls role: $tlsRole" }
        val srtpTransformers = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            tlsRole)

        rtpReceiver.setSrtpTransformers(srtpTransformers)
        rtpSender.setSrtpTransformers(srtpTransformers)
    }

    /**
     * Get stats about this transceiver's pipeline nodes
     */
    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Transceiver $id").apply {
            addBlock(NodeStatsBlock("rtpExtensions").apply {
                rtpExtensions.forEach {
                    addString(it.key.toString(), it.value.type.toString())
                }
            })
            addBlock(NodeStatsBlock("payloadTypes").apply {
                payloadTypes.forEach {
                    addString(it.key.toString(), it.value.toString())
                }
            })
            addString("receiveSsrcs", receiveSsrcs.toString())
            addBlock(mediaStreamTracks.getNodeStats())
            addString("endpointConnectionStats", endpointConnectionStats.getSnapshot().toString())
            addBlock(bandwidthEstimator.getNodeStats())
            addBlock(rtpReceiver.getNodeStats())
            addBlock(rtpSender.getNodeStats())
        }
    }

    /**
     * Get various media and network stats
     */
    fun getTransceiverStats(): TransceiverStats {
        return TransceiverStats(
            endpointConnectionStats.getSnapshot(),
            rtpReceiver.getStreamStats(),
            rtpSender.getStreamStats(),
            bandwidthEstimator.statistics)
    }

    override fun stop() {
        rtpReceiver.stop()
        rtpSender.stop()
    }

    fun teardown() {
        rtpReceiver.tearDown()
        rtpSender.tearDown()
    }

    companion object {
        init {
//            Node.plugins.add(BufferTracePlugin)
//            Node.PLUGINS_ENABLED = true
        }
    }
}

/**
 * Extracts a [NodeStatsBlock] from a [BandwidthEstimatorImpl]. This is here temporarily, once we figure out
 * what to do with [BandwidthEstimator] it should go away or move.
 */
fun BandwidthEstimatorImpl.getNodeStats(): NodeStatsBlock = NodeStatsBlock("BandwidthEstimator").apply {
    addNumber("latestREMB", latestREMB)
    addNumber("latestEstimate", latestEstimate)
    addNumber("latestFractionLoss", latestFractionLoss)
    val bweStats: BandwidthEstimator.Statistics = statistics
    addNumber("lossDegradedMs", bweStats.lossDegradedMs)
    addNumber("lossFreeMs", bweStats.lossFreeMs)
    addNumber("lossLimitedMs", bweStats.lossLimitedMs)
}
