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

package org.jitsi.nlj.stats

import io.kotlintest.IsolationMode
import io.kotlintest.minutes
import io.kotlintest.seconds
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.nlj.test_utils.FakeClock

class PacketIOActivityTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val packetIoActivity = PacketIOActivity()
    private val clock: FakeClock = FakeClock()

    init {
        "Last packet time values" {
            clock.elapse(1.minutes)
            val oldTime = clock.instant()
            clock.elapse(1.minutes)
            val newTime = clock.instant()
            packetIoActivity.lastRtpPacketSentTimestamp = newTime
            packetIoActivity.lastRtpPacketReceivedTimestamp = newTime
            packetIoActivity.lastIceActivityTimestamp = newTime
            "when setting an older time" {
                packetIoActivity.lastRtpPacketSentTimestamp = oldTime
                packetIoActivity.lastRtpPacketReceivedTimestamp = oldTime
                packetIoActivity.lastIceActivityTimestamp = oldTime
                should("not allow going backwards") {
                    packetIoActivity.lastRtpPacketSentTimestamp shouldBe newTime
                    packetIoActivity.lastRtpPacketReceivedTimestamp shouldBe newTime
                    packetIoActivity.lastIceActivityTimestamp shouldBe newTime
                }
            }
        }
        "lastOverallRtpActivity" {
            should("only reflect RTP packet time values") {
                clock.elapse(30.seconds)
                val rtpSentTime = clock.instant()
                clock.elapse(5.seconds)
                val rtpReceivedTime = clock.instant()
                clock.elapse(10.seconds)
                val iceTime = clock.instant()
                packetIoActivity.lastRtpPacketSentTimestamp = rtpSentTime
                packetIoActivity.lastRtpPacketReceivedTimestamp = rtpReceivedTime
                packetIoActivity.lastIceActivityTimestamp = iceTime
                packetIoActivity.lastOverallRtpActivity shouldBe rtpReceivedTime
            }
        }
        "lastOverallActivity" {
            should("only reflect all packet time values") {
                clock.elapse(30.seconds)
                val rtpSentTime = clock.instant()
                clock.elapse(10.seconds)
                val iceTime = clock.instant()
                clock.elapse(5.seconds)
                val rtpReceivedTime = clock.instant()
                packetIoActivity.lastRtpPacketSentTimestamp = rtpSentTime
                packetIoActivity.lastRtpPacketReceivedTimestamp = rtpReceivedTime
                packetIoActivity.lastIceActivityTimestamp = iceTime
                packetIoActivity.lastOverallRtpActivity shouldBe rtpReceivedTime
            }
        }
    }
}
