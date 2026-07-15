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
import org.jitsi.nlj.format.AudioPayloadType
import org.jitsi.nlj.format.PayloadType
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
    /** Resolves an audio SSRC to its source name, which is used as the media-json tag. */
    private val getSourceName: (Long) -> String?,
    /** Resolves an audio SSRC to whether diarization is requested for its endpoint (colibri2 `diarize` attribute). */
    private val getDiarize: (Long) -> Boolean,
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

        var state = ssrcsStarted[p.ssrc]
        if (state == null) {
            // Tag the stream with its source name. If the SSRC doesn't resolve to a signaled source (media racing
            // colibri signaling), drop the packet: such audio isn't routed to receivers either, and starting the
            // stream with a placeholder tag would strand its media events once the name resolves. The tag is kept
            // in the state, so it stays consistent between the stream's start event and its media events.
            val tag = getSourceName(p.ssrc) ?: run {
                logger.info("Ignoring packet for SSRC ${p.ssrc} with no known source")
                return@synchronized
            }
            state = createSsrcState(p.timestamp, payloadType, tag)
            ssrcsStarted[p.ssrc] = state
            logger.info("Starting SSRC ${p.ssrc} for endpoint $epId ")
            handleEvent(createStart(tag, epId, payloadType, p.ssrc))
        }

        if (payloadType.pt != state.payloadType.pt) {
            logger.info("SSRC ${p.ssrc} changed payload type from ${state.payloadType} to $payloadType.")
            // An SSRC's source name doesn't change, so the new state (announced by a new start event) keeps the tag.
            state = createSsrcState(p.timestamp, payloadType, state.tag)
            ssrcsStarted[p.ssrc] = state
            handleEvent(createStart(state.tag, epId, payloadType, p.ssrc))
        }

        handleEvent(encodeMedia(p, state, packetInfo.audioLevel, packetInfo.vad))
    }

    private fun createSsrcState(initialRtpTimestamp: Long, payloadType: PayloadType, tag: String): SsrcState {
        val now = Clock.systemUTC().instant()
        return SsrcState(
            initialRtpTimestamp,
            (Duration.between(ref, now).toNanos() * payloadType.clockRate.toDouble() * 1e-9).toLong(),
            payloadType,
            tag
        )
    }

    private fun createStart(tag: String, epId: String, payloadType: PayloadType, ssrc: Long) = StartEvent(
        ++seq,
        Start(
            tag,
            MediaFormat(
                payloadType.encodingName(),
                payloadType.clockRate,
                (payloadType as? AudioPayloadType)?.channels ?: 1,
                payloadType.parameters
            ),
            CustomParameters(endpointId = epId),
            // Only set the flag when diarization is on; when off, leave it null so the field is omitted from the wire
            // format (jackson drops nulls) and the transcriber proxy falls back to its global config.
            diarize = if (getDiarize(ssrc)) true else null
        )
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeMedia(p: AudioRtpPacket, state: SsrcState, audioLevel: Int?, vad: Boolean?): Event {
        ++seq
        return MediaEvent(
            seq,
            media = Media(
                state.tag,
                state.getSequenceNumber(p.sequenceNumber),
                state.getTimestamp(p.timestamp),
                Base64.encode(p.buffer, p.payloadOffset, p.payloadOffset + p.payloadLength),
                audioLevel,
                vad
            )
        )
    }

    private class SsrcState(
        initialRtpTimestamp: Long,
        // Offset of this SSRC since the start time in RTP units
        startOffset: Long,
        val payloadType: PayloadType,
        /** The stream's mediajson tag, as announced in its start event. */
        val tag: String
    ) {
        private val seqIndexTracker = RtpSequenceIndexTracker()
        private val timestampIndexTracker = RtpTimestampIndexTracker().apply { update(initialRtpTimestamp) }
        private val offset = startOffset - initialRtpTimestamp
        fun getTimestamp(rtpTimestamp: Long): Long = offset + timestampIndexTracker.update(rtpTimestamp)
        fun getSequenceNumber(seq: Int) = seqIndexTracker.update(seq).toInt()
    }
}
