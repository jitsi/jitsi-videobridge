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
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.PayloadTypeEncoding
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.nlj.util.RtpTimestampIndexTracker
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encodes the media in a conference into a mediajson format. Maintains state for each SSRC in order to maintain a
 * common space for timestamps.
 *
 */
class MediaJsonSerializer(
    /** Encoded mediajson events are sent to this function */
    private val handleEvent: (Event) -> Unit
) {
    /** Reference time, timestamps are set relative to this instant. **/
    private val ref: Instant = Clock.systemUTC().instant()
    private val ssrcsStarted = mutableMapOf<Long, SsrcState>()

    /** Global sequence number for all events */
    var seq = 0

    val logger = createLogger()
    fun encode(packetInfo: PacketInfo) = synchronized(ssrcsStarted) {
        val p: AudioRtpPacket = packetInfo.packetAs()
        val epId = packetInfo.endpointId ?: run {
            logger.info("Ignoring packet without endpoint ID, SSRC ${p.ssrc}")
            return@synchronized
        }
        val payloadType = packetInfo.payloadType ?: run {
            logger.info("Ignoring packet without payloadType, SSRC ${p.ssrc}")
            return@synchronized
        }

        val state = ssrcsStarted.computeIfAbsent(p.ssrc) { ssrc ->
            val now = Clock.systemUTC().instant()
            SsrcState(
                p.timestamp,
                (Duration.between(ref, now).toNanos() * payloadType.clockRate.toDouble() * 1e-9).toLong(),
                payloadType
            ).also {
                logger.info("Starting SSRC $ssrc for endpoint $epId ")
                handleEvent(createStart(epId, ssrc, payloadType))
            }
        }

        if (payloadType.pt != state.payloadType.pt) {
            logger.debug("SSRC ${p.ssrc} changed payload type from ${state.payloadType.pt} to ${payloadType.pt}")
            return@synchronized
        }

        handleEvent(encodeMedia(p, state, epId))
    }

    private fun createStart(epId: String, ssrc: Long, payloadType: PayloadType) = StartEvent(
        ++seq,
        Start(
            "$epId-$ssrc",
            MediaFormat(
                payloadType.encoding.name,
                payloadType.clockRate,
                if (payloadType.encoding == PayloadTypeEncoding.OPUS) 2 else 1,
                payloadType.parameters
            ),
            CustomParameters(endpointId = epId)
        )
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeMedia(p: AudioRtpPacket, state: SsrcState, epId: String): Event {
        ++seq
        return MediaEvent(
            seq,
            media = Media(
                "$epId-${p.ssrc}",
                state.getSequenceNumber(p.sequenceNumber),
                state.getTimestamp(p.timestamp),
                Base64.encode(p.buffer, p.payloadOffset, p.payloadOffset + p.payloadLength)
            )
        )
    }

    private class SsrcState(
        initialRtpTimestamp: Long,
        // Offset of this SSRC since the start time in RTP units
        startOffset: Long,
        val payloadType: PayloadType
    ) {
        private val seqIndexTracker = RtpSequenceIndexTracker()
        private val timestampIndexTracker = RtpTimestampIndexTracker().apply { update(initialRtpTimestamp) }
        private val offset = startOffset - initialRtpTimestamp
        fun getTimestamp(rtpTimestamp: Long): Long = offset + timestampIndexTracker.update(rtpTimestamp)
        fun getSequenceNumber(seq: Int) = seqIndexTracker.update(seq).toInt()
    }
}
