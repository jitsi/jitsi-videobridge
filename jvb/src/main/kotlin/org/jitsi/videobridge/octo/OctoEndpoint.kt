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

package org.jitsi.videobridge.octo

import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.videobridge.VideoConstraints
import org.jitsi.videobridge.cc.allocation.VideoConstraints2
import org.jitsi.videobridge.message.AddReceiverMessage
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.RemoveReceiverMessage

/**
 * Represents an endpoint in a conference, which is connected to another
 * jitsi-videobridge instance.
 *
 * @author Boris Grozev
 */
class OctoEndpoint(
    conference: Conference,
    id: String,
    private val octoEndpoints: OctoEndpoints,
    parentLogger: Logger
) : AbstractEndpoint(conference, id, parentLogger),
    ConfOctoTransport.IncomingOctoEpPacketHandler {

    private val transceiverEventHandler = object : TransceiverEventHandler {
        override fun audioLevelReceived(sourceSsrc: Long, level: Long) =
            conference.speechActivity.levelChanged(this@OctoEndpoint, level)
    }

    private val transceiver = OctoTransceiver(id, transceiverEventHandler, logger).apply {
        setIncomingPacketHandler(object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                packetInfo.endpointId = id
                conference.handleIncomingPacket(packetInfo)
            }
        })
        // This handler will be used for all packets that come out of
        // the transceiver, but this is only used for RTCP (keyframe requests)
        setOutgoingPacketHandler(object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                conference.tentacle.send(packetInfo)
            }
        })
    }

    init {
        conference.tentacle.addHandler(id, this)
    }

    override fun handleIncomingPacket(packetInfo: OctoPacketInfo) {
        transceiver.handleIncomingPacket(packetInfo)
    }

    override fun sendMessage(msg: BridgeChannelMessage) {
        // This is intentionally a no-op. Since a conference can have
        // multiple OctoEndpoint instances, but we want a single message
        // to be sent through Octo, the message should be sent through the
        // single OctoEndpoints instance.
    }

    override fun requestKeyframe(mediaSsrc: Long) {
        transceiver.requestKeyframe(mediaSsrc)
    }

    override fun requestKeyframe() {
        transceiver.requestKeyframe()
    }

    override fun setFeature(feature: EndpointDebugFeatures?, enabled: Boolean) {
        // NO-OP
    }

    override fun shouldExpire(): Boolean = !transceiver.hasReceiveSsrcs()

    override val mediaSources: Array<MediaSourceDesc> = transceiver.mediaSources

    /**
     * This [OctoEndpoint] aggregates the constraints from the local endpoints on this bridge, and propagates the max
     * constraints to the bridge that is local for the sending endpoint via an [AddReceiverMessage].
     */
    override fun maxReceiverVideoConstraintsChanged(maxVideoConstraints: VideoConstraints2) {
        conference.tentacle.sendMessage(
            AddReceiverMessage(
                conference.tentacle.bridgeId,
                id,
                maxVideoConstraints
            )
        )
    }

    override fun receivesSsrc(ssrc: Long): Boolean = transceiver.receivesSsrc(ssrc)

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType?) {
        // This is controlled through setReceiveSsrcs.
    }

    override fun addPayloadType(payloadType: PayloadType?) {
        transceiver.addPayloadType(payloadType)
    }

    override fun addRtpExtension(rtpExtension: RtpExtension?) {
        transceiver.addRtpExtension(rtpExtension)
    }

    fun setMediaSources(sources: Array<MediaSourceDesc>) {
        transceiver.mediaSources = sources
    }

    override fun expire() {
        if (super.isExpired()) {
            return
        }
        super.expire()
        conference.tentacle.sendMessage(RemoveReceiverMessage(conference.tentacle.bridgeId, id))
        transceiver.stop()
        logger.debug { transceiver.getNodeStats().prettyPrint() }
        conference.tentacle.removeHandler(id, this)
        octoEndpoints.endpointExpired(this)
    }

    /**
     * Sets the set SSRCs we expect to receive from this endpoint.
     */
    fun setReceiveSsrcs(ssrcsByMediaType: Map<MediaType, Set<Long>>) {
        transceiver.setReceiveSsrcs(ssrcsByMediaType)
    }

    // The endpoint is sending audio if our Receiver object is receiving audio from the endpoint.
    override fun isSendingAudio(): Boolean = transceiver.isReceivingAudio

    // The endpoint is sending video if our Receiver object is receiving video from the endpoint.
    override fun isSendingVideo(): Boolean = transceiver.isReceivingVideo
}
