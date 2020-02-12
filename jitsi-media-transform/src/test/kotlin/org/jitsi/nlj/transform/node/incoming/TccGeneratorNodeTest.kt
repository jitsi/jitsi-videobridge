package org.jitsi.nlj.transform.node.incoming

import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.matchers.numerics.shouldBeLessThan
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.test_utils.timeline
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.nlj.util.ms
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import java.util.Random

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
        streamInformationStore.addRtpExtensionMapping(RtpExtension(tccExtensionId.toByte(), RtpExtensionType.TRANSPORT_CC))
        streamInformationStore.addRtpPayloadType(vp8PayloadType)
        tccGenerator = TccGeneratorNode(onTccReady, streamInformationStore, StdoutLogger(), clock)
    }

    init {
        "when TCC is not signaled" {
            streamInformationStore.clearRtpPayloadTypes()
            timeline(clock) {
                repeat(100) { tccSeqNum ->
                    run { tccGenerator.processPacket(PacketInfo(createPacket(tccSeqNum))) }
                    elapse(10.ms())
                }
            }.run()
            "no TCC packets should be sent" {
                tccPackets.size shouldBe 0
            }
        }
        "when a series of packets (without marking) is received" {
            timeline(clock) {
                repeat(11) { tccSeqNum ->
                    run { tccGenerator.processPacket(PacketInfo(createPacket(tccSeqNum))) }
                    elapse(10.ms())
                }
            }.run()
            "a TCC packet" {
                should("be sent after 100ms") {
                    tccPackets.size shouldBeGreaterThan 0
                }
            }
        }
        "when a series of packets (where one is marked) is received" {
            timeline(clock) {
                run { tccGenerator.processPacket(PacketInfo(createPacket(1))) }
                elapse(10.ms())
                run { tccGenerator.processPacket(PacketInfo(createPacket(2))) }
                elapse(10.ms())
                run { tccGenerator.processPacket(PacketInfo(createPacket(3).apply { isMarked = true })) }
            }.run()
            "a TCC packet" {
                should("be sent after 20ms") {
                    tccPackets.size shouldBeGreaterThan 0
                }
            }
        }
        "when random packets are added" {
            val random = Random(1234)
            for (i in 1..10000) {
                tccGenerator.processPacket(PacketInfo(createPacket(random.nextInt(0xffff))))
                clock.elapse(10.ms())

                tccPackets.lastOrNull()?.let {
                    it.length shouldBeLessThan 1500
                }
            }
        }
        "when a few packets covering the seq num space are added" {
            for (i in listOf(0, 10000, 20000, 30000, 40000, 50000, 60000)) {
                tccGenerator.processPacket(PacketInfo(createPacket(i)))
            }
            for (i in 2..5000) {
                tccGenerator.processPacket(PacketInfo(createPacket(i and 0xffff)))
                clock.elapse(10.ms())

                tccPackets.lastOrNull()?.let {
                    it.length shouldBeLessThan 1500
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
        val ext = dummyPacket.addHeaderExtension(tccExtensionId, TccHeaderExtension.DATA_SIZE_BYTES)
        TccHeaderExtension.setSequenceNumber(ext, tccSeqNum)

        return dummyPacket
    }
}
