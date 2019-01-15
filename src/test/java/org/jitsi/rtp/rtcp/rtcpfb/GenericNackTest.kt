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
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class GenericNackTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a GenericNack" {
            "from values" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val genericNack = GenericNack(missingSeqNums)
                should("set the missing seq nums correctly") {
                    genericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
                "and then serializing it" {
                    val buf = genericNack.getBuffer()
                    should("write the data correctly") {
                        //TODO: we should test these GenericNack static helpers too
                        GenericNack.getPacketId(buf) shouldBe 10
                        GenericNack.getBlp(buf).lostPacketOffsets shouldContainExactly listOf(1, 3, 5, 7, 9, 11, 13, 16)
                    }
                }
            }
            "from a buffer" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val genericNack = GenericNack(missingSeqNums)
                val buf = genericNack.getBuffer()
                val parsedGenericNack = GenericNack(buf)
                should("parse the values correctly") {
                    parsedGenericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
            }
        }
    }
}
