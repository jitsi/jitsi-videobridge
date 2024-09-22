package org.jitsi.videobridge.exporter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.MediaFormat
import org.jitsi.mediajson.Start
import org.jitsi.mediajson.StartEvent
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class MediaJsonEncoder(
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
    val om = jacksonObjectMapper()

    fun encode(p: AudioRtpPacket, epId: String) = synchronized(ssrcsStarted) {
        if (ssrcsStarted.none { it.ssrc == p.ssrc } ) {
            val offset: Long = ((Duration.between(ref, Clock.systemUTC().instant())).toNanos() * 48.0e-6).toLong()
            val state = SsrcState(p.ssrc, p.timestamp, offset)
            ssrcsStarted.add(state)
            val e = StartEvent(
                (++seq).toString(),
                Start(
                    "$epId-${p.ssrc}",
                    MediaFormat(
                        "opus",
                        48000,
                        2
                    )
                )
            )
            handleEvent(e)
        }

        seq++
        handleEvent(p.encodeAsJson(epId))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun RtpPacket.encodeAsJson(epId: String): Event {
        val ssrcState = ssrcsStarted.find { it.ssrc == this.ssrc }!!
        val elapsedRtpTime = this.timestamp - ssrcState.initialRtpTs
        val ts = elapsedRtpTime + ssrcState.offset
        val p = MediaEvent(
            seq.toString(),
            media = Media(
                "$epId-${this.ssrc}",
                this.sequenceNumber.toString(),
                ts.toString(),
                Base64.encode(this.buffer, this.payloadOffset, this.payloadOffset + this.payloadLength)
            )
        )
        return p
    }
}
