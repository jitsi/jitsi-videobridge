/*
 * Copyright @ 2020 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.xml.bind.DatatypeConverter.parseHexBinary
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.message.AudioSourcesMap
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import java.util.Random
import java.util.function.Supplier

class SsrcCacheTest : ShouldSpec() {
    private val limit = 50
    private val ep = Ep()
    private val logger: Logger = LoggerImpl(javaClass.name)
    private val audioSsrcCache = AudioSsrcCache(limit, ep, false, logger)

    private val seed = System.currentTimeMillis()
    private val random = Random(seed)
    private val streams = ArrayList<PacketGenerator>()
    private val checker = PacketChecker()

    init {
        for (i in 0 until 4 * limit) {
            val ssrc = random.nextLong().and(0xFFFF_FFFFL)
            streams.add(PacketGenerator(ssrc, random))
        }

        for (i in 0 until 100000) {
            /* Generate packets for the highest ranked streams */
            for (j in 0 until 10) {
                val packet = streams[j].nextPacket()
                audioSsrcCache.rewriteRtp(packet)
                checker.checkPacket(packet)
            }

            /* Randomly select a stream and move to head of the list */
            val pos = random.nextInt(streams.size)
            val nextSpeaker = streams.removeAt(pos)
            streams.add(0, nextSpeaker)
        }

        checker.size() shouldBeLessThanOrEqual limit

        should("assign a stable mid per slot when mid demux is enabled") {
            val midEp = Ep()
            val cache = AudioSsrcCache(2, midEp, midDemux = true, logger)

            cache.rewriteRtp(PacketGenerator(1001L, random).nextPacket())
            cache.rewriteRtp(PacketGenerator(1002L, random).nextPacket())
            // The pool (size 2) is now full; a third source remaps the least-recently-used slot.
            cache.rewriteRtp(PacketGenerator(1003L, random).nextPacket())

            val msgs = midEp.sentMessages.filterIsInstance<AudioSourcesMap>()
            msgs.size shouldBe 3
            val first = msgs[0].mappedSources.single()
            val second = msgs[1].mappedSources.single()
            val third = msgs[2].mappedSources.single()

            first.mid shouldBe "a0"
            second.mid shouldBe "a1"
            // The remap reuses the evicted slot, so it keeps that slot's mid and send SSRC.
            third.mid shouldBe "a0"
            third.ssrc shouldBe first.ssrc

            cache.getMidBySsrc(first.ssrc) shouldBe "a0"
            cache.getMidBySsrc(second.ssrc) shouldBe "a1"
        }

        should("not assign mids when mid demux is disabled") {
            val plainEp = Ep()
            val cache = AudioSsrcCache(2, plainEp, midDemux = false, logger)

            cache.rewriteRtp(PacketGenerator(2001L, random).nextPacket())

            val mapping = plainEp.sentMessages.filterIsInstance<AudioSourcesMap>()
                .flatMap { it.mappedSources }.single()
            mapping.mid shouldBe null
            cache.getMidBySsrc(mapping.ssrc) shouldBe null
        }

        context("An AV1 DD packet") {
            fun newVideoCache(errorLogger: Logger): VideoSsrcCache {
                val videoEp = VideoEp(AV1_SSRC)
                return VideoSsrcCache(limit, videoEp, false, errorLogger).apply { activate(listOf(videoEp.source)) }
            }

            should("be rewritten when it has a descriptor") {
                val errorLogger = ErrorRecordingLogger("SsrcCacheTest")
                val cache = newVideoCache(errorLogger)
                val structure = av1Packet(AV1_KEYFRAME, null).descriptor!!.structure

                cache.rewriteRtp(av1Packet(AV1_KEYFRAME, null)) shouldBe true
                val following = av1Packet(AV1_FOLLOWING, structure)
                following.descriptor shouldNotBe null
                cache.rewriteRtp(following) shouldBe true
                errorLogger.errors shouldBe emptyList()
            }
            should("not be sent, and not cause an error, when it has no descriptor") {
                /* The frame number and template ID of the packet cannot be rewritten. */
                val errorLogger = ErrorRecordingLogger("SsrcCacheTest")
                val cache = newVideoCache(errorLogger)

                cache.rewriteRtp(av1Packet(AV1_KEYFRAME, null)) shouldBe true
                val following = av1Packet(AV1_FOLLOWING, null)
                following.descriptor shouldBe null
                cache.rewriteRtp(following) shouldBe false
                errorLogger.errors shouldBe emptyList()
            }
        }
    }

    private fun av1Packet(hex: String, structure: Av1TemplateDependencyStructure?): Av1DDPacket {
        val data = parseHexBinary(hex)
        return Av1DDPacket(RtpPacket(data, 0, data.size), AV1_DD_HEADER_EXTENSION_ID, structure, logger)
    }

    companion object {
        private const val AV1_DD_HEADER_EXTENSION_ID = 11
        private const val AV1_SSRC = 0x114ed316L

        /* Packets captured from an AV1 test call (see Av1DDPacketTest). */

        /** The first packet of a non-scalable keyframe. Its DD carries a template dependency structure. */
        private const val AV1_KEYFRAME =
            // RTP header
            "902939b91ff6a695114ed316" +
                // Header extension header
                "bede0006" +
                // Other header extensions
                "3248c482" +
                "510002" +
                // AV1 DD
                "bc80000180003a410180ef808680" +
                // Padding
                "000000" +
                // AV1 Media payload, truncated.
                "680b0800000004477e1a00d004301061"

        /** A following packet of the same stream. Its DD needs the structure of the keyframe to be parsed. */
        private const val AV1_FOLLOWING =
            // RTP header
            "90a939ba1ff6a695114ed316" +
                // Header extension header
                "bede0003" +
                // Other header extensions
                "3248d02a" +
                "510003" +
                // AV1 DD
                "b2400001" +
                // Padding
                "00" +
                // AV1 Media payload, truncated.
                "9057f7c3f51ba803b0c397e938589750"
    }
}

/** A logger that records errors, also for the child loggers created from it. */
private class ErrorRecordingLogger(
    name: String,
    val errors: MutableList<String> = mutableListOf()
) : LoggerImpl(name) {
    override fun error(msg: Any?) {
        errors.add("$msg")
        super.error(msg)
    }

    override fun error(msg: Supplier<String>) {
        errors.add(msg.get())
        super.error(msg)
    }

    override fun error(msg: Any?, t: Throwable?) {
        errors.add("$msg: $t")
        super.error(msg, t)
    }

    override fun createChildLogger(name: String): Logger = ErrorRecordingLogger(name, errors)

    override fun createChildLogger(name: String, context: Map<String, String>): Logger =
        ErrorRecordingLogger(name, errors)
}

/** An endpoint with one video source. */
private class VideoEp(ssrc: Long) : SsrcRewriter {
    val source = MediaSourceDesc(arrayOf(RtpEncodingDesc(ssrc, 0)), "owner", "name")

    private var nextSendSsrc = 1L

    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? = if (ssrc == source.primarySSRC) source else null

    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? = null

    override fun getNextSendSsrc(): Long = nextSendSsrc++

    override fun sendMessage(msg: BridgeChannelMessage) {}
}

private class Ep : SsrcRewriter {

    private var nextSendSsrc = 1L

    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? = null

    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? =
        AudioSourceDesc(ssrc, "anon-$ssrc", "anon-$ssrc-a0")

    val sentMessages = mutableListOf<BridgeChannelMessage>()

    override fun getNextSendSsrc(): Long {
        synchronized(this) {
            return nextSendSsrc++
        }
    }

    override fun sendMessage(msg: BridgeChannelMessage) {
        sentMessages.add(msg)
    }
}

private class PacketGenerator(val ssrc: Long, random: Random) {
    private var seq = random.nextInt(0x1_0000)
    private var ts = random.nextLong().and(0xFFFF_FFFFL)
    private var frameCount = 0

    fun nextPacket(): AudioRtpPacket {
        val buffer = ByteArray(28)

        val audioPacket = AudioRtpPacket(buffer, 0, buffer.size)
        audioPacket.ssrc = ssrc
        audioPacket.sequenceNumber = seq
        audioPacket.timestamp = ts
        audioPacket.isMarked = true

        seq = RtpUtils.applySequenceNumberDelta(seq, 1)
        ts = RtpUtils.applyTimestampDelta(ts, 960)
        ++frameCount

        return audioPacket
    }
}

private class PacketChecker() {

    private val ssrcs = HashMap<Long, RtpPacket>()

    fun checkPacket(packet: RtpPacket) {
        val last = ssrcs.get(packet.ssrc)
        if (last != null) {
            packet.sequenceNumber shouldBe RtpUtils.applySequenceNumberDelta(last.sequenceNumber, 1)
            packet.timestamp shouldBe RtpUtils.applyTimestampDelta(last.timestamp, 960L)
        }
        ssrcs.put(packet.ssrc, packet)
    }

    fun size(): Int = ssrcs.size
}
