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
            should("continue from the last sequence number") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(SSRC, 101) shouldBe 101
                (102 until 40102).forEach { rewriter.dropped(SSRC, it) }
                rewriter.send(SSRC, 40102) shouldBe 102
                rewriter.send(SSRC, 40103) shouldBe 103
            }
            should("keep a later gap from packet loss") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC, 49999)
                rewriter.send(SSRC, 50000) shouldBe 101
                rewriter.send(SSRC, 50003) shouldBe 104
            }
            should("handle wrap around") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 65535) shouldBe 65535
                rewriter.dropped(SSRC, 29999)
                rewriter.send(SSRC, 30000) shouldBe 0
                rewriter.send(SSRC, 30001) shouldBe 1
            }
            should("close each gap") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC, 19999)
                rewriter.send(SSRC, 20000) shouldBe 101
                rewriter.dropped(SSRC, 59999)
                rewriter.send(SSRC, 60000) shouldBe 102
            }
            should("not affect other SSRCs") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(OTHER_SSRC, 500) shouldBe 500
                rewriter.dropped(SSRC, 39999)
                rewriter.send(OTHER_SSRC, 501) shouldBe 501
                rewriter.send(SSRC, 40000) shouldBe 101
            }
            should("continue from the highest sequence number when packets were reordered") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.send(SSRC, 102) shouldBe 102
                rewriter.send(SSRC, 101) shouldBe 101
                rewriter.dropped(SSRC, 39999)
                rewriter.send(SSRC, 40000) shouldBe 103
            }
            should("not close the gap with a packet that was accepted before the drop") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                // 101 was accepted and waits in the send queue while 102 is dropped.
                rewriter.dropped(SSRC, 102)
                rewriter.send(SSRC, 101) shouldBe 101
                (103 until 40000).forEach { rewriter.dropped(SSRC, it) }
                rewriter.send(SSRC, 40000) shouldBe 102
            }
            should("drop a late packet that would reuse a sequence number") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 100) shouldBe 100
                rewriter.dropped(SSRC, 101)
                rewriter.send(SSRC, 103) shouldBe 101
                rewriter.send(SSRC, 102).shouldBeNull()
                rewriter.send(SSRC, 104) shouldBe 102
            }
            should("not drop a packet that is far from the resume point") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.send(SSRC, 10000) shouldBe 10000
                rewriter.dropped(SSRC, 19999)
                rewriter.send(SSRC, 20000) shouldBe 10001
                // For example, a sender that started again with a new sequence number.
                rewriter.send(SSRC, 5000) shouldBe ((5000 - 9999) and 0xffff)
            }
        }
        context("An SSRC") {
            should("not change the first packet after packets dropped before it") {
                val rewriter = AudioSequenceNumberRewriter()
                rewriter.dropped(SSRC, 39999)
                rewriter.send(SSRC, 40000) shouldBe 40000
            }
        }
    }

    /**
     * Rewrites a packet from [ssrc] with sequence number [seq], and returns the sequence number after rewriting, or
     * null if the packet must be dropped.
     */
    private fun AudioSequenceNumberRewriter.send(ssrc: Long, seq: Int): Int? {
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
