package org.jitsi.nlj.rtp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import java.util.logging.Level

class TransportCcEngineTest : FunSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val bandwidthEstimator: BandwidthEstimator = mockk(relaxed = true)
    private val clock: FakeClock = FakeClock()
    private val logger = StdoutLogger(_level = Level.INFO)

    private val transportCcEngine = TransportCcEngine(bandwidthEstimator, logger, clock)

    init {
        test("Missing packet reports") {
            transportCcEngine.mediaPacketSent(4, 1300.bytes)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 0)) {
                SetBase(1, 100)
                AddReceivedPacket(1, 100)
                AddReceivedPacket(2, 110)
                AddReceivedPacket(3, 120)
                AddReceivedPacket(4, 130)
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant().toEpochMilli())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 3
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 0
            }
        }
        test("Duplicate packet reports") {
            transportCcEngine.mediaPacketSent(4, 1300.bytes)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 1)) {
                SetBase(4, 130)
                AddReceivedPacket(4, 130)
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant().toEpochMilli())

            val tccPacket2 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 2)) {
                SetBase(4, 130)
                AddReceivedPacket(4, 130)
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket2, clock.instant().toEpochMilli())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 1
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 0
            }
        }
        test("Packets reported after lost") {
            transportCcEngine.mediaPacketSent(4, 1300.bytes)
            transportCcEngine.mediaPacketSent(5, 1300.bytes)

            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 1)) {
                SetBase(4, 130)
                AddReceivedPacket(5, 130)
                build()
            }
            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant().toEpochMilli())

            val tccPacket2 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 2)) {
                SetBase(4, 130)
                AddReceivedPacket(4, 130)
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket2, clock.instant().toEpochMilli())

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 1
                numPacketsUnreported shouldBe 0
            }
        }
        test("Packet unreported") {
            transportCcEngine.mediaPacketSent(4, 1300.bytes)
            /* Force the report of sequence 4 to be evicted from the packet history */
            transportCcEngine.mediaPacketSent(1004, 1300.bytes)

            with(transportCcEngine.getStatistics()) {
                numMissingPacketReports shouldBe 0
                numDuplicateReports shouldBe 0
                numPacketsReportedAfterLost shouldBe 0
                numPacketsUnreported shouldBe 1
            }
        }
    }
}
