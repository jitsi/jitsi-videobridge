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

package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer

internal class NackFciTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a NackFci" {
            "from a buffer" {
                "with a single nack block" {
                    val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 25, 26)
                    val nackBlock = GenericNack(missingSeqNums)
                    val buf = nackBlock.getBuffer()
                    val nackFci = NackFci(buf)
                    should("parse the values correctly") {
                        nackFci.missingSeqNums shouldContainExactly missingSeqNums
                    }
                }
                "with multiple nack blocks" {
                    val missingSeqNums1 = listOf(10, 11, 13, 15, 17, 19, 21, 23, 25, 26)
                    val nackBlock1 = GenericNack(missingSeqNums1)
                    val missingSeqNums2 = listOf(30, 31, 33, 35, 37, 39, 41, 43, 45, 46)
                    val nackBlock2 = GenericNack(missingSeqNums2)
                    val buf = ByteBuffer.allocate(GenericNack.SIZE_BYTES * 2)
                    buf.put(nackBlock1.getBuffer())
                    buf.put(nackBlock2.getBuffer())
                    buf.rewind()

                    val nackFci = NackFci(buf)
                    should("parse the values correctly") {
                        nackFci.missingSeqNums shouldContainExactly (missingSeqNums1 + missingSeqNums2)
                    }
                }
            }
            "from values which require a single NACK block" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 25, 26)
                val nackFci = NackFci(missingSeqNums)
                should("set the missing seq nums correclty") {
                    nackFci.missingSeqNums shouldContainExactly missingSeqNums
                }
                "and then serializing it" {
                    val buf = nackFci.getBuffer()
                    // We know parsing from a buffer works, so test serialization by parsing the serialized
                    // data and checking it
                    val parsedNackFci = NackFci(buf)
                    should("write the data correctly") {
                        parsedNackFci.missingSeqNums shouldContainExactly missingSeqNums
                    }
                }
            }
            "from values which require multiple nack blocks" {
                val missingSeqNums = listOf(10, 11, 30, 31, 50, 51, 70, 71)
                val nackFci = NackFci(missingSeqNums)
                should("set the missing seq nums correclty") {
                    nackFci.missingSeqNums shouldContainExactly missingSeqNums
                }
                "and then serializing it" {
                    val buf = nackFci.getBuffer()
                    // We know parsing from a buffer works, so test serialization by parsing the serialized
                    // data and checking it
                    val parsedNackFci = NackFci(buf)
                    should("write the data correctly") {
                        parsedNackFci.missingSeqNums shouldContainExactly missingSeqNums
                    }
                }
            }
        }
    }
}
