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
package org.jitsi.nlj

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtcp.RetransmissionRequester
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.transform.node.AudioRedHandler
import org.jitsi.nlj.transform.node.incoming.DiscardableDiscarder
import org.jitsi.nlj.transform.node.incoming.DuplicateTermination
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.RtcpSrPacketBuilder
import org.jitsi.rtp.rtcp.SenderInfoBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import java.time.Instant

/**
 * Verifies that the per-SSRC tracking structures are bounded, i.e. that the number of distinct SSRCs tracked for a
 * single stream is limited regardless of how many distinct SSRCs are received.
 */
class PerSsrcMapCapTest : ShouldSpec() {
    init {
        val n = 1000

        context("IncomingStatisticsTracker") {
            val store = StreamInformationStoreImpl().apply {
                addRtpPayloadType(Vp8PayloadType(100, emptyMap(), emptySet()))
            }
            val tracker = IncomingStatisticsTracker(store)
            repeat(n) { i ->
                tracker.processPacket(PacketInfo(rtpPacket(i.toLong())).apply { receivedTime = Instant.EPOCH })
            }
            should("bound the number of tracked SSRCs") {
                tracker.getSnapshot().ssrcStats.size shouldBe IncomingStatisticsTracker.MAX_SSRCS
            }
        }

        context("DuplicateTermination") {
            val node = DuplicateTermination()
            repeat(n) { i -> node.processPacket(PacketInfo(rtpPacket(i.toLong()))) }
            should("bound the number of tracked replay contexts") {
                node.mapField("replayContexts").size shouldBe DuplicateTermination.MAX_SSRCS
            }
        }

        context("DiscardableDiscarder") {
            val node = DiscardableDiscarder("test", keepHistory = false)
            repeat(n) { i -> node.processPacket(PacketInfo(rtpPacket(i.toLong()))) }
            should("bound the number of tracked rewriters") {
                node.rewriters.size shouldBe DiscardableDiscarder.MAX_SSRCS
            }
        }

        context("AudioRedHandler") {
            val handler = AudioRedHandler(StreamInformationStoreImpl(), StdoutLogger())
            repeat(n) { i -> handler.processPacket(PacketInfo(audioPacket(i.toLong()))) }
            should("bound the number of tracked RED handlers") {
                handler.mapField("ssrcRedHandlers").size shouldBe AudioRedHandler.MAX_SSRCS
            }
        }

        context("RetransmissionRequester") {
            val scheduler = FakeScheduledExecutorService()
            val requester = RetransmissionRequester({}, scheduler, StdoutLogger(), scheduler.clock)
            repeat(n) { i -> requester.packetReceived(i.toLong(), 1) }
            should("bound the number of tracked stream requesters") {
                requester.mapField("streamPacketRequesters").size shouldBe RetransmissionRequester.MAX_SSRCS
            }
        }

        context("RtcpRrGenerator") {
            val scheduler = FakeScheduledExecutorService()
            val store = StreamInformationStoreImpl()
            val generator = RtcpRrGenerator(
                scheduler,
                {},
                IncomingStatisticsTracker(store),
                scheduler.clock
            ) { emptyList() }
            repeat(n) { i ->
                val sr = RtcpSrPacketBuilder(
                    RtcpHeaderBuilder(senderSsrc = i.toLong()),
                    SenderInfoBuilder(rtpTimestamp = 12345L)
                ).build()
                generator.rtcpPacketReceived(sr, Instant.EPOCH)
            }
            should("bound the number of tracked sender infos") {
                generator.mapField("senderInfos").size shouldBe RtcpRrGenerator.MAX_SSRCS
            }
        }
    }

    private fun rtpPacket(ssrcVal: Long, seq: Int = 1, pt: Int = 100): RtpPacket =
        RtpPacket(ByteArray(50), 0, 50).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = pt
            sequenceNumber = seq
            timestamp = 456L
            ssrc = ssrcVal
        }

    private fun audioPacket(ssrcVal: Long, seq: Int = 1, pt: Int = 111): AudioRtpPacket =
        AudioRtpPacket(ByteArray(50), 0, 50).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = pt
            sequenceNumber = seq
            timestamp = 456L
            ssrc = ssrcVal
        }

    /** Reads a private (possibly inherited) [Map] field by reflection, for asserting its size. */
    private fun Any.mapField(name: String): Map<*, *> {
        var cls: Class<*>? = this::class.java
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(this) as Map<*, *>
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
