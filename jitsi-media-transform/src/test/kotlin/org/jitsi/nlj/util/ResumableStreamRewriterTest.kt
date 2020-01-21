/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.util

import io.kotlintest.matchers.withClue
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.rtp.ResumableStreamRewriter

internal class ResumableStreamRewriterTest : ShouldSpec() {
    init {
        var ret: Int
        val snr = ResumableStreamRewriter(false)

        "Initial state" {
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe -1
        }

        "Accept first packet." {
            ret = snr.rewriteSequenceNumber(true, 0xffff - 2)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
            ret shouldBe 0xffff - 2
        }

        "Retransmission." {
            ret = snr.rewriteSequenceNumber(true, 0xffff - 2)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
            ret shouldBe 0xffff - 2
        }

        "Retransmission & accept toggle." {
            snr.rewriteSequenceNumber(false, 0xffff - 2)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
        }

        "Retransmission & accept toggle again." {
            ret = snr.rewriteSequenceNumber(true, 0xffff - 2)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
            ret shouldBe 0xffff - 2
        }

        "Drop ordered packet." {
            snr.rewriteSequenceNumber(false, 0xffff - 1)
            snr.seqnumDelta shouldBe 1
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
        }

        "Drop re-ordered packet." {
            snr.rewriteSequenceNumber(false, 0xffff - 3)
            snr.seqnumDelta shouldBe 1
            snr.highestSequenceNumberSent shouldBe 0xffff - 2
        }

        "Accept after re-ordered drop." {
            ret = snr.rewriteSequenceNumber(true, 0xffff)
            snr.seqnumDelta shouldBe 1
            snr.highestSequenceNumberSent shouldBe 0xffff - 1
            ret shouldBe 0xffff - 1
        }

        "Drop ordered packet again." {
            snr.rewriteSequenceNumber(false, 0)
            snr.seqnumDelta shouldBe 2
            snr.highestSequenceNumberSent shouldBe 0xffff - 1
        }

        "Accept ordered packet." {
            ret = snr.rewriteSequenceNumber(true, 1)
            snr.seqnumDelta shouldBe 2
            snr.highestSequenceNumberSent shouldBe 0xffff
            ret shouldBe 0xffff
        }

        "Drop ordered packets." {
            for (i in 2 until 0xffff) {
                withClue("index = $i") {
                    snr.rewriteSequenceNumber(false, i)
                    snr.seqnumDelta shouldBe i + 1
                    snr.highestSequenceNumberSent shouldBe 0xffff
                }
            }
        }

        "Drop ordered packet again(2)" {
            snr.rewriteSequenceNumber(false, 0xffff)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0xffff
        }

        "Accept ordered packet again" {
            ret = snr.rewriteSequenceNumber(true, 0)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0
            ret shouldBe 0
        }

        "Retransmission + accept toggle" {
            ret = snr.rewriteSequenceNumber(true, 0xffff)
            snr.seqnumDelta shouldBe 0
            snr.highestSequenceNumberSent shouldBe 0
            ret shouldBe 0xffff
        }
    }
}
