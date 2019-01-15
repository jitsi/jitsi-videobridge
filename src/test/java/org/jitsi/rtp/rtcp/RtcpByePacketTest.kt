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

package org.jitsi.rtp.rtcp

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.string.shouldBeEmpty
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf

internal class RtcpByePacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating an RtcpByePacket" {
            "from a buffer" {
                val buf = byteBufferOf(
                    0x81.toByte(), 0xCB.toByte(), 0x00.toByte(), 0x01.toByte(),
                    // ssrc 3641474342
                    0xD9.toByte(), 0x0C.toByte(), 0x7D.toByte(), 0x26.toByte()
                )
                val packet = RtcpByePacket(buf)
                should("parse the values correctly") {
                    packet.ssrcs shouldHaveSize 1
                    packet.ssrcs shouldContain 3641474342
                    packet.reason.shouldBeEmpty()
                }
            }
        }
    }
}
