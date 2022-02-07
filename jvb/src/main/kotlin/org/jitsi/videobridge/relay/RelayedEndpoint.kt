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
import org.jitsi.nlj.MediaSources
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpReceiverEventHandler
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.AddReceiverMessage
import org.jitsi.videobridge.octo.OctoPacketInfo
import org.jitsi.videobridge.util.TaskPools

class RelayedEndpoint(
    conference: Conference,
    val relay: Relay,
    id: String,
    parentLogger: Logger
) : AbstractEndpoint(conference, id, parentLogger), Relay.IncomingRelayPacketHandler {
    var audioSources: Array<AudioSourceDesc> = arrayOf()
        set(value) {
            field = value
            value.forEach { streamInformationStore.addReceiveSsrc(it.ssrc, MediaType.AUDIO) }
        }

    private val streamInformationStore: StreamInformationStore = StreamInformationStoreImpl()

    // TODO Figure this out
    private val rtcpEventNotifier = RtcpEventNotifier()

    val rtpReceiver = RtpReceiverImpl(
        id,
        { rtcpPacket ->
            if (rtcpPacket.length >= 1500) {
                logger.warn(
                    "Sending large locally-generated RTCP packet of size ${rtcpPacket.length}, " +
                        "first packet of type ${rtcpPacket.packetType}."
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
        logger
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

    fun setReceiveSsrcs(ssrcsByMediaType: Map<MediaType, Set<Long>>) {
        streamInformationStore.receiveSsrcs.forEach { ssrc ->
            streamInformationStore.removeReceiveSsrc(ssrc)
        }
        ssrcsByMediaType.forEach { (mediaType: MediaType, ssrcs: Set<Long>) ->
            ssrcs.forEach { ssrc ->
                streamInformationStore.addReceiveSsrc(ssrc, mediaType)
            }
        }
    }

    fun hasReceiveSsrcs(): Boolean = streamInformationStore.receiveSsrcs.isNotEmpty()

    /** Relayed endpoints are not automatically expired. **/
    override fun shouldExpire(): Boolean = false

    override fun requestKeyframe(mediaSsrc: Long) = relay.transceiver.requestKeyFrame(mediaSsrc)

    override fun requestKeyframe() = relay.transceiver.requestKeyFrame(mediaSource?.primarySSRC)

    override fun isSendingAudio(): Boolean = rtpReceiver.isReceivingAudio()
    override fun isSendingVideo(): Boolean = rtpReceiver.isReceivingVideo()

    /**
     * Relayed endpoints don't have their own payload types and RTP header extensions, these are properties of the
     * relay.
     */
    override fun addPayloadType(payloadType: PayloadType) = streamInformationStore.addRtpPayloadType(payloadType)
    override fun addRtpExtension(rtpExtension: RtpExtension) =
        streamInformationStore.addRtpExtensionMapping(rtpExtension)

    override fun sendVideoConstraints(maxVideoConstraints: VideoConstraints) {
        relay.sendMessage(
            AddReceiverMessage(
                conference.tentacle.bridgeId, /* TODO: store local bridge ID somewhere better */
                id,
                maxVideoConstraints
            )
        )
    }

    fun relayMessageTransportConnected() =
        sendVideoConstraints(maxReceiverVideoConstraints)

    private val _mediaSources = MediaSources()

    override val mediaSource: MediaSourceDesc?
        get() = mediaSources.getOrNull(0)
    override var mediaSources: Array<MediaSourceDesc>
        get() = _mediaSources.getMediaSources()
        set(value) {
            val changed = _mediaSources.setMediaSources(value)
            if (changed) {
                val setMediaSourcesEvent = SetMediaSourcesEvent(mediaSources)

                rtpReceiver.handleEvent(setMediaSourcesEvent)
                mediaSources.forEach {
                    it.rtpEncodings.forEach {
                        it.ssrcs.forEach {
                            streamInformationStore.addReceiveSsrc(it, MediaType.VIDEO)
                        }
                    }
                }
            }
        }

    val ssrcs: Set<Long>
        get() = HashSet<Long>().also { set ->
            audioSources.forEach { set.add(it.ssrc) }
            mediaSources.forEach { it.rtpEncodings.forEach { set.addAll(it.ssrcs) } }
        }

    fun setSrtpInformation(srtpTransformers: SrtpTransformers) {
        rtpReceiver.setSrtpTransformers(srtpTransformers)
    }

    override fun handleIncomingPacket(packetInfo: OctoPacketInfo) = rtpReceiver.processPacket(packetInfo)

    private inner class RtpReceiverEventHandlerImpl : RtpReceiverEventHandler {
        /**
         * Forward audio level events from the Transceiver to the conference. We use the same thread, because this fires
         * for every packet and we want to avoid the switch. The conference audio level code must not block.
         */
        override fun audioLevelReceived(sourceSsrc: Long, level: Long) {
            conference.speechActivity.levelChanged(this@RelayedEndpoint, level)
        }

        /**
         * Forward bwe events from the Transceiver.
         */
        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
            logger.cdebug { "Estimated bandwidth is now $newValue" }
            /* We don't use BWE for relay connections. */
        }
    }
}
