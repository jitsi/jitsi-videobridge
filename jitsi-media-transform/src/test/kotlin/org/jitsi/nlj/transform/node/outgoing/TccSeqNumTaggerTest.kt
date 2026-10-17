/*
 * Copyright @ 2026 - Present, 8x8 Inc
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

package org.jitsi.nlj.transform.node.outgoing

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * Several threads use the same [TccSeqNumTagger]: the sender queue (media), the thread that handles incoming RTCP
 * (retransmissions in response to NACKs), and the probing threads. Each packet must get a different sequence number.
 */
class TccSeqNumTaggerTest : ShouldSpec() {
    private val tccExtId = 5
    private val streamInformationStore = StreamInformationStoreImpl().apply {
        addRtpExtensionMapping(RtpExtension(tccExtId.toByte(), RtpExtensionType.TRANSPORT_CC))
    }

    private val tagged = ConcurrentLinkedQueue<Long>()
    private val transportCcEngine = object : TransportCcEngine() {
        override fun mediaPacketTagged(packetInfo: PacketInfo, tccSeqNum: Long) {
            tagged.add(tccSeqNum)
        }
        override fun onRttUpdate(rtt: Duration) {}
        override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Instant?) {}
        override fun mediaPacketSent(packetInfo: PacketInfo, tccSeqNum: Long) {}
        override fun getStatistics() = object : StatisticsSnapshot() {
            override fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode()
        }
        override fun addBandwidthListener(listener: BandwidthListener) {}
        override fun removeBandwidthListener(listener: BandwidthListener) {}
        override fun start() {}
        override fun stop() {}
    }

    private fun videoPacketInfo() = PacketInfo(
        VideoRtpPacket(ByteArray(1500), 0, 200).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = 100
            sequenceNumber = 123
            timestamp = 456L
            ssrc = 1234L
        }
    )

    init {
        should("assign a different sequence number to each packet when several threads use it") {
            val tagger = TccSeqNumTagger(transportCcEngine, streamInformationStore)
            val numThreads = 4
            val packetsPerThread = 50_000
            val barrier = CyclicBarrier(numThreads)
            (1..numThreads).map {
                thread {
                    barrier.await()
                    repeat(packetsPerThread) { tagger.processPacket(videoPacketInfo()) }
                }
            }.forEach { it.join() }

            tagged.size shouldBe numThreads * packetsPerThread
            tagged.toSet().size shouldBe numThreads * packetsPerThread
        }
    }
}
