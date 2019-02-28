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

package org.jitsi.rtp.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.BehaviorSpec

internal class RtpPacketTest : BehaviorSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf


    init {
        given("an RTP packet") {
            val rtpPacket = RtpPacket()
            `when`("we get its buffer") {
                val buf = rtpPacket.getBuffer()
                and("then modify its header") {
                    rtpPacket.header.ssrc = 123
                    and("get its buffer again") {
                        val newBuf = rtpPacket.getBuffer()
                        then("the change should be reflected") {
                            RtpHeader.getSsrc(newBuf) shouldBe 123
                        }
                    }
                }
            }
        }
    }
}