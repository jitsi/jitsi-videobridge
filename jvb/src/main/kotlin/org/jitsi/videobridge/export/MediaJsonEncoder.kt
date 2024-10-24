/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import org.jitsi.mediajson.CustomParameters
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.MediaFormat
import org.jitsi.mediajson.Start
import org.jitsi.mediajson.StartEvent
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encodes the media in a conference into a mediajson format. Maintains state for each SSRC in order to maintain a
 * common space for timestamps.
 *
 * Note we're using a common clock with a rate of 48000 for all SSRCs (that's equivalent to the RTP timestamp for opus).
 */
class MediaJsonEncoder(
    /** Encoded mediajson events are sent to this function */
    val handleEvent: (Event) -> Unit
) {
    val logger = createLogger()
    val ref = Clock.systemUTC().instant()

    private data class SsrcState(
        val ssrc: Long,
        val initialRtpTs: Long,
        // Offset of this SSRC since the start time in RTP units
        val offset: Long
    )

    private val ssrcsStarted = mutableSetOf<SsrcState>()
    var seq = 0

    fun encode(p: AudioRtpPacket, epId: String) = synchronized(ssrcsStarted) {
        if (ssrcsStarted.none { it.ssrc == p.ssrc }) {
            // This is a new SSRC, save it and produce a StartEvent
            val state = SsrcState(
                p.ssrc,
                p.timestamp,
                (Duration.between(ref, Clock.systemUTC().instant()).toNanos() * 48.0e-6).toLong()
            )
            ssrcsStarted.add(state)
            val startEvent = StartEvent(
                ++seq,
                Start(
                    "$epId-${p.ssrc}",
                    MediaFormat(
                        "opus",
                        48000,
                        2
                    ),
                    CustomParameters(endpointId = epId)
                )
            )
            handleEvent(startEvent)
        }

        seq++
        handleEvent(p.encodeAsJson(epId))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun RtpPacket.encodeAsJson(epId: String): Event {
        val ssrcState = ssrcsStarted.find { it.ssrc == this.ssrc }!!
        val elapsedRtpTime = this.timestamp - ssrcState.initialRtpTs
        val ts = elapsedRtpTime + ssrcState.offset
        val indexTracker = Rfc3711IndexTracker()
        val p = MediaEvent(
            seq,
            media = Media(
                "$epId-${this.ssrc}",
                indexTracker.update(this.sequenceNumber),
                ts,
                Base64.encode(this.buffer, this.payloadOffset, this.payloadOffset + this.payloadLength)
            )
        )
        return p
    }
}
