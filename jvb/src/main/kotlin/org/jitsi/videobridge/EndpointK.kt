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
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.RemoteSsrcAssociation
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.videobridge.cc.BandwidthProbing
import org.jitsi.videobridge.cc.BandwidthProbingK
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.videobridge.shim.ChannelShim
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Duration
import java.time.Instant

class EndpointK @JvmOverloads constructor(
    id: String,
    conference: Conference,
    parentLogger: Logger,
    iceControlling: Boolean,
    clock: Clock = Clock.systemUTC()
) : Endpoint(id, conference, parentLogger, iceControlling, clock) {

    // TODO: this naming is to avoid conflicts with getTransceiver in Endpoint.  It will change back
    // once Endpoint.java goes away
    val _transceiver = Transceiver(
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
                this@EndpointK.handleIncomingPacket(packetInfo)
            }

            override fun trace(f: () -> Unit) = f.invoke()
        })
    }

    private val bandwidthProbing = BandwidthProbingK(
        BandwidthProbing.ProbingDataSender { mediaSsrcs, numBytes -> _transceiver.sendProbing(mediaSsrcs, numBytes) },
        java.util.function.Supplier { bitrateController.getStatusSnapshot() }
    ).apply {
        setDiagnosticContext(diagnosticContext)
        enabled = true
    }.also {
        recurringRunnableExecutor.registerRecurringRunnable(it)
    }

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()
    }

    // TODO: Some weirdness here with with a property for reading mediaSources but a function for setting them because
    //  only the getter is defined in AbstractEndpoint.  Will take another look at it.
    override val mediaSources: Array<out MediaSourceDesc>
        get() = _transceiver.getMediaSources()

    override fun getTransceiver(): Transceiver = _transceiver

    override fun setMediaSources(mediaSources: Array<MediaSourceDesc>) {
        val wasEmpty = _transceiver.getMediaSources().isEmpty()
        if (_transceiver.setMediaSources(mediaSources)) {
            eventEmitter.fireEventSync { sourcesChanged() }
        }
        if (wasEmpty) {
            sendVideoConstraints(maxReceiverVideoConstraints)
        }
    }

    override fun setupIceTransport() {
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
                    _transceiver.handleIncomingPacket(pktInfo)
                }
            }
        }
        iceTransport.eventHandler = object : IceTransport.EventHandler {
            override fun connected() {
                logger.info("ICE connected")
                eventEmitter.fireEventSync { iceSucceeded() }
                _transceiver.setOutgoingPacketHandler(object : PacketHandler {
                    override fun processPacket(packetInfo: PacketInfo) {
                        outgoingSrtpPacketQueue.add(packetInfo)
                    }
                })
                TaskPools.IO_POOL.submit(iceTransport::startReadingData)
                TaskPools.IO_POOL.submit(dtlsTransport::startDtlsHandshake)
            }

            override fun failed() {
                eventEmitter.fireEventSync { iceFailed() }
            }

            override fun consentUpdated(time: Instant) {
                _transceiver.packetIOActivity.lastIceActivityInstant = time
            }
        }
    }

    override fun setupDtlsTransport() {
        dtlsTransport.incomingDataHandler = object : DtlsTransport.IncomingDataHandler {
            override fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int) {
                this@EndpointK.dtlsAppPacketReceived(buf, off, len)
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
                _transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
                // TODO(brian): the old code would work even if the sctp connection was created after
                //  the handshake had completed, but this won't (since this is a one-time event).  do
                //  we need to worry about that case?
                sctpSocket.ifPresent(::acceptSctpConnection)
                scheduleEndpointMessageTransportTimeout()
            }
        }
    }

    override fun updateForceMute() {
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

        _transceiver.forceMuteAudio(audioForceMuted)
        _transceiver.forceMuteVideo(videoForceMuted)
    }

    override fun addPayloadType(payloadType: PayloadType) {
        _transceiver.addPayloadType(payloadType)
        bitrateController.addPayloadType(payloadType)
    }

    override fun addRtpExtension(rtpExtension: RtpExtension) {
        _transceiver.addRtpExtension(rtpExtension)
    }

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        logger.cdebug { "Adding receive ssrc $ssrc of type $mediaType" }
        _transceiver.addReceiveSsrc(ssrc, mediaType)
    }

    override fun onNewSsrcAssociation(
        endpointId: String,
        primarySsrc: Long,
        secondarySsrc: Long,
        type: SsrcAssociationType
    ) {
        if (endpointId.equals(id, ignoreCase = true)) {
            _transceiver.addSsrcAssociation(LocalSsrcAssociation(primarySsrc, secondarySsrc, type))
        } else {
            _transceiver.addSsrcAssociation(RemoteSsrcAssociation(primarySsrc, secondarySsrc, type))
        }
    }

    override fun setFeature(feature: EndpointDebugFeatures, enabled: Boolean) {
        when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> _transceiver.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled)
        }
    }

    override fun isFeatureEnabled(feature: EndpointDebugFeatures): Boolean {
        return when (feature) {
            EndpointDebugFeatures.PCAP_DUMP -> _transceiver.isFeatureEnabled(Features.TRANSCEIVER_PCAP_DUMP)
        }
    }

    override fun isSendingAudio(): Boolean {
        // The endpoint is sending audio if we (the transceiver) are receiving audio.
        return _transceiver.isReceivingAudio()
    }
    override fun isSendingVideo(): Boolean {
        // The endpoint is sending video if we (the transceiver) are receiving video.
        return _transceiver.isReceivingAudio()
    }

    override fun receivesSsrc(ssrc: Long): Boolean = _transceiver.receivesSsrc(ssrc)

    override fun getLastIncomingActivity(): Instant = _transceiver.packetIOActivity.lastIncomingActivityInstant

    override fun requestKeyframe() = _transceiver.requestKeyFrame()

    override fun requestKeyframe(mediaSsrc: Long) = _transceiver.requestKeyFrame(mediaSsrc)

    override fun send(packetInfo: PacketInfo) {
        when (val packet = packetInfo.packet) {
            is VideoRtpPacket -> {
                if (bitrateController.transformRtp(packetInfo)) {
                    // The original packet was transformed in place.
                    _transceiver.sendPacket(packetInfo)
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
        _transceiver.sendPacket(packetInfo)
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
            .max() ?: Duration.ZERO

        val lastActivity = lastIncomingActivity
        val now = clock.instant()

        if (lastActivity == NEVER) {
            val timeSinceCreation = Duration.between(creationTime, now)
            if (timeSinceCreation > EP_TIMEOUT) {
                logger.info(
                    "Endpoint's ICE connection has neither failed nor connected " +
                        "after " + timeSinceCreation + ", expiring"
                )
                return true
            }
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
        }
        if (Duration.between(lastActivity, now) > maxExpireTimeFromChannelShims) {
            logger.info("Allowing to expire because of no activity in over $maxExpireTimeFromChannelShims")
            return true
        }
        return false
    }

    override fun setLastN(lastN: Int) {
        bitrateController.lastN = lastN
    }

    override fun getLastN(): Int = bitrateController.lastN

    /**
     * Set the local SSRC for [mediaType] to [ssrc] for this endpoint.
     */
    override fun setLocalSsrc(mediaType: MediaType, ssrc: Long) = _transceiver.setLocalSsrc(mediaType, ssrc)

    /**
     * Returns true if this endpoint's transport is 'fully' connected (both ICE and DTLS), false otherwise
     */
    private fun isTransportConnected(): Boolean = iceTransport.isConnected() && dtlsTransport.isConnected

    override fun getRtt(): Double = _transceiver.getTransceiverStats().endpointConnectionStats.rtt

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
     * Updates the conference statistics with value from this endpoint. Since
     * the values are cumulative this should execute only once when the endpoint
     * expires.
     */
    private fun updateStatsOnExpire() {
        val conferenceStats = conference.statistics
        val transceiverStats = _transceiver.getTransceiverStats()

        conferenceStats.apply {
            val incomingStats = transceiverStats.incomingPacketStreamStats
            val outgoingStats = transceiverStats.outgoingPacketStreamStats
            totalBytesReceived.addAndGet(incomingStats.bytes)
            totalPacketsReceived.addAndGet(incomingStats.packets)
            totalBytesSent.addAndGet(outgoingStats.bytes)
            totalPacketsSent.addAndGet(outgoingStats.packets)
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
            // debugState.put("sctpManager", sctpManager.getDebugState());
            put("bitrateController", bitrateController.debugState)
            put("bandwidthProbing", bandwidthProbing.debugState)
            put("iceTransport", iceTransport.getDebugState())
            put("dtlsTransport", dtlsTransport.getDebugState())
            put("transceiver", _transceiver.getNodeStats().toJson())
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
            _transceiver.stop()
            logger.cdebug { _transceiver.getNodeStats().prettyPrint(0) }
            logger.cdebug { bitrateController.debugState.toJSONString() }
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            _transceiver.teardown()
            getMessageTransport()?.close()
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

    private inner class TransceiverEventHandlerImpl : TransceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long) =
            conference.speechActivity.levelChanged(this@EndpointK, level)

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
