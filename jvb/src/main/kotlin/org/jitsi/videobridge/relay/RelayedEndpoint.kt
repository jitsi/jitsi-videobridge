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
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.message.BridgeChannelMessage

class RelayedEndpoint(
    conference: Conference,
    val relay: Relay,
    id: String,
    parentLogger: Logger
) : AbstractEndpoint(conference, id, parentLogger) {
    var audioSources: Array<AudioSourceDesc> = arrayOf()

    override fun receivesSsrc(ssrc: Long): Boolean {
        TODO("Not yet implemented")
    }

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType?) {
        TODO("Not yet implemented")
    }

    override fun shouldExpire(): Boolean {
        TODO("Not yet implemented")
    }

    override fun sendMessage(msg: BridgeChannelMessage?) {
        TODO("Not yet implemented")
    }

    override fun requestKeyframe(mediaSsrc: Long) {
        TODO("Not yet implemented")
    }

    override fun requestKeyframe() {
        TODO("Not yet implemented")
    }

    override fun isSendingAudio(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isSendingVideo(): Boolean {
        TODO("Not yet implemented")
    }

    override fun addPayloadType(payloadType: PayloadType?) {
        TODO("Not yet implemented")
    }

    override fun addRtpExtension(rtpExtension: RtpExtension?) {
        TODO("Not yet implemented")
    }

    override fun sendVideoConstraints(maxVideoConstraints: VideoConstraints) {
        TODO("Not yet implemented")
    }

    override val mediaSource: MediaSourceDesc?
        get() = mediaSources.getOrNull(0)
    override var mediaSources: Array<MediaSourceDesc> = arrayOf()
}
