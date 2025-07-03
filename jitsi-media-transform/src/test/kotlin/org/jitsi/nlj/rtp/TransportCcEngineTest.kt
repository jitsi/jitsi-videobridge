/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.instantOfEpochMicro
import org.jitsi.utils.time.FakeClock
import java.util.logging.Level

class TransportCcEngineTest : FunSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val bandwidthEstimator: BandwidthEstimator = mockk(relaxed = true)
    private val clock: FakeClock = FakeClock()
    private val logger = StdoutLogger(_level = Level.INFO)

    private val lossListener = object : LossListener {
        var numReceived = 0
        var numLost = 0

        override fun packetReceived(previouslyReportedLost: Boolean) {
            numReceived++
            if (previouslyReportedLost) {
                numLost--
            }
        }

        override fun packetLost(numLost: Int) {
            this.numLost += numLost
        }
    }

    private val transportCcEngine = ClassicTransportCcEngine(bandwidthEstimator, logger, clock).also {
        it.addLossListener(lossListener)
    }

    private fun makePacket(length: DataSize): PacketInfo {
        val len = length.bytes.toInt()
        val buf = BufferPool.getBuffer(len)
        buf.fill(0, 0, len)

        val packet = RtpPacket(buf, 0, len).apply {
            version = 2
        }
        return PacketInfo(packet)
    }

    init {
        test("Missing packet reports") {
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 4)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 0)) {
                SetBase(1, instantOfEpochMicro(100))
                AddReceivedPacket(1, instantOfEpochMicro(100))
                AddReceivedPacket(2, instantOfEpochMicro(110))
                AddReceivedPacket(3, instantOfEpochMicro(120))
                AddReceivedPacket(4, instantOfEpochMicro(130))
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 3
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 0
            }
            lossListener.numReceived shouldBe 1
            lossListener.numLost shouldBe 0
        }
        test("Duplicate packet reports") {
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 4)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 1)) {
                SetBase(4, instantOfEpochMicro(130))
                AddReceivedPacket(4, instantOfEpochMicro(130))
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant())

            val tccPacket2 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 2)) {
                SetBase(4, instantOfEpochMicro(130))
                AddReceivedPacket(4, instantOfEpochMicro(130))
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket2, clock.instant())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 1
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 0
            }
            lossListener.numReceived shouldBe 1
            lossListener.numLost shouldBe 0
        }
        test("Packets reported after lost") {
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 4)
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 5)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 1)) {
                SetBase(4, instantOfEpochMicro(130))
                AddReceivedPacket(5, instantOfEpochMicro(130))
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant())

            lossListener.numReceived shouldBe 1
            lossListener.numLost shouldBe 1

            val tccPacket2 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 2)) {
                SetBase(4, instantOfEpochMicro(130))
                AddReceivedPacket(4, instantOfEpochMicro(130))
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket2, clock.instant())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 1
                numPacketsUnreported shouldBe 0
            }
            lossListener.numReceived shouldBe 2
            lossListener.numLost shouldBe 0
        }
        test("Packet unreported") {
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 4)
            /* Force the report of sequence 4 to be evicted from the packet history */
            transportCcEngine.mediaPacketSent(makePacket(1300.bytes), 1004)

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 1
            }
            lossListener.numReceived shouldBe 0
            lossListener.numLost shouldBe 0
        }
    }
}
