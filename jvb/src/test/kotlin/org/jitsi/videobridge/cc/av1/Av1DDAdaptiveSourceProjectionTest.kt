/*
 * Copyright @ 2019 - Present, 8x8 Inc
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
package org.jitsi.videobridge.cc.av1

import jakarta.xml.bind.DatatypeConverter.parseHexBinary
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getIndex
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.cc.RtpState
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.xml.bind.DatatypeConverter

class Av1DDAdaptiveSourceProjectionTest {
    private val logger: Logger = LoggerImpl(javaClass.name)

    @Test
    fun singlePacketProjectionTest() {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "singlePacketProjectionTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        val generator = ScalableAv1PacketGenerator(1)
        val packetInfo = generator.nextPacket()
        val packet = packetInfo.packetAs<Av1DDPacket>()
        val targetIndex = getIndex(eid = 0, dt = 0)
        Assert.assertTrue(context.accept(packetInfo, targetIndex))
        context.rewriteRtp(packetInfo)
        Assert.assertEquals(10001, packet.sequenceNumber)
        Assert.assertEquals(1003000, packet.timestamp)
        Assert.assertEquals(0, packet.frameNumber)
        Assert.assertEquals(0, packet.frameInfo?.spatialId)
        Assert.assertEquals(0, packet.frameInfo?.temporalId)
    }

    private fun runInOrderTest(generator: Av1PacketGenerator, targetIndex: Int, expectAccept: (FrameInfo) -> Boolean) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        for (i in 0..99999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val frameInfo = packet.frameInfo!!

            val accepted = context.accept(packetInfo, targetIndex)
            val endOfFrame = packet.isEndOfFrame
            val endOfPicture = packet.isMarked // Save this before rewriteRtp
            if (expectAccept(frameInfo)) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (endOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
    }

    @Test
    fun simpleNonScalableTest() {
        val generator = NonScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            true
        }
    }

    @Test
    fun simpleTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun filteredTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largerFrameTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun largerFrameTemporalFilteredTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun hugeFrameTest() {
        val generator = TemporallyScaledPacketGenerator(200)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun simpleSvcTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            true
        }
    }

    @Test
    fun filteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0
        }
    }

    @Test
    fun spatialAndTemporalFilteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerSvcTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            true
        }
    }

    @Test
    fun largerFilteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredSvcTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun simpleKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2 || !it.hasInterPictureDependency()
        }
    }

    @Test
    fun filteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && (it.spatialId == 2 || !it.hasInterPictureDependency())
        }
    }

    @Test
    fun spatialAndTemporalFilteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2 || !it.hasInterPictureDependency()
        }
    }

    @Test
    fun largerFilteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && (it.spatialId == 2 || !it.hasInterPictureDependency())
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredKSvcTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun simpleSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2
        }
    }

    @Test
    fun filteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && it.spatialId == 2
        }
    }

    @Test
    fun spatialAndTemporalFilteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2
        }
    }

    @Test
    fun largerFilteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && it.spatialId == 2
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredSingleEncodingSimulcastTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    private class ProjectedPacket constructor(
        val packet: Av1DDPacket,
        val origSeq: Int,
        val extOrigSeq: Long,
        val extFrameNum: Long,
    )

    /** Run an out-of-order test on a single stream, randomized order except for the first
     *  [initialOrderedCount] packets.  */
    private fun doRunOutOfOrderTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        initialOrderedCount: Int,
        seed: Long,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val expectedInitialTs: Long = RtpUtils.applyTimestampDelta(initialState.maxTimestamp, 3000)
        val expectedTsOffset: Long = RtpUtils.getTimestampDiff(expectedInitialTs, generator.ts)
        val reorderSize = 64
        val buffer = ArrayList<PacketInfo>(reorderSize)
        for (i in 0 until reorderSize) {
            buffer.add(generator.nextPacket())
        }
        val random = Random(seed)
        var orderedCount = initialOrderedCount - 1
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        var latestSeq = buffer[0].packetAs<Av1DDPacket>().sequenceNumber
        val projectedPackets = TreeMap<Long, ProjectedPacket>()
        val origSeqIdxTracker = RtpSequenceIndexTracker()
        val newSeqIdxTracker = RtpSequenceIndexTracker()
        val frameNumsDropped = HashSet<Long>()
        val frameNumsIndexTracker = RtpSequenceIndexTracker()
        for (i in 0..99999) {
            val packetInfo = buffer[0]
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val origSeq = packet.sequenceNumber
            val origTs = packet.timestamp
            if (latestSeq isOlderThan origSeq) {
                latestSeq = origSeq
            }
            val frameInfo = packet.frameInfo!!

            val accepted = context.accept(packetInfo, targetIndex)
            val oldestValidSeq: Int =
                RtpUtils.applySequenceNumberDelta(
                    latestSeq,
                    -((Av1DDFrameMap.FRAME_MAP_SIZE - 1) * generator.packetsPerFrame)
                )
            if (origSeq isOlderThan oldestValidSeq && !accepted) {
                /* This is fine; packets that are too old get ignored. */
                /* Note we don't want assertFalse(accepted) here because slightly-too-old packets
                 * that are part of an existing accepted frame will be accepted.
                 */
                val extFrameNum = frameNumsIndexTracker.update(packet.frameNumber)
                frameNumsDropped.add(extFrameNum)
            } else if (expectAccept(frameInfo)
            ) {
                Assert.assertTrue(accepted)

                context.rewriteRtp(packetInfo)
                Assert.assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.timestamp)
                val newSeq = packet.sequenceNumber
                val extNewSeq = newSeqIdxTracker.update(newSeq)
                val extOrigSeq = origSeqIdxTracker.update(origSeq)
                Assert.assertFalse(projectedPackets.containsKey(extNewSeq))
                val extFrameNum = frameNumsIndexTracker.update(packet.frameNumber)
                projectedPackets[extNewSeq] = ProjectedPacket(packet, origSeq, extOrigSeq, extFrameNum)
            } else {
                Assert.assertFalse(accepted)
            }
            if (orderedCount > 0) {
                buffer.removeAt(0)
                buffer.add(generator.nextPacket())
                orderedCount--
            } else {
                buffer[0] = generator.nextPacket()
                buffer.shuffle(random)
            }
        }
        val frameNumsSeen = HashSet<Long>()

        /* Add packets that weren't added yet, or that were dropped for being too old, to frameNumsSeen. */
        frameNumsSeen.addAll(frameNumsDropped)
        buffer.forEach {
            frameNumsSeen.add(frameNumsIndexTracker.update(it.packetAs<Av1DDPacket>().frameNumber))
        }

        val iter = projectedPackets.keys.iterator()
        var prevPacket = projectedPackets[iter.next()]!!
        frameNumsSeen.add(prevPacket.extFrameNum)
        while (iter.hasNext()) {
            val packet = projectedPackets[iter.next()]
            Assert.assertTrue(packet!!.origSeq isNewerThan prevPacket.origSeq)
            frameNumsSeen.add(packet.extFrameNum)
            Assert.assertTrue(
                RtpUtils.getSequenceNumberDelta(
                    prevPacket.packet.frameNumber,
                    packet.packet.frameNumber
                ) <= 0
            )
            if (packet.packet.isStartOfFrame) {
                Assert.assertTrue(
                    RtpUtils.getSequenceNumberDelta(
                        prevPacket.packet.frameNumber,
                        packet.packet.frameNumber
                    ) < 0
                )
                if (prevPacket.packet.sequenceNumber ==
                    RtpUtils.applySequenceNumberDelta(packet.packet.sequenceNumber, -1)
                ) {
                    Assert.assertTrue(prevPacket.packet.isEndOfFrame)
                }
            } else {
                if (prevPacket.packet.sequenceNumber ==
                    RtpUtils.applySequenceNumberDelta(packet.packet.sequenceNumber, -1)
                ) {
                    Assert.assertEquals(prevPacket.packet.frameNumber, packet.packet.frameNumber)
                    Assert.assertEquals(prevPacket.packet.timestamp, packet.packet.timestamp)
                }
            }
            packet.packet.frameInfo?.fdiff?.forEach {
                Assert.assertTrue(frameNumsSeen.contains(packet.extFrameNum - it))
            }
            prevPacket = packet
        }

        /* Overall, we should not have expanded sequence numbers. */
        val firstPacket = projectedPackets.firstEntry().value
        val lastPacket = projectedPackets.lastEntry().value
        val origDelta = lastPacket!!.extOrigSeq - firstPacket!!.extOrigSeq
        val projDelta = projectedPackets.lastKey() - projectedPackets.firstKey()
        Assert.assertTrue(projDelta <= origDelta)
    }

    /** Run multiple instances of out-of-order test on a single stream, with different
     * random seeds.  */
    private fun runOutOfOrderTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        initialOrderedCount: Int = 1,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        /* Seeds that have triggered problems in the past for this or VP8/VP9, plus a random one. */
        val seeds = longArrayOf(
            1576267371838L,
            1578347926155L,
            1579620018479L,
            5786714086792432950L,
            5929140296748347521L,
            -8226056792707023108L,
            System.currentTimeMillis()
        )
        for (seed in seeds) {
            try {
                doRunOutOfOrderTest(generator, targetIndex, initialOrderedCount, seed, expectAccept)
            } catch (e: Throwable) {
                logger.error(
                    "Exception thrown in randomized test, seed = $seed",
                    e
                )
                throw e
            }
            generator.reset()
        }
    }

    @Test
    fun simpleOutOfOrderNonScalableTest() {
        val generator = NonScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            true
        }
    }

    @Test
    fun simpleOutOfOrderTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun filteredOutOfOrderTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largerFrameOutOfOrderTemporalProjectionTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun largerFrameOutOfOrderTemporalFilteredTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun hugeFrameOutOfOrderTest() {
        val generator = TemporallyScaledPacketGenerator(200)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun simpleSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            true
        }
    }

    @Test
    fun filteredSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredOutOfOrderSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0
        }
    }

    @Test
    fun spatialAndTemporalFilteredSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            true
        }
    }

    @Test
    fun largerFilteredSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredSvcOutOfOrderTest() {
        val generator = ScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun simpleKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2 || !it.hasInterPictureDependency()
        }
    }

    @Test
    fun filteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && (it.spatialId == 2 || !it.hasInterPictureDependency())
        }
    }

    @Test
    fun spatialAndTemporalFilteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2)) {
            it.spatialId == 2 || !it.hasInterPictureDependency()
        }
    }

    @Test
    fun largerFilteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2)) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2)) {
            it.temporalId == 0 && (it.spatialId == 2 || !it.hasInterPictureDependency())
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredKSvcOutOfOrderTest() {
        val generator = KeyScalableAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0)) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun simpleSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2), 3) {
            it.spatialId == 2
        }
    }

    @Test
    fun filteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2), 3) {
            it.spatialId == 0
        }
    }

    @Test
    fun temporalFilteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2), 3) {
            it.temporalId == 0 && it.spatialId == 2
        }
    }

    @Test
    fun spatialAndTemporalFilteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0), 3) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun largerSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2 + 2), 7) {
            it.spatialId == 2
        }
    }

    @Test
    fun largerFilteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 2), 7) {
            it.spatialId == 0
        }
    }

    @Test
    fun largerTemporalFilteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 3 * 2), 7) {
            it.temporalId == 0 && it.spatialId == 2
        }
    }

    @Test
    fun largerSpatialAndTemporalFilteredSingleEncodingSimulcastOutOfOrderTest() {
        val generator = SingleEncodingSimulcastAv1PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, dt = 0), 7) {
            it.spatialId == 0 && it.temporalId == 0
        }
    }

    @Test
    fun slightlyDelayedKeyframeTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "slightlyDelayedKeyframeTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        val firstPacketInfo = generator.nextPacket()
        val targetIndex = getIndex(eid = 0, dt = 2)
        for (i in 0..2) {
            val packetInfo = generator.nextPacket()

            Assert.assertFalse(context.accept(packetInfo, targetIndex))
        }
        Assert.assertTrue(context.accept(firstPacketInfo, targetIndex))
        context.rewriteRtp(firstPacketInfo)
        for (i in 0..9995) {
            val packetInfo = generator.nextPacket()
            Assert.assertTrue(context.accept(packetInfo, targetIndex))
            context.rewriteRtp(packetInfo)
        }
    }

    @Test
    fun veryDelayedKeyframeTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "veryDelayedKeyframeTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        val firstPacketInfo = generator.nextPacket()
        val targetIndex = getIndex(eid = 0, dt = 2)
        for (i in 0..3) {
            val packetInfo = generator.nextPacket(missedStructure = true)
            Assert.assertFalse(context.accept(packetInfo, targetIndex))
        }
        Assert.assertFalse(context.accept(firstPacketInfo, targetIndex))
        for (i in 0..9) {
            val packetInfo = generator.nextPacket()
            Assert.assertFalse(context.accept(packetInfo, targetIndex))
        }
        generator.requestKeyframe()
        for (i in 0..9995) {
            val packetInfo = generator.nextPacket()
            Assert.assertTrue(context.accept(packetInfo, targetIndex))
            context.rewriteRtp(packetInfo)
        }
    }

    @Test
    fun twoStreamsNoSwitchingTest() {
        val generator1 = TemporallyScaledPacketGenerator(packetsPerFrame = 3, encodingId = 1)
        val generator2 = TemporallyScaledPacketGenerator(packetsPerFrame = 3, encodingId = 0)
        generator2.ssrc = 0xdeadbeefL
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "twoStreamsNoSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(diagnosticContext, initialState, null, logger)
        val targetIndex = getIndex(eid = 1, dt = 2)
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        for (i in 0..9999) {
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Av1DDPacket>()

            Assert.assertTrue(context.accept(packetInfo1, targetIndex))
            val packetInfo2 = generator2.nextPacket()
            Assert.assertFalse(context.accept(packetInfo2, targetIndex))
            context.rewriteRtp(packetInfo1)
            Assert.assertEquals(expectedSeq, packet1.sequenceNumber)
            Assert.assertEquals(expectedTs, packet1.timestamp)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet1.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
    }

    @Test
    fun twoStreamsSwitchingTest() {
        val generator1 = TemporallyScaledPacketGenerator(3, encodingId = 0)
        val generator2 = TemporallyScaledPacketGenerator(3, encodingId = 1)
        generator2.ssrc = 0xdeadbeefL
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "twoStreamsSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(diagnosticContext, initialState, null, logger)
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        var expectedTemplateOffset = 0
        var targetIndex = getIndex(eid = 0, dt = 2)

        /* Start by wanting encoding 0 */
        for (i in 0..899) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Av1DDPacket>()
            if (i == 0) {
                expectedTemplateOffset = packet1.descriptor!!.structure.templateIdOffset
            }
            Assert.assertTrue(context.accept(packetInfo1, targetIndex))
            context.rewriteRtp(packetInfo1)
            Assert.assertTrue(context.rewriteRtcp(srPacket1))
            Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
            Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            Assert.assertFalse(context.accept(packetInfo2, targetIndex))
            Assert.assertFalse(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(expectedSeq, packet1.sequenceNumber)
            Assert.assertEquals(expectedTs, packet1.timestamp)
            Assert.assertEquals(expectedFrameNumber, packet1.frameNumber)
            Assert.assertEquals(expectedTemplateOffset, packet1.descriptor?.structure?.templateIdOffset)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet1.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (packet1.isMarked) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }

        /* Switch to wanting encoding 1, but don't send a keyframe. We should stay at the first encoding. */
        targetIndex = getIndex(eid = 1, dt = 2)
        for (i in 0..89) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Av1DDPacket>()
            Assert.assertTrue(context.accept(packetInfo1, targetIndex))
            context.rewriteRtp(packetInfo1)
            Assert.assertTrue(context.rewriteRtcp(srPacket1))
            Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
            Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            Assert.assertFalse(context.accept(packetInfo2, targetIndex))
            Assert.assertFalse(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(expectedSeq, packet1.sequenceNumber)
            Assert.assertEquals(expectedTs, packet1.timestamp)
            Assert.assertEquals(expectedFrameNumber, packet1.frameNumber)
            Assert.assertEquals(expectedTemplateOffset, packet1.descriptor?.structure?.templateIdOffset)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet1.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (packet1.isMarked) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
        generator1.requestKeyframe()
        generator2.requestKeyframe()

        /* After a keyframe we should accept spatial layer 1 */
        for (i in 0..8999) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Av1DDPacket>()

            /* We will cut off the layer 0 keyframe after 1 packet, once we see the layer 1 keyframe. */
            Assert.assertEquals(i == 0, context.accept(packetInfo1, targetIndex))
            Assert.assertEquals(i == 0, context.rewriteRtcp(srPacket1))
            if (i == 0) {
                context.rewriteRtp(packetInfo1)
                Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
                Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
                expectedTemplateOffset += packet1.descriptor!!.structure.templateCount
            }
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            val packet2 = packetInfo2.packetAs<Av1DDPacket>()
            Assert.assertTrue(context.accept(packetInfo2, targetIndex))
            val expectedTemplateId = (packet2.descriptor!!.frameDependencyTemplateId + expectedTemplateOffset) % 64
            context.rewriteRtp(packetInfo2)
            Assert.assertTrue(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(packet2.ssrc, srPacket2.senderSsrc)
            Assert.assertEquals(packet2.timestamp, srPacket2.senderInfo.rtpTimestamp)
            if (i == 0) {
                /* We leave a 1-packet gap for the layer 0 keyframe. */
                expectedSeq += 2
                /* ts will advance by an extra 3000 samples for the extra frame. */
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                /* frame number will advance by 1 for the extra keyframe. */
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            Assert.assertEquals(expectedSeq, packet2.sequenceNumber)
            Assert.assertEquals(expectedTs, packet2.timestamp)
            Assert.assertEquals(expectedFrameNumber, packet2.frameNumber)
            Assert.assertEquals(expectedTemplateId, packet2.descriptor?.frameDependencyTemplateId)
            if (packet2.descriptor?.newTemplateDependencyStructure != null) {
                Assert.assertEquals(
                    expectedTemplateOffset,
                    packet2.descriptor?.newTemplateDependencyStructure?.templateIdOffset
                )
            }
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet2.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (packet2.isMarked) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
    }

    @Test
    fun temporalLayerSwitchingTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "temporalLayerSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        var targetTid = 0
        var decodableTid = 0
        var targetIndex = getIndex(0, targetTid)
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        for (i in 0..9999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val accepted = context.accept(packetInfo, targetIndex)
            if (accepted) {
                if (decodableTid < packet.frameInfo!!.temporalId) {
                    decodableTid = packet.frameInfo!!.temporalId
                }
            } else {
                if (decodableTid > packet.frameInfo!!.temporalId - 1) {
                    decodableTid = packet.frameInfo!!.temporalId - 1
                }
            }
            if (packet.frameInfo!!.temporalId <= decodableTid) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (packet.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
                if (i % 97 == 0) { // Prime number so it's out of sync with packet cycles.
                    targetTid = (targetTid + 2) % 3
                    targetIndex = getIndex(0, targetTid)
                }
            }
        }
    }

    private fun runLargeDropoutTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        for (i in 0..999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Av1DDPacket>()

            val accepted = context.accept(packetInfo, targetIndex)
            val frameInfo = packet.frameInfo!!
            val endOfPicture = packet.isMarked
            if (expectAccept(frameInfo)) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (packet.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
        for (gap in 64..65536 step { it * 2 }) {
            for (i in 0 until gap) {
                generator.nextPacket()
            }
            var packetInfo: PacketInfo
            var packet: Av1DDPacket
            var frameInfo: FrameInfo
            do {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                frameInfo = packet.frameInfo!!
            } while (!expectAccept(frameInfo))
            var endOfPicture = packet.isMarked
            Assert.assertTrue(context.accept(packetInfo, targetIndex))
            context.rewriteRtp(packetInfo)

            /* Allow any values after a gap. */
            expectedSeq = RtpUtils.applySequenceNumberDelta(packet.sequenceNumber, 1)
            expectedTs = packet.timestamp
            expectedFrameNumber = packet.frameNumber
            if (packet.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
            for (i in 0..999) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(packetInfo, targetIndex)
                endOfPicture = packet.isMarked
                frameInfo = packet.frameInfo!!
                if (expectAccept(frameInfo)) {
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                    Assert.assertEquals(expectedTs, packet.timestamp)
                    Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                } else {
                    Assert.assertFalse(accepted)
                }
                if (packet.isEndOfFrame) {
                    expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
                }
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }
        }
    }

    @Test
    fun largeDropoutTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runLargeDropoutTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun filteredDropoutTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runLargeDropoutTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largeFrameDropoutTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runLargeDropoutTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun largeFrameFilteredDropoutTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runLargeDropoutTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    private fun runSourceSuspensionTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            null,
            logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0

        var packetInfo: PacketInfo
        var packet: Av1DDPacket
        var frameInfo: FrameInfo

        var lastPacketAccepted = false
        var lastFrameAccepted = -1

        for (i in 0..999) {
            packetInfo = generator.nextPacket()
            packet = packetInfo.packetAs()
            frameInfo = packet.frameInfo!!
            val accepted = context.accept(packetInfo, targetIndex)
            val endOfPicture = packet.isMarked
            if (expectAccept(frameInfo)) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                lastPacketAccepted = true
                lastFrameAccepted = packet.frameNumber
            } else {
                Assert.assertFalse(accepted)
                lastPacketAccepted = false
            }
            if (packet.isEndOfFrame) {
                expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
            }
        }
        for (suspended in 64..65536 step { it * 2 }) {
            /* If the last frame was accepted, finish the current frame if this generator is creating multi-packet
                frames. */
            if (lastPacketAccepted) {
                while (generator.packetOfFrame != 0) {
                    packetInfo = generator.nextPacket()
                    packet = packetInfo.packetAs()

                    val accepted = context.accept(packetInfo, targetIndex)
                    val endOfPicture = packet.isMarked
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                    if (packet.isEndOfFrame) {
                        expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
                    }
                    if (endOfPicture) {
                        expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                    }
                }
            }
            /* Turn the source off for a time. */
            for (i in 0 until suspended) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()

                val accepted = context.accept(packetInfo, RtpLayerDesc.SUSPENDED_INDEX)
                Assert.assertFalse(accepted)
                val endOfPicture = packet.isMarked
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }

            /* Switch back to wanting [targetIndex], but don't send a keyframe for a while.
             * Should still be dropped. */
            for (i in 0 until 30) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()

                val accepted = context.accept(packetInfo, targetIndex)
                val endOfPicture = packet.isMarked
                Assert.assertFalse(accepted)
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }

            /* Request a keyframe.  Will be sent as of the next frame. */
            generator.requestKeyframe()
            /* If this generator is creating multi-packet frames, finish the previous frame. */
            while (generator.packetOfFrame != 0) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(packetInfo, targetIndex)
                val endOfPicture = packet.isMarked
                Assert.assertFalse(accepted)
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }
            expectedFrameNumber = RtpUtils.applySequenceNumberDelta(lastFrameAccepted, 1)

            for (i in 0..999) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                frameInfo = packet.frameInfo!!
                val accepted = context.accept(packetInfo, targetIndex)
                val endOfPicture = packet.isMarked
                if (expectAccept(frameInfo)) {
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                    Assert.assertEquals(expectedTs, packet.timestamp)
                    Assert.assertEquals(expectedFrameNumber, packet.frameNumber)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                    lastPacketAccepted = true
                    lastFrameAccepted = packet.frameNumber
                } else {
                    Assert.assertFalse(accepted)
                    lastPacketAccepted = false
                }
                if (packet.isEndOfFrame) {
                    expectedFrameNumber = RtpUtils.applySequenceNumberDelta(expectedFrameNumber, 1)
                }
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }
        }
    }

    @Test
    fun sourceSuspensionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runSourceSuspensionTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun filteredSourceSuspensionTest() {
        val generator = TemporallyScaledPacketGenerator(1)
        runSourceSuspensionTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun largeFrameSourceSuspensionTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runSourceSuspensionTest(generator, getIndex(eid = 0, dt = 2)) {
            true
        }
    }

    @Test
    fun largeFrameFilteredSourceSuspensionTest() {
        val generator = TemporallyScaledPacketGenerator(3)
        runSourceSuspensionTest(generator, getIndex(eid = 0, dt = 0)) {
            it.temporalId == 0
        }
    }

    @Test
    fun persistentStateProjectionTest() {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "singlePacketProjectionTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState,
            Av1PersistentState(5555, 7),
            logger
        )
        val generator = ScalableAv1PacketGenerator(1)
        val packetInfo = generator.nextPacket()
        val packet = packetInfo.packetAs<Av1DDPacket>()
        val targetIndex = getIndex(eid = 0, dt = 0)
        Assert.assertTrue(context.accept(packetInfo, targetIndex))
        context.rewriteRtp(packetInfo)
        Assert.assertEquals(10001, packet.sequenceNumber)
        Assert.assertEquals(1003000, packet.timestamp)
        Assert.assertEquals(5556, packet.frameNumber)
        Assert.assertEquals(0, packet.frameInfo?.spatialId)
        Assert.assertEquals(0, packet.frameInfo?.temporalId)
        Assert.assertEquals(7, packet.descriptor?.structure?.templateIdOffset)
    }
}

private open class Av1PacketGenerator(
    val packetsPerFrame: Int,
    val keyframeTemplates: Array<Int>,
    val normalTemplates: Array<Int>,
    // Equivalent to number of layers
    val framesPerTimestamp: Int,
    templateDdHex: String,
    val allKeyframesGetStructure: Boolean = false,
    val encodingId: Int = 0
) {
    private val logger: Logger = LoggerImpl(javaClass.name)

    var packetOfFrame = 0
        private set
    private var frameOfPicture = 0

    private var seq = 0
    var ts: Long = 0L
        private set
    var ssrc: Long = 0xcafebabeL
    private var frameNumber = 0
    private var keyframePicture = false
    private var keyframeRequested = false
    private var pictureCount = 0
    private var receivedTime = baseReceivedTime
    private var templateIdx = 0
    private var packetCount = 0
    private var octetCount = 0

    private val structure: Av1TemplateDependencyStructure

    init {
        val dd = parseHexBinary(templateDdHex)
        structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure
    }

    fun reset() {
        val useRandom = true // switch off to ease debugging
        val seed = System.currentTimeMillis()
        val random = Random(seed)
        seq = if (useRandom) random.nextInt() % 0x10000 else 0
        ts = if (useRandom) random.nextLong() % 0x100000000L else 0
        frameNumber = 0
        packetOfFrame = 0
        frameOfPicture = 0
        keyframePicture = true
        keyframeRequested = false
        ssrc = 0xcafebabeL
        pictureCount = 0
        receivedTime = baseReceivedTime
        templateIdx = 0
        packetCount = 0
        octetCount = 0
    }

    fun nextPacket(missedStructure: Boolean = false): PacketInfo {
        val startOfFrame = packetOfFrame == 0
        val endOfFrame = packetOfFrame == packetsPerFrame - 1
        val startOfPicture = startOfFrame && frameOfPicture == 0
        val endOfPicture = endOfFrame && frameOfPicture == framesPerTimestamp - 1

        val templateId = (
            (if (keyframePicture) keyframeTemplates[templateIdx] else normalTemplates[templateIdx]) +
                structure.templateIdOffset
            ) % 64

        val buffer = packetTemplate.clone()
        val rtpPacket = RtpPacket(buffer, 0, buffer.size)
        rtpPacket.ssrc = ssrc
        rtpPacket.sequenceNumber = seq
        rtpPacket.timestamp = ts
        rtpPacket.isMarked = endOfPicture

        val dd = Av1DependencyDescriptorHeaderExtension(
            startOfFrame = startOfFrame,
            endOfFrame = endOfFrame,
            frameDependencyTemplateId = templateId,
            frameNumber = frameNumber,
            newTemplateDependencyStructure =
            if (keyframePicture && startOfFrame && (startOfPicture || allKeyframesGetStructure)) {
                structure
            } else {
                null
            },
            activeDecodeTargetsBitmask = null,
            customDtis = null,
            customFdiffs = null,
            customChains = null,
            structure = structure
        )

        val ext = rtpPacket.addHeaderExtension(AV1_DD_HEADER_EXTENSION_ID, dd.encodedLength)
        dd.write(ext)
        rtpPacket.encodeHeaderExtensions()

        val av1Packet = Av1DDPacket(
            rtpPacket,
            AV1_DD_HEADER_EXTENSION_ID,
            if (missedStructure) null else structure,
            logger
        )
        av1Packet.encodingId = encodingId

        val info = PacketInfo(av1Packet)
        info.receivedTime = receivedTime

        seq = RtpUtils.applySequenceNumberDelta(seq, 1)
        packetCount++
        octetCount += av1Packet.length

        if (endOfFrame) {
            packetOfFrame = 0
            if (endOfPicture) {
                frameOfPicture = 0
            } else {
                frameOfPicture++
            }
            templateIdx++
            if (keyframeRequested) {
                keyframePicture = true
                templateIdx = 0
            } else if (keyframePicture) {
                if (templateIdx >= keyframeTemplates.size) {
                    keyframePicture = false
                }
            }
            frameNumber = RtpUtils.applySequenceNumberDelta(frameNumber, 1)
        } else {
            packetOfFrame++
        }

        if (endOfPicture) {
            ts = RtpUtils.applyTimestampDelta(ts, 3000)

            keyframeRequested = false
            if (templateIdx >= normalTemplates.size) {
                templateIdx = 0
            }
            pictureCount++
            receivedTime = baseReceivedTime + Duration.ofMillis(pictureCount * 100L / 3)
        }

        return info
    }

    fun requestKeyframe() {
        if (packetOfFrame == 0) {
            keyframePicture = true
            templateIdx = 0
        } else {
            keyframeRequested = true
        }
    }

    val srPacket: RtcpSrPacket
        get() {
            val srPacketBuilder = RtcpSrPacketBuilder()
            srPacketBuilder.rtcpHeader.senderSsrc = ssrc
            val siBuilder = srPacketBuilder.senderInfo
            siBuilder.setNtpFromJavaTime(receivedTime.toEpochMilli())
            siBuilder.rtpTimestamp = ts
            siBuilder.sendersOctetCount = packetCount.toLong()
            siBuilder.sendersOctetCount = octetCount.toLong()
            return srPacketBuilder.build()
        }

    init {
        reset()
    }

    companion object {
        val baseReceivedTime: Instant = Instant.ofEpochMilli(1577836800000L) // 2020-01-01 00:00:00 UTC

        const val AV1_DD_HEADER_EXTENSION_ID = 11

        private val packetTemplate = DatatypeConverter.parseHexBinary(
            // RTP Header
            "80" + // V, P, X, CC
                "29" + // M, PT
                "0000" + // Seq
                "00000000" + // TS
                "cafebabe" + // SSRC
                // Header extension will be added dynamically
                // Dummy payload data
                "0000000000000000000000"
        )
    }
}

private class NonScalableAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(0),
        arrayOf(1),
        1,
        "80000180003a410180ef808680"
    )

private class TemporallyScaledPacketGenerator(
    packetsPerFrame: Int,
    encodingId: Int = 0
) : Av1PacketGenerator(
    packetsPerFrame = packetsPerFrame,
    keyframeTemplates = arrayOf(0),
    normalTemplates = arrayOf(1, 3, 2, 4),
    framesPerTimestamp = 1,
    templateDdHex = "800001800214eaa860414d141020842701df010d",
    encodingId = encodingId
)

private class ScalableAv1PacketGenerator(
    packetsPerFrame: Int,
    encodingId: Int = 0
) :
    Av1PacketGenerator(
        packetsPerFrame = packetsPerFrame,
        keyframeTemplates = arrayOf(1, 6, 11),
        normalTemplates = arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        framesPerTimestamp = 3,
        templateDdHex = "d0013481e81485214eafffaaaa863cf0430c10c302afc0aaa0063c00430010c002a000a800060000" +
            "40001d954926e082b04a0941b820ac1282503157f974000ca864330e222222eca8655304224230ec" +
            "a87753013f00b3027f016704ff02cf",
        encodingId = encodingId
    )

private class KeyScalableAv1PacketGenerator(
    packetsPerFrame: Int,
    encodingId: Int = 0
) :
    Av1PacketGenerator(
        packetsPerFrame = packetsPerFrame,
        keyframeTemplates = arrayOf(0, 5, 10),
        normalTemplates = arrayOf(1, 6, 11, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        framesPerTimestamp = 3,
        templateDdHex = "8f008581e81485214eaaaaa8000600004000100002aa80a8000600004000100002a000a80006000040" +
            "0016d549241b5524906d54923157e001974ca864330e222396eca8655304224390eca87753013f00b3027f016704ff02cf",
        encodingId = encodingId
    )

private class SingleEncodingSimulcastAv1PacketGenerator(
    packetsPerFrame: Int,
    encodingId: Int = 0
) :
    Av1PacketGenerator(
        packetsPerFrame = packetsPerFrame,
        keyframeTemplates = arrayOf(1, 6, 11),
        normalTemplates = arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        framesPerTimestamp = 3,
        templateDdHex = "c1000180081485214ea000a8000600004000100002a000a8000600004000100002a000a8000600004" +
            "0001d954926caa493655248c55fe5d00032a190cc38e58803b2a1954c10e10843b2a1dd4c01dc010803bc0218077c0434",
        allKeyframesGetStructure = true,
        encodingId = encodingId
    )

private infix fun IntRange.step(next: (Int) -> Int) =
    generateSequence(first, next).takeWhile { if (first < last) it <= last else it >= last }
