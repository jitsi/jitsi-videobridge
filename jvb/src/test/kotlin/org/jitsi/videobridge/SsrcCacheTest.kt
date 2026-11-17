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
import io.kotest.matchers.collections.shouldBeEmpty
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
import org.jitsi.videobridge.message.VideoSourcesMap
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.SyntheticSourceKind
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

        should("carry the kind of a synthetic source in its mapping, and none for a regular source") {
            val kindEp = Ep(mapOf(3001L to SyntheticSourceKind.AGENT, 3002L to SyntheticSourceKind.TRANSLATION))
            val cache = AudioSsrcCache(3, kindEp, midDemux = false, logger)

            listOf(3001L, 3002L, 3003L).forEach { cache.rewriteRtp(PacketGenerator(it, random).nextPacket()) }

            val mappings = kindEp.sentMessages.filterIsInstance<AudioSourcesMap>().flatMap { it.mappedSources }
            mappings.map { it.source to it.kind } shouldBe listOf(
                "anon-3001-a0" to SyntheticSourceKind.AGENT,
                "anon-3002-a0" to SyntheticSourceKind.TRANSLATION,
                "anon-3003-a0" to null
            )
        }

        context("The RTX send SSRC of a slot") {
            should("be given to the endpoint when a video slot is created") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)

                cache.activate(listOf(videoEp.source))

                val mapping = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>()
                    .flatMap { it.mappedSources }.single()
                videoEp.sendRtxSsrcs shouldBe listOf(mapping.ssrc to mapping.rtx)
            }
            should("not be given again when the slot is remapped, or mapped to its first source again") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(1, videoEp, midDemux = false, logger)
                val otherSource = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "owner", "other")

                cache.activate(listOf(videoEp.source))
                // The pool (size 1) is full, so the second source takes over the slot and its send SSRCs. Then the
                // first source takes it back.
                videoEp.otherSources[4002L] = otherSource
                cache.activate(listOf(otherSource))
                cache.activate(listOf(videoEp.source))

                val mappings = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>().flatMap { it.mappedSources }
                mappings.map { it.source } shouldBe listOf("name", "other", "name")
                mappings.map { it.ssrc to it.rtx }.toSet().size shouldBe 1
                videoEp.sendRtxSsrcs shouldBe listOf(mappings[0].ssrc to mappings[0].rtx)
            }
            should("be given for each slot when one call maps more sources than there are slots") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)
                val second = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "owner", "second")
                val third = MediaSourceDesc(arrayOf(RtpEncodingDesc(4003L, 0)), "owner", "third")
                videoEp.otherSources[4002L] = second
                videoEp.otherSources[4003L] = third

                cache.activate(listOf(videoEp.source, second, third))

                val mappings = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>().flatMap { it.mappedSources }
                mappings.map { it.source } shouldBe listOf("name", "second", "third")
                // The third source takes over the slot of the first.
                mappings[2].ssrc shouldBe mappings[0].ssrc
                videoEp.sendRtxSsrcs shouldBe mappings.take(2).map { it.ssrc to it.rtx }
            }
            should("be given for the new slot of a source mapped after the sources of an endpoint are removed") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)
                val kept = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "other-owner", "kept")
                val later = MediaSourceDesc(arrayOf(RtpEncodingDesc(4003L, 0)), "owner", "later")
                videoEp.otherSources[4002L] = kept
                videoEp.otherSources[4003L] = later

                cache.activate(listOf(videoEp.source, kept))
                cache.removeByOwner("owner")
                // The slot of the removed source is gone, so this source gets a new one, with new send SSRCs.
                cache.activate(listOf(later))

                val mappings = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>().flatMap { it.mappedSources }
                mappings.map { it.source } shouldBe listOf("name", "kept", "later")
                mappings.map { it.ssrc }.toSet().size shouldBe 3
                videoEp.sendRtxSsrcs shouldBe mappings.map { it.ssrc to it.rtx }
            }
            should("be taken back from the endpoint when the slot is removed with the sources of its owner") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)
                val kept = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "other-owner", "kept")
                videoEp.otherSources[4002L] = kept

                cache.activate(listOf(videoEp.source, kept))
                videoEp.removedSendRtxSsrcs.shouldBeEmpty()
                cache.removeByOwner("owner")

                val mappings = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>().flatMap { it.mappedSources }
                // Only the slot of the removed owner, not the slot of the other one.
                videoEp.removedSendRtxSsrcs shouldBe listOf(mappings[0].ssrc to mappings[0].rtx)

                // Removing again reports nothing more.
                cache.removeByOwner("owner")
                videoEp.removedSendRtxSsrcs.size shouldBe 1
            }
            should("not be given for audio, which is not retransmitted") {
                val audioEp = Ep()
                val cache = AudioSsrcCache(2, audioEp, midDemux = false, logger)

                cache.rewriteRtp(PacketGenerator(5001L, random).nextPacket())

                audioEp.sentMessages.filterIsInstance<AudioSourcesMap>().size shouldBe 1
                audioEp.numSendRtxSsrcs shouldBe 0
            }
        }
        context("The send SSRCs of sources") {
            should("be those of their slots, and none for a source without a slot") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)

                cache.getSendSsrcs(listOf(4001L)) shouldBe emptyList()
                cache.activate(listOf(videoEp.source))

                val mapping = videoEp.sentMessages.filterIsInstance<VideoSourcesMap>()
                    .flatMap { it.mappedSources }.single()
                cache.getSendSsrcs(listOf(4001L, 4002L)) shouldBe listOf(mapping.ssrc)
            }
            should("be looked up without counting as a use of the slot") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)
                val second = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "owner", "second")
                val third = MediaSourceDesc(arrayOf(RtpEncodingDesc(4003L, 0)), "owner", "third")
                videoEp.otherSources[4002L] = second
                videoEp.otherSources[4003L] = third

                cache.activate(listOf(videoEp.source))
                cache.activate(listOf(second))
                val firstSendSsrc = cache.getSendSsrcs(listOf(4001L)).single()
                // The pool (size 2) is full. The first source is still the least recently used, so its slot is the
                // one remapped.
                cache.activate(listOf(third))

                cache.getSendSsrcs(listOf(4003L)) shouldBe listOf(firstSendSsrc)
                cache.getSendSsrcs(listOf(4001L)) shouldBe emptyList()
            }
            should("be forgotten when the sources of their owner are removed, and kept for other owners") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(2, videoEp, midDemux = false, logger)
                val kept = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "other-owner", "kept")
                videoEp.otherSources[4002L] = kept

                cache.activate(listOf(videoEp.source, kept))
                val keptSendSsrcs = cache.getSendSsrcs(listOf(4002L))
                cache.getSendSsrcs(listOf(4001L, 4002L)).size shouldBe 2
                cache.removeByOwner("owner")

                cache.getSendSsrcs(listOf(4001L)) shouldBe emptyList()
                cache.getSendSsrcs(listOf(4002L)) shouldBe keptSendSsrcs
            }
            should("be those of the slot again for a source which gets its slot back") {
                val videoEp = VideoEp(4001L)
                val cache = VideoSsrcCache(1, videoEp, midDemux = false, logger)
                val other = MediaSourceDesc(arrayOf(RtpEncodingDesc(4002L, 0)), "owner", "other")
                videoEp.otherSources[4002L] = other

                cache.activate(listOf(videoEp.source))
                val sendSsrcs = cache.getSendSsrcs(listOf(4001L))
                cache.activate(listOf(other))
                cache.getSendSsrcs(listOf(4001L, 4002L)) shouldBe sendSsrcs
                cache.activate(listOf(videoEp.source))

                cache.getSendSsrcs(listOf(4001L, 4002L)) shouldBe sendSsrcs
                cache.getSendSsrcs(listOf(4002L)) shouldBe emptyList()
            }
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

/** An endpoint with a video source, and further ones in [otherSources]. */
private class VideoEp(ssrc: Long) : SsrcRewriter {
    val source = MediaSourceDesc(arrayOf(RtpEncodingDesc(ssrc, 0)), "owner", "name")

    private var nextSendSsrc = 1L

    /** Further video sources, by primary SSRC. */
    val otherSources = mutableMapOf<Long, MediaSourceDesc>()

    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? =
        if (ssrc == source.primarySSRC) source else otherSources[ssrc]

    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? = null

    override fun getNextSendSsrc(): Long = nextSendSsrc++

    /** Each report of an RTX send SSRC by the cache: the primary send SSRC and the RTX send SSRC. */
    val sendRtxSsrcs = mutableListOf<Pair<Long, Long>>()

    override fun addSendRtxSsrc(primarySsrc: Long, rtxSsrc: Long) {
        sendRtxSsrcs.add(primarySsrc to rtxSsrc)
    }

    /** Each report by the cache that a slot is gone: its primary send SSRC and its RTX send SSRC. */
    val removedSendRtxSsrcs = mutableListOf<Pair<Long, Long>>()

    override fun removeSendRtxSsrc(primarySsrc: Long, rtxSsrc: Long) {
        removedSendRtxSsrcs.add(primarySsrc to rtxSsrc)
    }

    val sentMessages = mutableListOf<BridgeChannelMessage>()

    override fun sendMessage(msg: BridgeChannelMessage) {
        sentMessages.add(msg)
    }
}

private class Ep(
    /** The kinds of the synthetic sources, by SSRC; any other SSRC is a regular source. */
    private val syntheticKinds: Map<Long, SyntheticSourceKind> = emptyMap()
) : SsrcRewriter {

    private var nextSendSsrc = 1L

    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? = null

    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? {
        val kind = syntheticKinds[ssrc]
        return AudioSourceDesc(ssrc, "anon-$ssrc", "anon-$ssrc-a0", synthetic = kind != null, kind = kind)
    }

    val sentMessages = mutableListOf<BridgeChannelMessage>()

    override fun getNextSendSsrc(): Long {
        synchronized(this) {
            return nextSendSsrc++
        }
    }

    var numSendRtxSsrcs = 0

    override fun addSendRtxSsrc(primarySsrc: Long, rtxSsrc: Long) {
        numSendRtxSsrcs++
    }

    override fun removeSendRtxSsrc(primarySsrc: Long, rtxSsrc: Long) {
        numSendRtxSsrcs--
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
