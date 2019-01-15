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
import org.jitsi.rtp.util.RightToLeftBufferUtils
import java.nio.ByteBuffer

internal class GenericNackBlpTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private fun validateNackBlpBits(expectedOffsets: List<Int>, buf: ByteBuffer) {
        for (i in 0..15) {
            if ((i + 1) in expectedOffsets) {
                RightToLeftBufferUtils.getBitAsBool(buf, i) shouldBe true
            } else {
                RightToLeftBufferUtils.getBitAsBool(buf, i) shouldBe false
            }
        }
    }

    init {
        "Creating a GenericNackBlp" {
            "from values" {
                val offsets = listOf(1, 3, 5, 7, 9, 11, 13, 15)
                val nackBlp = GenericNackBlp(offsets)
                "should set the offsets correctly" {
                    nackBlp.lostPacketOffsets shouldContainExactly offsets
                }
                "and then serializing it" {
                    val buf = nackBlp.getBuffer()
                    should("encode the offsets correctly") {
                        validateNackBlpBits(offsets, buf)
                    }
                }
            }
            "from a buffer" {
                val offsets = listOf(1, 3, 5, 7, 9, 11, 13, 15)
                val nackBlp = GenericNackBlp(offsets)
                val buf = nackBlp.getBuffer()
                val parsedNackBlp = GenericNackBlp(buf)
                should("parse the offsets correctly") {
                    parsedNackBlp.lostPacketOffsets shouldContainExactly offsets
                }
            }
        }
    }
}
