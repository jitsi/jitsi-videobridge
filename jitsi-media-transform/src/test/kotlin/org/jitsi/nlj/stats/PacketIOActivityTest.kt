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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.secs
import java.time.Duration

class PacketIOActivityTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val packetIoActivity = PacketIOActivity()
    private val clock: FakeClock = FakeClock()

    init {
        context("Last packet time values") {
            clock.elapse(Duration.ofMinutes(1))
            val oldTime = clock.instant()
            clock.elapse(Duration.ofMinutes(1))
            val newTime = clock.instant()
            packetIoActivity.lastRtpPacketSentInstant = newTime
            packetIoActivity.lastRtpPacketReceivedInstant = newTime
            packetIoActivity.lastIceActivityInstant = newTime
            context("when setting an older time") {
                packetIoActivity.lastRtpPacketSentInstant = oldTime
                packetIoActivity.lastRtpPacketReceivedInstant = oldTime
                packetIoActivity.lastIceActivityInstant = oldTime
                should("not allow going backwards") {
                    packetIoActivity.lastRtpPacketSentInstant shouldBe newTime
                    packetIoActivity.lastRtpPacketReceivedInstant shouldBe newTime
                    packetIoActivity.lastIceActivityInstant shouldBe newTime
                }
            }
        }
        context("lastOverallRtpActivity") {
            should("only reflect RTP packet time values") {
                clock.elapse(30.secs)
                val rtpSentTime = clock.instant()
                clock.elapse(5.secs)
                val rtpReceivedTime = clock.instant()
                clock.elapse(10.secs)
                val iceTime = clock.instant()
                packetIoActivity.lastRtpPacketSentInstant = rtpSentTime
                packetIoActivity.lastRtpPacketReceivedInstant = rtpReceivedTime
                packetIoActivity.lastIceActivityInstant = iceTime
                packetIoActivity.lastRtpActivityInstant shouldBe rtpReceivedTime
            }
        }
        context("lastOverallActivity") {
            should("only reflect all packet time values") {
                clock.elapse(30.secs)
                val rtpSentTime = clock.instant()
                clock.elapse(10.secs)
                val iceTime = clock.instant()
                clock.elapse(5.secs)
                val rtpReceivedTime = clock.instant()
                packetIoActivity.lastRtpPacketSentInstant = rtpSentTime
                packetIoActivity.lastRtpPacketReceivedInstant = rtpReceivedTime
                packetIoActivity.lastIceActivityInstant = iceTime
                packetIoActivity.lastRtpActivityInstant shouldBe rtpReceivedTime
            }
        }
    }
}
