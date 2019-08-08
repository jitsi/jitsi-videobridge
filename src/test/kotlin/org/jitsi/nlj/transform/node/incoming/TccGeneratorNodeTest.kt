package org.jitsi.nlj.transform.node.incoming

import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doNothing
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.whenever
import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.ms
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import java.time.Duration
import java.time.Instant

class TccGeneratorNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = spy()
    private val tccPackets = mutableListOf<RtcpPacket>()
    private val onTccReady = { tccPacket: RtcpPacket -> tccPackets.add(tccPacket); Unit }
    private val streamInformationStore: StreamInformationStore = mock()
    private val setTccExtId = argumentCaptor<(Int?) -> Unit>()
    private val tccExtensionId = 5

    private lateinit var tccGenerator: TccGeneratorNode

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        doNothing().whenever(streamInformationStore).onRtpExtensionMapping(
                eq(RtpExtensionType.TRANSPORT_CC), setTccExtId.capture())
        tccGenerator = TccGeneratorNode(onTccReady, streamInformationStore, clock)
        setTccExtId.firstValue(tccExtensionId)
    }

    init {
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

internal class TimelineTest(private val clock: FakeClock) {
    private val tasks = mutableListOf<() -> Unit>()

    fun time(time: Int) {
        tasks.add { clock.setTime(Instant.ofEpochMilli(time.toLong())) }
    }

    fun run(block: () -> Unit) {
        tasks.add(block)
    }

    fun elapse(duration: Duration) {
        tasks.add { clock.elapse(duration) }
    }

    fun run() {
        tasks.forEach { it.invoke() }
    }
}

internal fun timeline(clock: FakeClock, block: TimelineTest.() -> Unit): TimelineTest =
        TimelineTest(clock).apply(block)