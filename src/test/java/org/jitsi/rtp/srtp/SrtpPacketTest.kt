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

package org.jitsi.rtp.srtp

import io.kotlintest.IsolationMode
import io.kotlintest.specs.BehaviorSpec
import org.jitsi.rtp.rtp.RtpHeader
import java.nio.ByteBuffer

internal class SrtpPacketTest : BehaviorSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        given("an SRTP packet created from a buffer") {
            val header = RtpHeader()
            val payload = ByteBuffer.allocate(110)
            val buf = ByteBuffer.allocate(header.sizeBytes + payload.limit())
            header.serializeTo(buf)
            buf.put(payload)
            val srtpPacket = SrtpPacket.create(buf)
            and("removing its auth tag") {
                srtpPacket.removeAuthTag(10)
                and("getting its buffer") {
                    val newBuf = srtpPacket.getBuffer()
                }
            }
        }
    }
}