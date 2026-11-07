/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.rtp.rtp.RtpPacket

class AudioSequenceNumberRewriterTest : ShouldSpec() {
    init {
        context("Without dropped packets") {
            should("not change the sequence numbers, including gaps, reordering and wrap around") {
                val rewriter = AudioSequenceNumberRewriter()
                listOf(65530, 65531, 65535, 65533, 0, 3).forEach {
                    rewriter.send(SSRC, it) shouldBe it
                }
            }
        }
        context("After dropped packets") {
            should("continue from the highest sequence number sent") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(SSRC, 101) shouldBe 101
                rewriter.drop(102 until 40102)
                rewriter.send(SSRC, 40102) shouldBe 102
                rewriter.send(SSRC, 40103) shouldBe 103
            }
            should("keep a later gap from packet loss") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 50000) shouldBe 101
                rewriter.send(SSRC, 50003) shouldBe 104
            }
            should("handle wrap around") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 65535) shouldBe 65535
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 30000) shouldBe 0
                rewriter.send(SSRC, 30001) shouldBe 1
            }
            should("close each gap") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 20000) shouldBe 101
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 60000) shouldBe 102
            }
            should("not affect other SSRCs") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(OTHER_SSRC, 500) shouldBe 500
                rewriter.dropped(SSRC)
                rewriter.send(OTHER_SSRC, 501) shouldBe 501
                rewriter.send(SSRC, 40000) shouldBe 101
            }
            should("continue from the highest sequence number when packets were reordered") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(SSRC, 102) shouldBe 102
                rewriter.send(SSRC, 101) shouldBe 101
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 40000) shouldBe 103
            }
            should("close the gap when the resume packet is lost after it was accepted") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.drop(101 until 40001)
                rewriter.accepted(SSRC, 40001)
                // 40001 is lost in the send queue.
                rewriter.send(SSRC, 40002) shouldBe 101
            }
            should("count the gaps closed") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100)
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 20000)
                rewriter.debugState()["gaps_closed"].asLong() shouldBe 1
            }
        }
        context("A packet that waits in the send queue") {
            should("not close the gap when it was accepted before the drop") {
                // A packet waits in the queue for a short time only, so this drop is short.
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.accepted(SSRC, 101)
                rewriter.drop(102 until 110)
                rewriter.accepted(SSRC, 110)
                rewriter.rewrite(SSRC, 101) shouldBe 101
                rewriter.rewrite(SSRC, 110) shouldBe 102
            }
            should("close the gap when the source is dropped again before it is sent") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.drop(101 until 40001)
                rewriter.accepted(SSRC, 40001)
                rewriter.dropped(SSRC)
                rewriter.accepted(SSRC, 40003)
                rewriter.rewrite(SSRC, 40001) shouldBe 101
                rewriter.rewrite(SSRC, 40003) shouldBe 102
            }
            should("close the gap when it is the first packet and the drop starts before it is sent") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.accepted(SSRC, 100)
                rewriter.drop(101 until 40001)
                rewriter.rewrite(SSRC, 100) shouldBe 100
                rewriter.send(SSRC, 40001) shouldBe 101
            }
        }
        context("A late packet after a gap is closed") {
            should("be dropped if its sequence number was already used") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 103) shouldBe 101
                rewriter.send(SSRC, 102).shouldBeNull()
                rewriter.send(SSRC, 104) shouldBe 102
                rewriter.debugState()["late_packets_dropped"].asLong() shouldBe 1
            }
            should("be forwarded if it is far from the resume point") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 10000) shouldBe 10000
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 20000) shouldBe 10001
                // For example, a sender that started again with a new sequence number.
                rewriter.send(SSRC, 5000) shouldBe ((5000 - 9999) and 0xffff)
            }
            should("be forwarded after too many late packets in a row") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 10000) shouldBe 10000
                rewriter.dropped(SSRC)
                rewriter.send(SSRC, 20000) shouldBe 10001
                // A sender that started again with a new sequence number just before the resume point.
                (19900 until 19910).forEach { rewriter.send(SSRC, it).shouldBeNull() }
                rewriter.send(SSRC, 19910) shouldBe ((19910 - 9999) and 0xffff)
                rewriter.send(SSRC, 19911) shouldBe ((19911 - 9999) and 0xffff)
            }
        }
        context("An SSRC") {
            should("not change the first packet after packets dropped before it") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.drop(1 until 40000)
                rewriter.send(SSRC, 40000) shouldBe 40000
            }
            should("be removed when there are too many") {
                val rewriter = AudioSequenceNumberRewriter()
                (0L..AudioSequenceNumberRewriter.MAX_SSRCS).forEach { rewriter.send(1000 + it, 1) }
                rewriter.debugState()["ssrcs"].asInt() shouldBe AudioSequenceNumberRewriter.MAX_SSRCS
            }
        }
    }

    private fun AudioSequenceNumberRewriter.drop(sequenceNumbers: IntRange) = sequenceNumbers.forEach { _ ->
        dropped(SSRC)
    }

    /** Accepts a packet and rewrites it at once (with no wait in the send queue). */
    private fun AudioSequenceNumberRewriter.send(ssrc: Long, seq: Int): Int? {
        accepted(ssrc, seq)
        return rewrite(ssrc, seq)
    }

    /**
     * Rewrites a packet from [ssrc] with sequence number [seq], and returns the sequence number after rewriting, or
     * null if the packet must be dropped.
     */
    private fun AudioSequenceNumberRewriter.rewrite(ssrc: Long, seq: Int): Int? {
        val buffer = ByteArray(28)
        val packet = RtpPacket(buffer, 0, buffer.size).apply {
            version = 2
            this.ssrc = ssrc
            sequenceNumber = seq
        }
        return if (rewrite(packet)) packet.sequenceNumber else null
    }

    companion object {
        private const val SSRC = 1111L
        private const val OTHER_SSRC = 2222L
    }
}
