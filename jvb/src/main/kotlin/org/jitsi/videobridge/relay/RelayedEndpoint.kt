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
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.AddReceiverMessage

class RelayedEndpoint(
    conference: Conference,
    val relay: Relay,
    id: String,
    parentLogger: Logger
) : AbstractEndpoint(conference, id, parentLogger) {
    var audioSources: Array<AudioSourceDesc> = arrayOf()

    override fun receivesSsrc(ssrc: Long): Boolean = relay.getEndpointBySsrc(ssrc) == this

    /** Relayed endpoints are not automatically expired. **/
    override fun shouldExpire(): Boolean = false

    override fun requestKeyframe(mediaSsrc: Long) = relay.transceiver.requestKeyFrame(mediaSsrc)

    override fun requestKeyframe() = relay.transceiver.requestKeyFrame(mediaSource?.primarySSRC)

    // These should be queried on the relay
    override fun isSendingAudio(): Boolean = false
    override fun isSendingVideo(): Boolean = false

    /**
     * Relayed endpoints don't have their own payload types and RTP header extensions, these are properties of the
     * relay.
     */
    override fun addPayloadType(payloadType: PayloadType) {}
    override fun addRtpExtension(rtpExtension: RtpExtension) {}

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

    override val mediaSource: MediaSourceDesc?
        get() = mediaSources.getOrNull(0)
    override var mediaSources: Array<MediaSourceDesc> = arrayOf()

    val ssrcs: Set<Long>
        get() = HashSet<Long>().also { set ->
            audioSources.forEach { set.add(it.ssrc) }
            mediaSources.forEach { it.rtpEncodings.forEach { set.addAll(it.ssrcs) } }
        }
}
