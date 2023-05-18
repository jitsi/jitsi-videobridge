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
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getIndex
import org.jitsi.nlj.util.Rfc3711IndexTracker
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
            initialState, logger
        )
        val generator = ScalableAv1PacketGenerator(1)
        val packetInfo = generator.nextPacket()
        val packet = packetInfo.packetAs<Av1DDPacket>()
        val packetIndices = packet.layerIds.map { getIndex(0, it) }
        val targetIndex = getIndex(eid = 0, dt = 0)
        Assert.assertTrue(
            context.accept(packetInfo, packetIndices, targetIndex)
        )
        context.rewriteRtp(packetInfo)
        Assert.assertEquals(10001, packet.sequenceNumber)
        Assert.assertEquals(1003000, packet.timestamp)
        Assert.assertEquals(0, packet.frameNumber)
        Assert.assertEquals(0, packet.frameInfo?.spatialId)
        Assert.assertEquals(0, packet.frameInfo?.temporalId)
    }

    private fun runInOrderTest(
        generator: Av1PacketGenerator,
        targetIndex: Int,
        expectAccept: (FrameInfo) -> Boolean
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Av1DDAdaptiveSourceProjectionContext(
            diagnosticContext,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedFrameNumber = 0
        for (i in 0..99999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val frameInfo = packet.frameInfo!!
            val packetIndices = packet.layerIds.map { getIndex(0, it) }

            val accepted = context.accept(
                packetInfo,
                packetIndices, targetIndex
            )
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
        val extOrigSeq: Int,
        val nearOldest: Boolean
    )

    /** Run an out-of-order test on a single stream, randomized order except for the first packet.  */
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
            initialState, logger
        )
        var latestSeq = buffer[0].packetAs<Av1DDPacket>().sequenceNumber
        val projectedPackets = TreeMap<Int, ProjectedPacket?>()
        val origSeqIdxTracker = Rfc3711IndexTracker()
        val newSeqIdxTracker = Rfc3711IndexTracker()
        val frameNumsDropped = HashSet<Int>()
        val droppedFrameNumsIndexTracker = Rfc3711IndexTracker()
        for (i in 0..99999) {
            val packetInfo = buffer[0]
            val packet = packetInfo.packetAs<Av1DDPacket>()
            val origSeq = packet.sequenceNumber
            val origTs = packet.timestamp
            if (latestSeq isOlderThan origSeq) {
                latestSeq = origSeq
            }
            val frameInfo = packet.frameInfo!!
            val packetIndices = packet.layerIds.map { getIndex(0, it) }

            val accepted = context.accept(packetInfo, packetIndices, targetIndex)
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
                frameNumsDropped.add(droppedFrameNumsIndexTracker.update(packet.frameNumber))
            } else if (expectAccept(frameInfo)
            ) {
                Assert.assertTrue(accepted)

                /* There's an edge condition in frame projection where a packet
                   of a frame can be projected, then the frame can be forgotten
                   for being too old, then a later packet of the frame (which is
                   just barely not too old) can be projected, at which point it
                   can potentially get assigned different sequence number
                   values.

                   This is an unlikely enough case in real life that it's not worth
                   worrying about; but the incredibly aggressive packet randomizer
                   used by the unit tests can trigger it, so explicitly allow it.
                 */
                val nearOldest: Boolean =
                    RtpUtils.getSequenceNumberDelta(origSeq, oldestValidSeq) < generator.packetsPerFrame
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.timestamp)
                val newSeq = packet.sequenceNumber
                val extNewSeq = newSeqIdxTracker.update(newSeq)
                val extOrigSeq = origSeqIdxTracker.update(origSeq)
                Assert.assertFalse(projectedPackets.containsKey(extNewSeq))
                projectedPackets[extNewSeq] = ProjectedPacket(packet, origSeq, extOrigSeq, nearOldest)
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
        val frameNumsSeen = HashSet<Int>()

        /* Add packets that weren't added yet, or that were dropped for being too old, to frameNumsSeen. */
        frameNumsSeen.addAll(frameNumsDropped)
        buffer.forEach {
            frameNumsSeen.add(droppedFrameNumsIndexTracker.update(it.packetAs<Av1DDPacket>().frameNumber))
        }

        val frameNumTracker = Rfc3711IndexTracker()
        val iter = projectedPackets.keys.iterator()
        var prevPacket = projectedPackets[iter.next()]
        frameNumsSeen.add(frameNumTracker.update(prevPacket!!.packet.frameNumber))
        while (iter.hasNext()) {
            val packet = projectedPackets[iter.next()]
            Assert.assertTrue(packet!!.origSeq isNewerThan prevPacket!!.origSeq)
            val frameNumIdx = frameNumTracker.update(packet.packet.frameNumber)
            frameNumsSeen.add(frameNumIdx)
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
                    RtpUtils.applySequenceNumberDelta(packet.packet.sequenceNumber, - 1)
                ) {
                    Assert.assertTrue(prevPacket.packet.isEndOfFrame)
                }
            } else {
                if (prevPacket.packet.sequenceNumber ==
                    RtpUtils.applySequenceNumberDelta(packet.packet.sequenceNumber, - 1)
                ) {
                    Assert.assertEquals(prevPacket.packet.frameNumber, packet.packet.frameNumber)
                    Assert.assertEquals(prevPacket.packet.timestamp, packet.packet.timestamp)
                }
            }
            packet.packet.frameInfo?.fdiff?.forEach {
                Assert.assertTrue(frameNumsSeen.contains(frameNumIdx - it))
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
        val seeds = longArrayOf(1576267371838L, 1578347926155L, 1579620018479L, System.currentTimeMillis())
        for (seed in seeds) {
            try {
                doRunOutOfOrderTest(generator, targetIndex, initialOrderedCount, seed, expectAccept)
            } catch (e: Throwable) {
                logger.error(
                    "Exception thrown in randomized test, seed = $seed", e
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
}

private open class Av1PacketGenerator(
    val packetsPerFrame: Int,
    val keyframeTemplates: Array<Int>,
    val normalTemplates: Array<Int>,
    val framesPerTimestamp: Int, /* Equivalent to number of layers */
    templateDdHex: String,
    val allKeyframesGetStructure: Boolean = false
) {
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

    fun nextPacket(): PacketInfo {
        val startOfFrame = packetOfFrame == 0
        val endOfFrame = packetOfFrame == packetsPerFrame - 1
        val startOfPicture = startOfFrame && frameOfPicture == 0
        val endOfPicture = endOfFrame && frameOfPicture == framesPerTimestamp - 1

        val templateId = (if (keyframePicture) keyframeTemplates[templateIdx] else normalTemplates[templateIdx]) +
            structure.templateIdOffset

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
            if (keyframePicture && startOfFrame && (startOfPicture || allKeyframesGetStructure))
                structure else null,
            activeDecodeTargetsBitmask = null,
            customDtis = null,
            customFdiffs = null,
            customChains = null,
            structure = structure
        )

        val ext = rtpPacket.addHeaderExtension(AV1_DD_HEADER_EXTENSION_ID, dd.encodedLength)
        dd.write(ext)
        rtpPacket.encodeHeaderExtensions()

        val av1Packet = Av1DDPacket(rtpPacket, AV1_DD_HEADER_EXTENSION_ID, structure)

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
        keyframeRequested = true
    }

    init {
        reset()
    }

    companion object {
        val baseReceivedTime: Instant = Instant.ofEpochMilli(1577836800000L) /* 2020-01-01 00:00:00 UTC */

        const val AV1_DD_HEADER_EXTENSION_ID = 11

        private val packetTemplate = DatatypeConverter.parseHexBinary( /* RTP Header */
            "80" + /* V, P, X, CC */
                "29" + /* M, PT */
                "0000" + /* Seq */
                "00000000" + /* TS */
                "cafebabe" + /* SSRC */
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

private class TemporallyScaledPacketGenerator(packetsPerFrame: Int) : Av1PacketGenerator(
    packetsPerFrame,
    arrayOf(0),
    arrayOf(1, 3, 2, 4),
    1,
    "800001800214eaa860414d141020842701df010d"
)

private class ScalableAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(1, 6, 11),
        arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        3,
        "d0013481e81485214eafffaaaa863cf0430c10c302afc0aaa0063c00430010c002a000a800060000" +
            "40001d954926e082b04a0941b820ac1282503157f974000ca864330e222222eca8655304224230ec" +
            "a87753013f00b3027f016704ff02cf"
    )

private class KeyScalableAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(0, 5, 10),
        arrayOf(1, 6, 11, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        3,
        "8f008581e81485214eaaaaa8000600004000100002aa80a8000600004000100002a000a80006000040" +
            "0016d549241b5524906d54923157e001974ca864330e222396eca8655304224390eca87753013f00b3027f016704ff02cf"
    )

private class SingleEncodingSimulcastAv1PacketGenerator(
    packetsPerFrame: Int
) :
    Av1PacketGenerator(
        packetsPerFrame,
        arrayOf(1, 6, 11),
        arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
        3,
        "c1000180081485214ea000a8000600004000100002a000a8000600004000100002a000a8000600004" +
            "0001d954926caa493655248c55fe5d00032a190cc38e58803b2a1954c10e10843b2a1dd4c01dc010803bc0218077c0434",
        allKeyframesGetStructure = true
    )
