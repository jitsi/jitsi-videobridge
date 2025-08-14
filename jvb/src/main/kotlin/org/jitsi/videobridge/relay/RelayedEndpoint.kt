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

import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.Features
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.MediaSources
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpReceiverEventHandler
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.copy
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.AddReceiverMessage
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import java.time.Instant

/**
 * An object that handles media received from a single remote endpoint to a relay.
 */
class RelayedEndpoint(
    conference: Conference,
    val relay: Relay,
    id: String,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext
) : AbstractEndpoint(conference, id, parentLogger), Relay.IncomingRelayPacketHandler {
    override var audioSources: List<AudioSourceDesc> = listOf()
        set(value) {
            field = value
            value.forEach {
                streamInformationStore.addReceiveSsrc(it.ssrc, MediaType.AUDIO)
                conference.addEndpointSsrc(this, it.ssrc)
            }
        }

    private val streamInformationStore: StreamInformationStore = StreamInformationStoreImpl()

    val rtcpEventNotifier = RtcpEventNotifier().apply {
        addRtcpEventListener(
            object : RtcpListener {
                override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?) {
                    relay.rtcpPacketReceived(packet, receivedTime, id)
                }
                override fun rtcpPacketSent(packet: RtcpPacket) {
                    throw IllegalStateException("got rtcpPacketSent callback from a receiver")
                }
            },
            external = true
        )
    }

    private val rtpReceiver = RtpReceiverImpl(
        "${relay.id}-$id",
        { rtcpPacket ->
            if (rtcpPacket.length >= 1500) {
                logger.warn(
                    "Sending large locally-generated RTCP packet of size ${rtcpPacket.length}, " +
                        "first packet of type ${rtcpPacket.packetType} rc ${rtcpPacket.reportCount}."
                )
            }
            /* TODO */
            relay.transceiver.sendPacket(PacketInfo(rtcpPacket))
        },
        rtcpEventNotifier,
        TaskPools.CPU_POOL,
        TaskPools.SCHEDULED_POOL,
        streamInformationStore,
        RtpReceiverEventHandlerImpl(),
        logger,
        diagnosticContext
    ).apply {
        packetHandler = object : ConsumerNode("receiver chain handler") {
            override fun consume(packetInfo: PacketInfo) {
                packetInfo.endpointId = id
                conference.handleIncomingPacket(packetInfo)
            }

            override fun trace(f: () -> Unit) = f.invoke()
        }
        handleEvent(SetLocalSsrcEvent(MediaType.AUDIO, conference.localAudioSsrc))
        handleEvent(SetLocalSsrcEvent(MediaType.VIDEO, conference.localVideoSsrc))
    }

    override fun receivesSsrc(ssrc: Long): Boolean {
        return streamInformationStore.receiveSsrcs.contains(ssrc)
    }

    override val ssrcs
        get() = HashSet(streamInformationStore.receiveSsrcs)

    // Visitors are never advertised between relays, so relayed endpoints are never visitors.
    override val visitor = false

    /** Relayed endpoints are not automatically expired. **/
    override fun shouldExpire(): Boolean = false

    override fun requestKeyframe(mediaSsrc: Long) = relay.transceiver.requestKeyFrame(mediaSsrc)

    override fun requestKeyframe() = relay.transceiver.requestKeyFrame(mediaSource?.primarySSRC)

    override val isSendingAudio
        get() = rtpReceiver.isReceivingAudio()
    override val isSendingVideo
        get() = rtpReceiver.isReceivingVideo()

    override fun addPayloadType(payloadType: PayloadType) = streamInformationStore.addRtpPayloadType(payloadType)
    override fun addRtpExtension(rtpExtension: RtpExtension) =
        streamInformationStore.addRtpExtensionMapping(rtpExtension)

    override fun setExtmapAllowMixed(allow: Boolean) = streamInformationStore.setExtmapAllowMixed(allow)

    override fun sendVideoConstraints(sourceName: String, maxVideoConstraints: VideoConstraints) {
        relay.sendMessage(
            AddReceiverMessage(
                RelayConfig.config.relayId,
                sourceName,
                maxVideoConstraints
            )
        )
    }

    fun relayMessageTransportConnected() {
        maxReceiverVideoConstraints.forEach { (sourceName, constraints) ->
            sendVideoConstraints(sourceName, constraints)
        }
    }

    private val _mediaSources = MediaSources()

    override var mediaSources: Array<MediaSourceDesc>
        get() = _mediaSources.getMediaSources()
        set(value) {
            applyVideoTypeCache(value)
            val changed = _mediaSources.setMediaSources(value)
            val mergedMediaSources = _mediaSources.getMediaSources()
            val signaledMediaSources = value.copy()
            if (changed) {
                val setMediaSourcesEvent = SetMediaSourcesEvent(mergedMediaSources, signaledMediaSources)

                rtpReceiver.handleEvent(setMediaSourcesEvent)
                mediaSources.forEach {
                    it.rtpEncodings.forEach {
                        it.ssrcs.forEach {
                            streamInformationStore.addReceiveSsrc(it, MediaType.VIDEO)
                            conference.addEndpointSsrc(this, it)
                        }
                    }
                }
            }
        }

    fun setSrtpInformation(srtpTransformers: SrtpTransformers) {
        rtpReceiver.setSrtpTransformers(srtpTransformers)
    }

    override fun handleIncomingPacket(packetInfo: RelayedPacketInfo) = rtpReceiver.enqueuePacket(packetInfo)

    fun setFeature(feature: Features, enabled: Boolean) {
        rtpReceiver.setFeature(feature, enabled)
    }

    fun getIncomingStats() = rtpReceiver.getStats().packetStreamStats

    override fun debugState(mode: DebugStateMode): JSONObject = super.debugState(mode).apply {
        if (mode == DebugStateMode.FULL) {
            this["stream_information_store"] = streamInformationStore.debugState(mode)
            this["receiver"] = rtpReceiver.debugState(mode)
            this["media_sources"] = _mediaSources.debugState()
        }
    }

    private fun updateStatsOnExpire() {
        val relayStats = relay.statistics
        val rtpReceiverStats = rtpReceiver.getStats()

        // Add stats from the local transceiver
        val incomingStats = rtpReceiverStats.packetStreamStats

        relayStats.apply {
            bytesReceived.getAndAdd(incomingStats.bytes)
            packetsReceived.getAndAdd(incomingStats.packets)
        }
    }

    override fun expire() {
        if (super.isExpired) {
            return
        }
        super.expire()

        try {
            updateStatsOnExpire()
            rtpReceiver.stop()
            logger.cdebug { debugState(DebugStateMode.FULL).toJSONString() }
            rtpReceiver.tearDown()
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        logger.info("Expired.")
    }

    private inner class RtpReceiverEventHandlerImpl : RtpReceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long): Boolean =
            conference.levelChanged(this@RelayedEndpoint, level)

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            /* We don't use BWE for relay connections. */
        }
    }
}
