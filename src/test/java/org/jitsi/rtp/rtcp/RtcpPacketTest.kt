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
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.fci.tcc.Tcc

internal class RtcpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf


    init {
        "an RTCP packet, created from values" {
            val tccPacket = RtcpFbTccPacket(fci = Tcc(feedbackPacketCount = 1))
            repeat (10) {
                tccPacket.addPacket(it, System.currentTimeMillis())
            }
            "and getting its payload" {
                val buf = tccPacket.mutablePayload
                println(buf.toHex())
            }
        }

    }
}