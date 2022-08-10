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
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import java.util.Random

class SsrcCacheTest : ShouldSpec() {
    private val limit = 50
    private val ep = Ep()
    private val logger: Logger = LoggerImpl(javaClass.name)
    private val audioSsrcCache = AudioSsrcCache(limit, ep, logger)

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
    }
}

private class Ep : SsrcRewriter {

    private var nextSendSsrc = 1L

    override fun findVideoSourceProps(ssrc: Long): MediaSourceDesc? {
        return null
    }

    override fun findAudioSourceProps(ssrc: Long): AudioSourceDesc? {
        return AudioSourceDesc(ssrc, "anon-$ssrc", "anon-$ssrc-a0")
    }

    override fun getNextSendSsrc(): Long {
        synchronized(this) {
            return nextSendSsrc++
        }
    }

    override fun sendMessage(msg: BridgeChannelMessage) {
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
