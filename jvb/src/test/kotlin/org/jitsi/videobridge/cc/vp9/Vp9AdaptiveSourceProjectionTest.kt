package org.jitsi.videobridge.cc.vp9

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.RtpLayerDesc.Companion.getIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getSidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getTidFromIndex
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.applyExtendedPictureIdDelta
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.applyTl0PicIdxDelta
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.getExtendedPictureIdDelta
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacketBuilder
import org.jitsi.rtp.rtcp.SenderInfoBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.rtp.util.isOlderTimestampThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.cc.RtpState
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.xml.bind.DatatypeConverter
import kotlin.collections.ArrayList

class Vp9AdaptiveSourceProjectionTest {
    private val logger: Logger = LoggerImpl(javaClass.name)
    private val payloadType: PayloadType = Vp9PayloadType(
        96.toByte(),
        ConcurrentHashMap(), CopyOnWriteArraySet()
    )

    @Test
    fun singlePacketProjectionTest() {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "singlePacketProjectionTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        val generator = ScalableVp9PacketGenerator(1)
        val packetInfo = generator.nextPacket()
        val packet = packetInfo.packetAs<Vp9Packet>()
        val targetIndex = getIndex(eid = 0, sid = 0, tid = 0)
        Assert.assertTrue(
            context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
        )
        context.rewriteRtp(packetInfo)
        Assert.assertEquals(10001, packet.sequenceNumber)
        Assert.assertEquals(1003000, packet.timestamp)
        Assert.assertEquals(0, packet.pictureId)
        Assert.assertEquals(0, packet.spatialLayerIndex)
        Assert.assertEquals(0, packet.temporalLayerIndex)
    }

    private fun runInOrderTest(generator: Vp9PacketGenerator, targetIndex: Int) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedPicId = 0
        var expectedTl0PicIdx = 0
        val targetSid = getSidFromIndex(targetIndex)
        val targetTid = getTidFromIndex(targetIndex)
        for (i in 0..99999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            val accepted = context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
            if (!packet.hasLayerIndices) {
                expectedTl0PicIdx = -1
            } else if (packet.isStartOfFrame && packet.spatialLayerIndex == 0 && packet.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            val endOfPicture = packet.isEndOfPicture // Save this before rewriteRtp
            if (packet.temporalLayerIndex <= targetTid &&
                (
                    packet.spatialLayerIndex == targetSid ||
                        (packet.isUpperLevelReference && packet.spatialLayerIndex < targetSid)
                    )
            ) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedPicId, packet.pictureId)
                Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                Assert.assertEquals(
                    packet.isEndOfFrame && packet.effectiveSpatialLayerIndex == targetSid,
                    packet.isMarked
                )
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }
    }

    private class ProjectedPacket internal constructor(
        val packet: Vp9Packet,
        val origSeq: Int,
        val extOrigSeq: Int,
        val nearOldest: Boolean
    )

    /** Run an out-of-order test on a single stream, randomized order except for the first packet.  */
    private fun doRunOutOfOrderTest(
        generator: Vp9PacketGenerator,
        targetIndex: Int,
        initialOrderedCount: Int,
        seed: Long
    ) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val expectedInitialTs: Long = RtpUtils.applyTimestampDelta(initialState.maxTimestamp, 3000)
        val expectedTsOffset: Long = RtpUtils.getTimestampDiff(expectedInitialTs, generator.ts)
        val targetSid = getSidFromIndex(targetIndex)
        val targetTid = getTidFromIndex(targetIndex)
        val reorderSize = 64
        val buffer = ArrayList<PacketInfo?>(reorderSize)
        for (i in 0 until reorderSize) {
            buffer.add(generator.nextPacket())
        }
        val random = Random(seed)
        var orderedCount = initialOrderedCount - 1
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext,
            payloadType,
            initialState, logger
        )
        var latestSeq = buffer[0]!!.packetAs<Vp9Packet>().sequenceNumber
        val projectedPackets = TreeMap<Int, ProjectedPacket?>()
        val origSeqIdxTracker = Rfc3711IndexTracker()
        val newSeqIdxTracker = Rfc3711IndexTracker()
        for (i in 0..99999) {
            val packetInfo = buffer[0]
            val packet = packetInfo!!.packetAs<Vp9Packet>()
            val origSeq = packet.sequenceNumber
            val origTs = packet.timestamp
            val origTl0PicIdx = packet.TL0PICIDX
            if (latestSeq isOlderThan origSeq) {
                latestSeq = origSeq
            }
            val accepted = context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
            val oldestValidSeq: Int =
                RtpUtils.applySequenceNumberDelta(
                    latestSeq,
                    -((Vp9PictureMap.PICTURE_MAP_SIZE - 1) * generator.packetsPerFrame)
                )
            if (origSeq isOlderThan oldestValidSeq && !accepted) {
                /* This is fine; packets that are too old get ignored. */
                /* Note we don't want assertFalse(accepted) here because slightly-too-old packets
                 * that are part of an existing accepted frame will be accepted.
                 */
            } else if (packet.temporalLayerIndex <= targetTid && (
                packet.spatialLayerIndex == targetSid ||
                    (packet.isUpperLevelReference && packet.spatialLayerIndex < targetSid)
                )
            ) {
                Assert.assertTrue(accepted)

                /* There's an edge condition in frame projection where a packet
                   of a frame can be projected, then the frame can be forgotten
                   for being too old, then a later packet of the frame (which is
                   just barely not too old) can be projected, at which point it
                   can potentially get assigned different sequence number/TL0PICIDX
                   values.

                   This is an unlikely enough case in real life that it's not worth
                   worrying about; but the incredibly aggressive packet randomizer
                   used by the unit tests can trigger it, so explicitly allow it.
                 */
                val nearOldest: Boolean =
                    RtpUtils.getSequenceNumberDelta(origSeq, oldestValidSeq) < generator.packetsPerFrame
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.timestamp)
                Assert.assertEquals(origTl0PicIdx, packet.TL0PICIDX)
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
        val iter: Iterator<Int> = projectedPackets.keys.iterator()
        var prevPacket = projectedPackets[iter.next()]
        while (iter.hasNext()) {
            val packet = projectedPackets[iter.next()]
            Assert.assertTrue(packet!!.origSeq isNewerThan prevPacket!!.origSeq)
            if (prevPacket.packet.timestamp isOlderTimestampThan packet.packet.timestamp) {
                Assert.assertTrue(getExtendedPictureIdDelta(prevPacket.packet.pictureId, packet.packet.pictureId) < 0)
            } else {
                Assert.assertEquals(prevPacket.packet.timestamp, packet.packet.timestamp)
                Assert.assertTrue(prevPacket.packet.pictureId == packet.packet.pictureId || packet.nearOldest)
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
    private fun runOutOfOrderTest(generator: Vp9PacketGenerator, targetIndex: Int, initialOrderedCount: Int = 1) {
        /* Seeds that have triggered problems in the past for this or VP8, plus a random one. */
        val seeds = longArrayOf(1576267371838L, 1578347926155L, 1579620018479L, System.currentTimeMillis())
        for (seed in seeds) {
            try {
                doRunOutOfOrderTest(generator, targetIndex, initialOrderedCount, seed)
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
    fun simpleNonScalableTest() {
        val generator = NonScalableVp9PacketGenerator()
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun simpleProjectionTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredProjectionTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largerFrameProjectionTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun largerFrameFilteredTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun hugeFrameTest() {
        val generator = ScalableVp9PacketGenerator(200)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun simpleKsvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2))
    }

    @Test
    fun filteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun temporalFilteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 0))
    }

    @Test
    fun spatialAndTemporalFilteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largerKsvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2))
    }

    @Test
    fun largerFilteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun largerTemporalFilteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 0))
    }

    @Test
    fun largerSpatialAndTemporalFilteredKsvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun simpleSvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2))
    }

    @Test
    fun filteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun temporalFilteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 0))
    }

    @Test
    fun spatialAndTemporalFilteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largerSvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2))
    }

    @Test
    fun largerFilteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun largerTemporalFilteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 0))
    }

    @Test
    fun largerSpatialAndTemporalFilteredSvcTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runInOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun simpleOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun largerOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largerFilteredOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun simpleKsvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2), 3)
    }

    @Test
    fun largerKsvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2), 7)
    }

    @Test
    fun filteredKsvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1, 3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2), 3)
    }

    @Test
    fun largerFilteredKsvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3, 3)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2), 7)
    }

    @Test
    fun simpleSvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2), 3)
    }

    @Test
    fun largerSvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 2, tid = 2), 7)
    }

    @Test
    fun filteredSvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(1, 3, false)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2), 3)
    }

    @Test
    fun largerFilteredSvcOutOfOrderTest() {
        val generator = ScalableVp9PacketGenerator(3, 3, false)
        runOutOfOrderTest(generator, getIndex(eid = 0, sid = 0, tid = 2), 7)
    }

    @Test
    fun slightlyDelayedKeyframeTest() {
        val generator = ScalableVp9PacketGenerator(1)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "slightlyDelayedKeyframeTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        val firstPacketInfo = generator.nextPacket()
        val firstPacket = firstPacketInfo.packetAs<Vp9Packet>()
        val targetIndex = getIndex(eid = 0, sid = 0, tid = 2)
        for (i in 0..2) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
        }
        Assert.assertTrue(
            context.accept(
                firstPacketInfo,
                getIndex(0, firstPacket.spatialLayerIndex, firstPacket.temporalLayerIndex), targetIndex
            )
        )
        context.rewriteRtp(firstPacketInfo)
        for (i in 0..9995) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertTrue(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo)
        }
    }

    @Test
    fun veryDelayedKeyframeTest() {
        val generator = ScalableVp9PacketGenerator(1)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "veryDelayedKeyframeTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        val firstPacketInfo = generator.nextPacket()
        val firstPacket = firstPacketInfo.packetAs<Vp9Packet>()
        val targetIndex = getIndex(eid = 0, sid = 0, tid = 2)
        for (i in 0..3) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
        }
        Assert.assertFalse(
            context.accept(
                firstPacketInfo,
                getIndex(0, firstPacket.spatialLayerIndex, firstPacket.temporalLayerIndex), targetIndex
            )
        )
        for (i in 0..9) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
        }
        generator.requestKeyframe()
        for (i in 0..9995) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertTrue(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo)
        }
    }

    @Test
    fun delayedPartialKeyframeTest() {
        val generator = ScalableVp9PacketGenerator(3)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "delayedPartialKeyframeTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        val firstPacketInfo = generator.nextPacket()
        val firstPacket = firstPacketInfo.packetAs<Vp9Packet>()
        val targetIndex = getIndex(eid = 0, sid = 0, tid = 2)
        var lowestSeq = Integer.MAX_VALUE
        for (i in 0..10) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertTrue(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo)
            Assert.assertTrue(packet.sequenceNumber > 10001)
            lowestSeq = minOf(lowestSeq, packet.sequenceNumber)
        }

        Assert.assertTrue(
            context.accept(
                firstPacketInfo,
                getIndex(0, firstPacket.spatialLayerIndex, firstPacket.temporalLayerIndex),
                targetIndex
            )
        )
        context.rewriteRtp(firstPacketInfo)
        Assert.assertEquals(lowestSeq - 1, firstPacket.sequenceNumber)

        for (i in 0..9980) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            Assert.assertTrue(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo)
        }
    }

    @Test
    fun twoStreamsNoSwitchingTest() {
        val generator1 = ScalableVp9PacketGenerator(3)
        val generator2 = ScalableVp9PacketGenerator(3)
        generator2.ssrc = 0xdeadbeefL
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "twoStreamsNoSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        val targetIndex = getIndex(eid = 1, sid = 0, tid = 2)
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        for (i in 0..9999) {
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Vp9Packet>()
            Assert.assertTrue(
                context.accept(
                    packetInfo1,
                    getIndex(1, packet1.spatialLayerIndex, packet1.temporalLayerIndex), targetIndex
                )
            )
            val packetInfo2 = generator2.nextPacket()
            val packet2 = packetInfo2.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo2,
                    getIndex(0, packet2.spatialLayerIndex, packet2.temporalLayerIndex), targetIndex
                )
            )
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
        val generator1 = ScalableVp9PacketGenerator(3)
        val generator2 = ScalableVp9PacketGenerator(3)
        generator2.ssrc = 0xdeadbeefL
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "twoStreamsSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedPicId = 0
        var expectedTl0PicIdx = 0
        var targetIndex = getIndex(eid = 0, sid = 0, tid = 2)

        /* Start by wanting spatial layer 0 */
        for (i in 0..899) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Vp9Packet>()
            if (packet1.isStartOfFrame && packet1.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            Assert.assertTrue(
                context.accept(
                    packetInfo1,
                    getIndex(
                        0,
                        packet1.spatialLayerIndex, packet1.temporalLayerIndex
                    ),
                    targetIndex
                )
            )
            context.rewriteRtp(packetInfo1)
            Assert.assertTrue(context.rewriteRtcp(srPacket1))
            Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
            Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            val packet2 = packetInfo2.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo2,
                    getIndex(
                        1,
                        packet2.spatialLayerIndex, packet2.temporalLayerIndex
                    ),
                    targetIndex
                )
            )
            Assert.assertFalse(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(expectedSeq, packet1.sequenceNumber)
            Assert.assertEquals(expectedTs, packet1.timestamp)
            Assert.assertEquals(expectedPicId, packet1.pictureId)
            Assert.assertEquals(expectedTl0PicIdx, packet1.TL0PICIDX)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet1.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }

        /* Switch to wanting spatial layer 1, but don't send a keyframe. We should stay at the higher layer. */
        targetIndex = getIndex(eid = 1, sid = 0, tid = 2)
        for (i in 0..89) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Vp9Packet>()
            if (packet1.isStartOfFrame && packet1.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            Assert.assertTrue(
                context.accept(
                    packetInfo1,
                    getIndex(0, packet1.spatialLayerIndex, packet1.temporalLayerIndex),
                    targetIndex
                )
            )
            context.rewriteRtp(packetInfo1)
            Assert.assertTrue(context.rewriteRtcp(srPacket1))
            Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
            Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            val packet2 = packetInfo2.packetAs<Vp9Packet>()
            Assert.assertFalse(
                context.accept(
                    packetInfo2,
                    getIndex(1, packet2.spatialLayerIndex, packet2.temporalLayerIndex),
                    targetIndex
                )
            )
            Assert.assertFalse(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(expectedSeq, packet1.sequenceNumber)
            Assert.assertEquals(expectedTs, packet1.timestamp)
            Assert.assertEquals(expectedPicId, packet1.pictureId)
            Assert.assertEquals(expectedTl0PicIdx, packet1.TL0PICIDX)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet1.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }
        generator1.requestKeyframe()
        generator2.requestKeyframe()

        /* After a keyframe we should accept spatial layer 1 */for (i in 0..8999) {
            val srPacket1 = generator1.srPacket
            val packetInfo1 = generator1.nextPacket()
            val packet1 = packetInfo1.packetAs<Vp9Packet>()
            if (i == 0 && packet1.isStartOfFrame && packet1.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }

            /* We will cut off the layer 0 keyframe after 1 packet, once we see the layer 1 keyframe. */
            Assert.assertEquals(
                i == 0,
                context.accept(
                    packetInfo1,
                    getIndex(0, packet1.spatialLayerIndex, packet1.temporalLayerIndex), targetIndex
                )
            )
            Assert.assertEquals(i == 0, context.rewriteRtcp(srPacket1))
            if (i == 0) {
                context.rewriteRtp(packetInfo1)
                Assert.assertEquals(packet1.ssrc, srPacket1.senderSsrc)
                Assert.assertEquals(packet1.timestamp, srPacket1.senderInfo.rtpTimestamp)
            }
            val srPacket2 = generator2.srPacket
            val packetInfo2 = generator2.nextPacket()
            val packet2 = packetInfo2.packetAs<Vp9Packet>()
            if (packet2.isStartOfFrame && packet2.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            Assert.assertTrue(
                context.accept(
                    packetInfo2,
                    getIndex(1, packet2.spatialLayerIndex, packet2.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo2)
            Assert.assertTrue(context.rewriteRtcp(srPacket2))
            Assert.assertEquals(packet2.ssrc, srPacket2.senderSsrc)
            Assert.assertEquals(packet2.timestamp, srPacket2.senderInfo.rtpTimestamp)
            if (i == 0) {
                /* We leave a 2-packet gap for the layer 0 keyframe. */
                expectedSeq += 2
                /* ts will advance by an extra 3000 samples for the extra frame. */
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                /* pid id and tl0picidx will advance by 1 for the extra keyframe. */
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
            Assert.assertEquals(expectedSeq, packet2.sequenceNumber)
            Assert.assertEquals(expectedTs, packet2.timestamp)
            Assert.assertEquals(expectedPicId, packet2.pictureId)
            Assert.assertEquals(expectedTl0PicIdx, packet2.TL0PICIDX)
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            if (packet2.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }
    }

    @Test
    fun temporalLayerSwitchingTest() {
        val generator = ScalableVp9PacketGenerator(3)
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = "temporalLayerSwitchingTest"
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext, payloadType,
            initialState, logger
        )
        var targetTid = 0
        var decodableTid = 0
        var targetIndex = getIndex(0, 0, targetTid)
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedPicId = 0
        var expectedTl0PicIdx = 0
        for (i in 0..9999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            val accepted = context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
            if (packet.isStartOfFrame && packet.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            if (accepted) {
                if (decodableTid < packet.temporalLayerIndex) {
                    decodableTid = packet.temporalLayerIndex
                }
            } else {
                if (decodableTid > packet.temporalLayerIndex - 1) {
                    decodableTid = packet.temporalLayerIndex - 1
                }
            }
            if (packet.temporalLayerIndex <= decodableTid) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedPicId, packet.pictureId)
                Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (packet.isEndOfFrame) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
                if (i % 97 == 0) /* Prime number so it's out of sync with packet cycles. */ {
                    targetTid = (targetTid + 2) % 3
                    targetIndex = getIndex(0, 0, targetTid)
                }
            }
        }
    }

    private fun runLargeDropoutTest(generator: Vp9PacketGenerator, targetIndex: Int) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext,
            payloadType,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedPicId = 0
        var expectedTl0PicIdx = 0
        val targetSid = getSidFromIndex(targetIndex)
        val targetTid = getTidFromIndex(targetIndex)
        for (i in 0..999) {
            val packetInfo = generator.nextPacket()
            val packet = packetInfo.packetAs<Vp9Packet>()
            val accepted = context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
            if (packet.isStartOfFrame && packet.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            val endOfPicture = packet.isEndOfPicture
            if (packet.temporalLayerIndex <= targetTid &&
                (
                    packet.spatialLayerIndex == targetSid ||
                        (packet.isUpperLevelReference && packet.spatialLayerIndex < targetSid)
                    )
            ) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedPicId, packet.pictureId)
                Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
            } else {
                Assert.assertFalse(accepted)
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }
        for (gap in 64..65536 step { it * 2 }) {
            for (i in 0 until gap) {
                generator.nextPacket()
            }
            var packetInfo: PacketInfo
            var packet: Vp9Packet
            do {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
            } while (packet.temporalLayerIndex > targetIndex)
            Assert.assertTrue(
                context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
            )
            context.rewriteRtp(packetInfo)

            /* Allow any values after a gap. */
            expectedSeq = RtpUtils.applySequenceNumberDelta(packet.sequenceNumber, 1)
            expectedTs = packet.timestamp
            expectedPicId = packet.pictureId
            expectedTl0PicIdx = packet.TL0PICIDX
            if (packet.isEndOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
            for (i in 0..999) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
                if (packet.isStartOfFrame && packet.temporalLayerIndex == 0) {
                    expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
                }
                val endOfPicture = packet.isEndOfPicture
                if (packet.temporalLayerIndex <= targetIndex) {
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                    Assert.assertEquals(expectedTs, packet.timestamp)
                    Assert.assertEquals(expectedPicId, packet.pictureId)
                    Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                } else {
                    Assert.assertFalse(accepted)
                }
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                    expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
                }
            }
        }
    }

    @Test
    fun largeDropoutTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runLargeDropoutTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredLargeDropoutTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runLargeDropoutTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largeFrameDropoutTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runLargeDropoutTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredLargeFrameDropoutTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runLargeDropoutTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    private fun runSourceSuspensionTest(generator: Vp9PacketGenerator, targetIndex: Int) {
        val diagnosticContext = DiagnosticContext()
        diagnosticContext["test"] = Thread.currentThread().stackTrace[2].methodName
        val initialState = RtpState(1, 10000, 1000000)
        val context = Vp9AdaptiveSourceProjectionContext(
            diagnosticContext,
            payloadType,
            initialState, logger
        )
        var expectedSeq = 10001
        var expectedTs: Long = 1003000
        var expectedPicId = 0
        var expectedTl0PicIdx = 0
        val targetSid = getSidFromIndex(targetIndex)
        val targetTid = getTidFromIndex(targetIndex)

        var packetInfo: PacketInfo
        var packet: Vp9Packet

        var lastPacketAccepted = false
        var lastPidAccepted = -1

        for (i in 0..999) {
            packetInfo = generator.nextPacket()
            packet = packetInfo.packetAs()
            val accepted = context.accept(
                packetInfo,
                getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
            )
            if (packet.isStartOfFrame && packet.temporalLayerIndex == 0) {
                expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
            }
            val endOfPicture = packet.isEndOfPicture
            if (packet.temporalLayerIndex <= targetTid &&
                (
                    packet.spatialLayerIndex == targetSid ||
                        (packet.isUpperLevelReference && packet.spatialLayerIndex < targetSid)
                    )
            ) {
                Assert.assertTrue(accepted)
                context.rewriteRtp(packetInfo)
                Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                Assert.assertEquals(expectedTs, packet.timestamp)
                Assert.assertEquals(expectedPicId, packet.pictureId)
                Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                lastPacketAccepted = true
                lastPidAccepted = packet.pictureId
            } else {
                Assert.assertFalse(accepted)
                lastPacketAccepted = false
            }
            if (endOfPicture) {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
            }
        }
        for (suspended in 64..65536 step { it * 2 }) {
            /* If the last frame was accepted, finish the current frame if this generator is creating multi-packet
                frames. */
            if (lastPacketAccepted) {
                while (generator.packetOfFrame != 0) {
                    packetInfo = generator.nextPacket()
                    packet = packetInfo.packetAs()
                    val accepted = context.accept(
                        packetInfo,
                        getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                    )
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                    if (packet.isEndOfPicture) {
                        expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                        expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
                    }
                }
            }
            /* Turn the source off for a time. */
            for (i in 0 until suspended) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), RtpLayerDesc.SUSPENDED_INDEX
                )
                Assert.assertFalse(accepted)
                if (packet.isEndOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }

            /* Switch back to wanting [targetIndex], but don't send a keyframe for a while.
             * Should still be dropped. */
            for (i in 0 until 30) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
                Assert.assertFalse(accepted)
                if (packet.isEndOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }

            /* Request a keyframe.  Will be sent as of the next frame. */
            generator.requestKeyframe()
            /* If this generator is creating multi-packet frames, finish the previous frame. */
            while (generator.packetOfFrame != 0) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
                Assert.assertFalse(accepted)
                if (packet.isEndOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                }
            }
            expectedPicId = applyExtendedPictureIdDelta(lastPidAccepted, 1)

            for (i in 0..999) {
                packetInfo = generator.nextPacket()
                packet = packetInfo.packetAs()
                val accepted = context.accept(
                    packetInfo,
                    getIndex(0, packet.spatialLayerIndex, packet.temporalLayerIndex), targetIndex
                )
                if (packet.isStartOfFrame && packet.temporalLayerIndex == 0) {
                    expectedTl0PicIdx = applyTl0PicIdxDelta(expectedTl0PicIdx, 1)
                }
                val endOfPicture = packet.isEndOfPicture
                if (packet.temporalLayerIndex <= targetTid &&
                    (
                        packet.spatialLayerIndex == targetSid ||
                            (packet.isUpperLevelReference && packet.spatialLayerIndex < targetSid)
                        )
                ) {
                    Assert.assertTrue(accepted)
                    context.rewriteRtp(packetInfo)
                    Assert.assertEquals(expectedSeq, packet.sequenceNumber)
                    Assert.assertEquals(expectedTs, packet.timestamp)
                    Assert.assertEquals(expectedPicId, packet.pictureId)
                    Assert.assertEquals(expectedTl0PicIdx, packet.TL0PICIDX)
                    expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1)
                    lastPacketAccepted = true
                    lastPidAccepted = packet.pictureId
                } else {
                    Assert.assertFalse(accepted)
                    lastPacketAccepted = false
                }
                if (endOfPicture) {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000)
                    expectedPicId = applyExtendedPictureIdDelta(expectedPicId, 1)
                }
            }
        }
    }

    @Test
    fun sourceSuspensionTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runSourceSuspensionTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredSourceSuspensionTest() {
        val generator = ScalableVp9PacketGenerator(1)
        runSourceSuspensionTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    @Test
    fun largeFrameSourceSuspensionTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runSourceSuspensionTest(generator, getIndex(eid = 0, sid = 0, tid = 2))
    }

    @Test
    fun filteredLargeFrameSourceSuspensionTest() {
        val generator = ScalableVp9PacketGenerator(3)
        runSourceSuspensionTest(generator, getIndex(eid = 0, sid = 0, tid = 0))
    }

    private abstract class Vp9PacketGenerator {
        open val packetsPerFrame: Int = 1
        abstract val ts: Long
        var ssrc: Long = 0xcafebabeL

        abstract fun reset()
        abstract fun nextPacket(): PacketInfo
        abstract fun requestKeyframe()
        abstract val packetOfFrame: Int

        init {
            reset()
        }

        companion object {
            val baseReceivedTime = Instant.ofEpochMilli(1577836800000L) /* 2020-01-01 00:00:00 UTC */
        }
    }

    private class NonScalableVp9PacketGenerator() : Vp9PacketGenerator() {
        private var seq = 0
        override var ts: Long = 0
            private set
        private var picId = 0
        override var packetOfFrame = 0
        private var keyframePicture = false
        private var keyframeRequested = false
        private var frameCount = 0
        private var receivedTime = baseReceivedTime

        override fun reset() {
            val useRandom = true // switch off to ease debugging
            val seed = System.currentTimeMillis()
            val random = Random(seed)
            seq = if (useRandom) random.nextInt() % 0x10000 else 0
            ts = if (useRandom) random.nextLong() % 0x100000000L else 0
            picId = 0
            packetOfFrame = 0
            keyframePicture = true
            keyframeRequested = false
            ssrc = 0xcafebabeL
            frameCount = 0
            receivedTime = baseReceivedTime
        }

        override fun nextPacket(): PacketInfo {
            val startOfFrame = packetOfFrame == 0
            val endOfFrame = packetOfFrame == packetsPerFrame - 1
            val buffer = vp9PacketTemplate.clone()
            val rtpPacket = RtpPacket(buffer, 0, buffer.size)
            rtpPacket.ssrc = ssrc
            rtpPacket.sequenceNumber = seq
            rtpPacket.timestamp = ts

            /* Do VP9 manipulations on buffer before constructing Vp9Packet, because
               Vp9Packet computes values at construct-time. */
            DePacketizer.VP9PayloadDescriptor.setStartOfFrame(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, startOfFrame
            )
            DePacketizer.VP9PayloadDescriptor.setEndOfFrame(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, endOfFrame
            )
            DePacketizer.VP9PayloadDescriptor.setInterPicturePredicted(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, !keyframePicture
            )

            rtpPacket.isMarked = endOfFrame
            val vp9Packet = rtpPacket.toOtherType(::Vp9Packet)

            /* Make sure our manipulations of the raw buffer were correct. */
            Assert.assertEquals(startOfFrame, vp9Packet.isStartOfFrame)
            Assert.assertEquals(endOfFrame, vp9Packet.isEndOfFrame)
            Assert.assertEquals(!keyframePicture, vp9Packet.isInterPicturePredicted)
            Assert.assertEquals(false, vp9Packet.hasLayerIndices)
            Assert.assertEquals(false, vp9Packet.isSwitchingUpPoint)
            Assert.assertEquals(false, vp9Packet.usesInterLayerDependency)
            Assert.assertEquals(keyframePicture, vp9Packet.isKeyframe)

            vp9Packet.pictureId = picId
            val info = PacketInfo(vp9Packet)
            info.receivedTime = receivedTime
            seq = RtpUtils.applySequenceNumberDelta(seq, 1)
            if (endOfFrame) {
                packetOfFrame = 0
                ts = RtpUtils.applyTimestampDelta(ts, 3000)
                picId = applyExtendedPictureIdDelta(picId, 1)
                keyframePicture = keyframeRequested
                keyframeRequested = false
                frameCount++
                receivedTime = baseReceivedTime + Duration.ofMillis(frameCount * 100L / 3)
            } else {
                packetOfFrame++
            }
            return info
        }
        override fun requestKeyframe() {
            if (packetOfFrame == 0) {
                keyframePicture = true
                keyframeRequested = false
            } else {
                keyframeRequested = true
            }
        }
        companion object {
            private val vp9PacketTemplate = DatatypeConverter.parseHexBinary( /* RTP Header */
                "80" + /* V, P, X, CC */
                    "60" + /* M, PT */
                    "0000" + /* Seq */
                    "00000000" + /* TS */
                    "cafebabe" + /* SSRC */
                    /* VP9 Payload descriptor */
                    // I=1,P=0,L=0,F=0,B=1,E=0,V=0,Z=0
                    "88" +
                    // M=1,PID=0x653e=25918
                    "e53e" +
                    /* TODO: Add SS if necessary.  Not currently parsed by the source projection context. */
                    // Dummy payload data
                    "000000"
            )
        }
    }

    private class ScalableVp9PacketGenerator(
        override val packetsPerFrame: Int,
        val numLayers: Int = 1,
        val isKsvc: Boolean = true
    ) :
        Vp9PacketGenerator() {
        private var seq = 0
        override var ts: Long = 0
            private set
        private var picId = 0
        private var tl0picidx = 0
        override var packetOfFrame = 0
        private var keyframePicture = false
        private var keyframeRequested = false
        private var sid = 0
        private var tidCycle = 0
        private var packetCount = 0
        private var octetCount = 0
        private var frameCount = 0
        private var receivedTime = baseReceivedTime
        override fun reset() {
            val useRandom = true // switch off to ease debugging
            val seed = System.currentTimeMillis()
            val random = Random(seed)
            seq = if (useRandom) random.nextInt() % 0x10000 else 0
            ts = if (useRandom) random.nextLong() % 0x100000000L else 0
            picId = 0
            tl0picidx = 0
            packetOfFrame = 0
            keyframePicture = true
            keyframeRequested = false
            sid = 0
            tidCycle = 0
            ssrc = 0xcafebabeL
            packetCount = 0
            octetCount = 0
            frameCount = 0
            receivedTime = baseReceivedTime
        }

        override fun nextPacket(): PacketInfo {
            val tid = when (tidCycle % 4) {
                0 -> 0
                2 -> 1
                1, 3 -> 2
                else -> {
                    assert(false /* Math is broken */)
                    -1
                }
            }
            val startOfFrame = packetOfFrame == 0
            val endOfFrame = packetOfFrame == packetsPerFrame - 1
            val startOfPicture = startOfFrame && sid == 0
            val endOfPicture = endOfFrame && sid == numLayers - 1
            if (startOfPicture && tid == 0) {
                tl0picidx = applyTl0PicIdxDelta(tl0picidx, 1)
            }
            val buffer = vp9SvcPacketTemplate.clone()
            val rtpPacket = RtpPacket(buffer, 0, buffer.size)
            rtpPacket.ssrc = ssrc
            rtpPacket.sequenceNumber = seq
            rtpPacket.timestamp = ts

            /* Do VP9 manipulations on buffer before constructing Vp9Packet, because
               Vp9Packet computes values at construct-time. */
            DePacketizer.VP9PayloadDescriptor.setStartOfFrame(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, startOfFrame
            )
            DePacketizer.VP9PayloadDescriptor.setEndOfFrame(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, endOfFrame
            )
            DePacketizer.VP9PayloadDescriptor.setInterPicturePredicted(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, !keyframePicture
            )
            DePacketizer.VP9PayloadDescriptor.setUpperLevelReference(
                rtpPacket.buffer,
                rtpPacket.payloadOffset, rtpPacket.payloadLength, sid != numLayers - 1
            )

            Assert.assertTrue(
                DePacketizer.VP9PayloadDescriptor.setLayerIndices(
                    rtpPacket.buffer,
                    rtpPacket.payloadOffset, rtpPacket.payloadLength, sid, tid, tid > 0,
                    sid > 0 && (isKsvc || keyframePicture)
                )
            )

            rtpPacket.isMarked = endOfPicture
            val vp9Packet = rtpPacket.toOtherType(::Vp9Packet)

            /* Make sure our manipulations of the raw buffer were correct. */
            Assert.assertEquals(startOfFrame, vp9Packet.isStartOfFrame)
            Assert.assertEquals(endOfFrame, vp9Packet.isEndOfFrame)
            Assert.assertEquals(endOfPicture, vp9Packet.isEndOfPicture)
            Assert.assertEquals(!keyframePicture, vp9Packet.isInterPicturePredicted)
            Assert.assertEquals(sid != numLayers - 1, vp9Packet.isUpperLevelReference)
            Assert.assertEquals(sid, vp9Packet.spatialLayerIndex)
            Assert.assertEquals(tid, vp9Packet.temporalLayerIndex)
            Assert.assertEquals(tid > 0, vp9Packet.isSwitchingUpPoint)
            Assert.assertEquals(sid > 0 && (isKsvc || keyframePicture), vp9Packet.usesInterLayerDependency)
            Assert.assertEquals(keyframePicture && sid == 0, vp9Packet.isKeyframe)

            vp9Packet.pictureId = picId
            vp9Packet.TL0PICIDX = tl0picidx
            val info = PacketInfo(vp9Packet)
            info.receivedTime = receivedTime
            seq = RtpUtils.applySequenceNumberDelta(seq, 1)
            packetCount++
            octetCount += vp9Packet.length
            if (endOfFrame) {
                packetOfFrame = 0
                if (endOfPicture) {
                    sid = 0
                } else {
                    sid++
                }
            } else {
                packetOfFrame++
            }
            if (endOfPicture) {
                ts = RtpUtils.applyTimestampDelta(ts, 3000)
                picId = applyExtendedPictureIdDelta(picId, 1)
                tidCycle++
                keyframePicture = keyframeRequested
                keyframeRequested = false
                if (keyframePicture) {
                    tidCycle = 0
                }
                frameCount++
                receivedTime = baseReceivedTime + Duration.ofMillis(frameCount * 100L / 3)
            }
            return info
        }

        override fun requestKeyframe() {
            if (packetOfFrame == 0) {
                keyframePicture = true
                keyframeRequested = false
                tidCycle = 0
            } else {
                keyframeRequested = true
            }
        }

        val srPacket: RtcpSrPacket
            get() {
                val srPacketBuilder = RtcpSrPacketBuilder()
                srPacketBuilder.rtcpHeader.senderSsrc = ssrc
                val siBuilder = srPacketBuilder.senderInfo
                setSIBuilderNtp(srPacketBuilder.senderInfo, receivedTime.toEpochMilli())
                siBuilder.rtpTimestamp = ts
                siBuilder.sendersOctetCount = packetCount.toLong()
                siBuilder.sendersOctetCount = octetCount.toLong()
                return srPacketBuilder.build()
            }

        companion object {
            private val vp9SvcPacketTemplate = DatatypeConverter.parseHexBinary( /* RTP Header */
                "80" + /* V, P, X, CC */
                    "60" + /* M, PT */
                    "0000" + /* Seq */
                    "00000000" + /* TS */
                    "cafebabe" + /* SSRC */
                    /* VP9 Payload descriptor */
                    // I=1,P=0,L=1,F=0,B=1,E=0,V=0,Z=0
                    "a8" +
                    // M=1,PID=0x653e=25918
                    "e53e" +
                    // TID=0,U=0,SID=0,D=0
                    "00" +
                    // TL0PICIDX=0x5b=91
                    "5b" +
                    /* TODO: Add SS if necessary.  Not currently parsed by the source projection context. */
                    // Dummy payload data
                    "000000"
            )
            /* TODO: move this to jitsi-rtp */
            fun setSIBuilderNtp(siBuilder: SenderInfoBuilder, wallTime: Long) {
                val JAVA_TO_NTP_EPOCH_OFFSET_SECS = 2208988800L
                val wallSecs = wallTime / 1000
                val wallMs = wallTime % 1000
                siBuilder.ntpTimestampMsw = wallSecs + JAVA_TO_NTP_EPOCH_OFFSET_SECS
                siBuilder.ntpTimestampLsw = wallMs * (1L shl 32) / 1000
            }
        }
    }
}

private infix fun IntRange.step(next: (Int) -> Int) =
    generateSequence(first, next).takeWhile { if (first < last) it <= last else it >= last }
