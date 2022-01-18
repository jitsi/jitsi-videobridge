package org.jitsi.nlj.transform.node.incoming

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.test.time.FakeClock
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import org.jitsi.utils.ms
import java.time.Duration
import java.util.Random

@SuppressFBWarnings(
    value = ["NP_ALWAYS_NULL"],
    justification = "False positives with 'lateinit'."
)
class TccGeneratorNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = FakeClock()
    private val tccPackets = mutableListOf<RtcpPacket>()
    private val onTccReady = { tccPacket: RtcpPacket -> tccPackets.add(tccPacket); Unit }
    private val streamInformationStore = StreamInformationStoreImpl()
    private val tccExtensionId = 5
    // TCC is enabled by having at least one payload type which has "transport-cc" signaled as a rtcp-fb.
    private val vp8PayloadType = Vp8PayloadType(100, emptyMap(), setOf("transport-cc"))

    private lateinit var tccGenerator: TccGeneratorNode

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        streamInformationStore.addRtpExtensionMapping(
            RtpExtension(tccExtensionId.toByte(), RtpExtensionType.TRANSPORT_CC)
        )
        streamInformationStore.addRtpPayloadType(vp8PayloadType)
        tccGenerator = TccGeneratorNode(onTccReady, streamInformationStore, StdoutLogger(), clock)
    }

    init {
        context("when TCC is not signaled") {
            streamInformationStore.clearRtpPayloadTypes()
            with(clock) {
                repeat(100) { tccSeqNum ->
                    tccGenerator.processPacket(
                        PacketInfo(createPacket(tccSeqNum)).apply {
                            receivedTime = clock.instant()
                        }
                    )
                    elapse(10.ms)
                }
            }
            context("no TCC packets should be sent") {
                tccPackets.size shouldBe 0
            }
        }
        context("when a series of packets (without marking) is received") {
            with(clock) {
                repeat(11) { tccSeqNum ->
                    tccGenerator.processPacket(
                        PacketInfo(createPacket(tccSeqNum)).apply {
                            receivedTime = clock.instant()
                        }
                    )
                    elapse(10.ms)
                }
            }
            context("one TCC packet") {
                should("be sent after 100ms") {
                    tccPackets.size shouldBe 1
                }
                should("contain the right number of reports") {
                    tccPackets[0] should beInstanceOf<RtcpFbTccPacket>()
                    with(tccPackets[0] as RtcpFbTccPacket) {
                        iterator().asSequence().count() shouldBe 11
                    }
                }
            }
        }
        context("when a series of packets (where one is marked) is received") {
            with(clock) {
                tccGenerator.processPacket(
                    PacketInfo(createPacket(1)).apply {
                        receivedTime = clock.instant()
                    }
                )
                elapse(10.ms)
                tccGenerator.processPacket(
                    PacketInfo(createPacket(2)).apply {
                        receivedTime = clock.instant()
                    }
                )
                elapse(10.ms)
                tccGenerator.processPacket(
                    PacketInfo(
                        createPacket(3).apply {
                            isMarked = true
                        }
                    ).apply {
                        receivedTime = clock.instant()
                    }
                )
                elapse(100.ms)
                tccGenerator.processPacket(
                    PacketInfo(createPacket(4)).apply {
                        receivedTime = clock.instant()
                    }
                )
            }
            context("two TCC packets") {
                should("be sent") {
                    tccPackets.size shouldBe 2
                }
            }
        }
        context("when random packets are added") {
            val random = Random(1234)
            for (i in 1..10000) {
                tccGenerator.processPacket(
                    PacketInfo(createPacket(random.nextInt(0xffff))).apply {
                        receivedTime = clock.instant()
                    }
                )
                clock.elapse(10.ms)

                tccPackets.lastOrNull()?.let {
                    it.length shouldBeLessThan 1500
                }
            }
        }
        context("when a few packets covering the seq num space are added") {
            for (i in listOf(0, 10000, 20000, 30000, 40000, 50000, 60000)) {
                tccGenerator.processPacket(
                    PacketInfo(createPacket(i % 0xffff)).apply {
                        receivedTime = clock.instant()
                    }
                )
            }
            for (i in 2..5000) {
                tccGenerator.processPacket(
                    PacketInfo(createPacket(i % 0xffff)).apply {
                        receivedTime = clock.instant()
                    }
                )
                clock.elapse(10.ms)

                tccPackets.lastOrNull()?.let {
                    it.length shouldBeLessThan 1500
                }
            }
        }
        context("when sequence numbers cycle") {
            var prevSize = tccPackets.size
            repeat(100000) { tccSeqNum ->
                val pi = PacketInfo(createPacket(tccSeqNum % 0xffff)).apply {
                    receivedTime = clock.instant()
                }
                tccGenerator.processPacket(pi)
                clock.elapse(10.ms)

                tccPackets.size shouldBeLessThanOrEqual prevSize + 1
                prevSize = tccPackets.size
            }
            context("an appropriate number of TCC packets") {
                should("be sent") {
                    tccPackets.size shouldBe 9999
                }
            }
        }
        context("when sequence numbers cycle with losses") {
            var prevSize = tccPackets.size
            repeat(50000) { tccSeqNum ->
                val pi = PacketInfo(createPacket((tccSeqNum * 2) % 0xffff)).apply {
                    receivedTime = clock.instant()
                }
                tccGenerator.processPacket(pi)
                clock.elapse(20.ms)

                tccPackets.size shouldBeLessThanOrEqual prevSize + 1
                prevSize = tccPackets.size
            }
            context("an appropriate number of TCC packets") {
                should("be sent") {
                    tccPackets.size shouldBe 9999
                }
            }
        }
        context("when there is a loss after a TCC packet is sent") {
            with(clock) {
                repeat(11) { tccSeqNum ->
                    tccGenerator.processPacket(
                        PacketInfo(createPacket(tccSeqNum)).apply {
                            receivedTime = clock.instant()
                        }
                    )
                    elapse(10.ms)
                }
                elapse(100.ms)
                tccGenerator.processPacket(
                    PacketInfo(createPacket(20)).apply {
                        receivedTime = clock.instant()
                    }
                )
            }
            context("two TCC packets") {
                should("be sent") {
                    tccPackets.size shouldBe 2
                }
            }
            context("last TCC packet") {
                should("have a base of the lost packet") {
                    val lastTcc = tccPackets[1] as RtcpFbTccPacket
                    val firstReport = lastTcc.iterator().next()
                    firstReport should beInstanceOf<UnreceivedPacketReport>()
                    firstReport.seqNum shouldBe 11
                }
            }
        }
        context("when there is a packet reordering after a TCC packet is sent") {
            with(clock) {
                repeat(9) { tccSeqNum ->
                    tccGenerator.processPacket(
                        PacketInfo(createPacket(tccSeqNum)).apply {
                            receivedTime = clock.instant()
                        }
                    )
                    elapse(10.ms)
                }
                elapse(10.ms)
                tccGenerator.processPacket(
                    PacketInfo(createPacket(10)).apply {
                        receivedTime = clock.instant()
                    }
                )

                tccGenerator.processPacket(
                    PacketInfo(createPacket(9)).apply {
                        receivedTime = clock.instant() - Duration.ofMillis(10)
                    }
                )

                elapse(100.ms)
                tccGenerator.processPacket(
                    PacketInfo(createPacket(20)).apply {
                        receivedTime = clock.instant()
                    }
                )
            }
            context("two TCC packets") {
                should("be sent") {
                    tccPackets.size shouldBe 2
                }
            }
            context("last TCC packet") {
                should("have a base of the reordered packet") {
                    val lastTcc = tccPackets[1] as RtcpFbTccPacket
                    val firstReport = lastTcc.iterator().next()
                    firstReport should beInstanceOf<ReceivedPacketReport>()
                    firstReport.seqNum shouldBe 9
                }
            }
        }
    }

    private fun createPacket(tccSeqNum: Int): RtpPacket {
        val dummyPacket = RtpPacket(ByteArray(1500), 0, 1500).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = 100
            sequenceNumber = 123
            timestamp = 456L
            ssrc = 1234L
        }
        val ext =
            dummyPacket.addHeaderExtension(tccExtensionId, TccHeaderExtension.DATA_SIZE_BYTES)
        TccHeaderExtension.setSequenceNumber(ext, tccSeqNum)

        return dummyPacket
    }
}
