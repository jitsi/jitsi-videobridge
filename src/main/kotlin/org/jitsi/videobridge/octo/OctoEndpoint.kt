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

import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.rest.root.colibri.debug.EndpointDebugFeatures
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import java.util.function.Consumer

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
) : AbstractEndpoint(conference, id, parentLogger) {

    /**
     * Information about the streams belonging to this [OctoEndpoint]
     */
    private val streamInformationStore: StreamInformationStore = StreamInformationStoreImpl()

    override fun sendMessage(msg: String?) {
        // This is intentionally a no-op. Since a conference can have
        // multiple OctoEndpoint instances, but we want a single message
        // to be sent through Octo, the message should be sent through the
        // single OctoEndpoints instance.
    }

    override fun requestKeyframe(mediaSsrc: Long) {
        // just making sure the tentacle hasn't expired
        conference.tentacle?.requestKeyframe(mediaSsrc)
    }

    override fun requestKeyframe() {
        streamInformationStore.primaryVideoSsrcs.stream().findFirst().ifPresent(Consumer { mediaSsrc: Long? -> this.requestKeyframe(mediaSsrc!!) })
    }

    override fun setFeature(feature: EndpointDebugFeatures?, enabled: Boolean) {
        // NO-OP
    }

    override fun shouldExpire(): Boolean =
        streamInformationStore.receiveSsrcs.isEmpty()

    override fun getMediaStreamTracks(): Array<MediaStreamTrackDesc> {
        return conference.tentacle.mediaStreamTracks.filter { it.owner == id }.toTypedArray()
    }

    override fun receivesSsrc(ssrc: Long): Boolean =
        streamInformationStore.receiveSsrcs.contains(ssrc)

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType?) {
        // This is controlled through setReceiveSsrcs.
    }

    override fun expire() {
        if (super.isExpired()) {
            return
        }
        octoEndpoints.endpointExpired(this)
    }

    /**
     * Sets the set SSRCs we expect to receive from this endpoint.
     */
    fun setReceiveSsrcs(ssrcsByMediaType: Map<MediaType, Set<Long>>) {
        streamInformationStore.receiveSsrcs.forEach(streamInformationStore::removeReceiveSsrc)
        ssrcsByMediaType.forEach { (mediaType, ssrcs) ->
            ssrcs.forEach { streamInformationStore.addReceiveSsrc(it, mediaType) }
        }
    }

    override fun isSendingAudio(): Boolean {
        // TODO implement detection
        return true
    }

    override fun isSendingVideo(): Boolean {
        // TODO implement detection
        return true
    }
}
