package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.*;
import org.jitsi.nlj.codec.vpx.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.junit.*;

import javax.xml.bind.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class VP8AdaptiveSourceProjectionTest
{
    private final Logger logger = new LoggerImpl(getClass().getName());
    private final PayloadType payloadType = new Vp8PayloadType((byte)96,
        new ConcurrentHashMap<>(), new CopyOnWriteArraySet<>());

    @Test
    public void singlePacketProjectionTest() throws RewriteException
    {
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "singlePacketProjectionTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        PacketInfo packetInfo = generator.nextPacket();
        Vp8Packet packet = packetInfo.packetAs();

        int targetIndex = RtpLayerDesc.getIndex(0, 0, 0);

        assertTrue(context.accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex));

        context.rewriteRtp(packetInfo);

        assertEquals(10001, packet.getSequenceNumber());
        assertEquals(1003000, packet.getTimestamp());
        assertEquals(0, packet.getPictureId());
        assertEquals(0, packet.getTemporalLayerIndex());
    }

    private void runInOrderTest(Vp8PacketGenerator generator, int targetTid)
        throws RewriteException
    {
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        int targetIndex = RtpLayerDesc.getIndex(0, 0, targetTid);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        for (int i = 0; i < 100000; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            boolean accepted = context.accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex);

            if (packet.isStartOfFrame() && packet.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            if (packet.getTemporalLayerIndex() <= targetIndex)
            {
                assertTrue(accepted);

                context.rewriteRtp(packetInfo);

                assertEquals(expectedSeq, packet.getSequenceNumber());
                assertEquals(expectedTs, packet.getTimestamp());
                assertEquals(expectedPicId, packet.getPictureId());
                assertEquals(expectedTl0PicIdx, packet.getTL0PICIDX());

                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
                if (packet.isEndOfFrame())
                {
                    expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
                }
            }
            else
            {
                assertFalse(accepted);
            }
            if (packet.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
            }
        }
    }

    private static class ProjectedPacket
    {
        final Vp8Packet packet;
        final int origSeq;
        final int extOrigSeq;
        final boolean nearOldest;

        ProjectedPacket(Vp8Packet p, int s, int e, boolean o)
        {
            packet = p;
            origSeq = s;
            extOrigSeq = e;
            nearOldest = o;
        }
    }

    /** Run an out-of-order test on a single stream, randomized order except for the first packet. */
    private void doRunOutOfOrderTest(Vp8PacketGenerator generator, int targetTid,
        long seed)
        throws RewriteException
    {
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        int targetIndex = RtpLayerDesc.getIndex(0, 0, targetTid);

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        final long expectedInitialTs = RtpUtils.applyTimestampDelta(initialState.maxTimestamp, 3000);
        final long expectedTsOffset = RtpUtils.getTimestampDiff(expectedInitialTs, generator.ts);

        final int reorderSize = 64;
        ArrayList<PacketInfo> buffer = new ArrayList<>(reorderSize);

        for (int i = 0; i < reorderSize; i++)
        {
            buffer.add(generator.nextPacket());
        }

        Random random = new Random(seed);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext,
                payloadType,
                initialState, logger);

        int latestSeq = buffer.get(0).<Vp8Packet>packetAs().getSequenceNumber();

        TreeMap<Integer, ProjectedPacket> projectedPackets = new TreeMap<>();
        Rfc3711IndexTracker origSeqIdxTracker = new Rfc3711IndexTracker();
        Rfc3711IndexTracker newSeqIdxTracker = new Rfc3711IndexTracker();

        for (int i = 0; i < 100000; i++)
        {
            PacketInfo packetInfo = buffer.get(0);
            Vp8Packet packet = packetInfo.packetAs();

            int origSeq = packet.getSequenceNumber();
            long origTs = packet.getTimestamp();
            int origTl0PicIdx = packet.getTL0PICIDX();

            if (RtpUtils.isOlderSequenceNumberThan(latestSeq, origSeq))
            {
                latestSeq = origSeq;
            }
            boolean accepted = context.accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex);

            int oldestValidSeq = RtpUtils.applySequenceNumberDelta(latestSeq, -((VP8FrameMap.FRAME_MAP_SIZE - 1) * generator.packetsPerFrame));

            if (RtpUtils.isOlderSequenceNumberThan(origSeq, oldestValidSeq) && !accepted)
            {
                /* This is fine; packets that are too old get ignored. */
                /* Note we don't want assertFalse(accepted) here because slightly-too-old packets
                 * that are part of an existing accepted frame will be accepted.
                 */
            }
            else if (packet.getTemporalLayerIndex() <= targetIndex)
            {
                assertTrue(accepted);

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
                boolean nearOldest = RtpUtils.getSequenceNumberDelta(origSeq, oldestValidSeq) < generator.packetsPerFrame;

                context.rewriteRtp(packetInfo);

                assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.getTimestamp());
                assertEquals(origTl0PicIdx, packet.getTL0PICIDX());
                int newSeq = packet.getSequenceNumber();
                int extNewSeq = newSeqIdxTracker.update(newSeq);
                int extOrigSeq = origSeqIdxTracker.update(origSeq);
                assertFalse(projectedPackets.containsKey(extNewSeq));
                projectedPackets.put(extNewSeq, new ProjectedPacket(packet, origSeq, extOrigSeq, nearOldest));
            }
            else
            {
                assertFalse(accepted);
            }

            buffer.set(0, generator.nextPacket());
            Collections.shuffle(buffer, random);
        }

        Iterator<Integer> iter = projectedPackets.keySet().iterator();

        ProjectedPacket prevPacket = projectedPackets.get(iter.next());

        while (iter.hasNext())
        {
            ProjectedPacket packet = projectedPackets.get(iter.next());

            assertTrue(RtpUtils.isNewerSequenceNumberThan(packet.origSeq, prevPacket.origSeq));
            if (RtpUtils.isOlderTimestampThan(prevPacket.packet.getTimestamp(), packet.packet.getTimestamp()))
            {
                assertTrue(VpxUtils.getExtendedPictureIdDelta(prevPacket.packet.getPictureId(), packet.packet.getPictureId()) < 0);
            }
            else
            {
                assertEquals(prevPacket.packet.getTimestamp(), packet.packet.getTimestamp());
                assertTrue(prevPacket.packet.getPictureId() == packet.packet.getPictureId() || packet.nearOldest);
            }

            prevPacket = packet;
        }

        /* Overall, we should not have expanded sequence numbers. */
        ProjectedPacket firstPacket = projectedPackets.firstEntry().getValue();
        ProjectedPacket lastPacket = projectedPackets.lastEntry().getValue();

        int origDelta = lastPacket.extOrigSeq - firstPacket.extOrigSeq;
        int projDelta = projectedPackets.lastKey() - projectedPackets.firstKey();
        assertTrue(projDelta <= origDelta);
    }

    /** Run multiple instances of out-of-order test on a single stream, with different
     * random seeds. */
    private void runOutOfOrderTest(Vp8PacketGenerator generator, int targetIndex)
        throws RewriteException
    {
        /* Seeds that have triggered problems in the past, plus a random one. */
        long[] seeds = { 1576267371838L, 1578347926155L, 1579620018479L, System.currentTimeMillis()};

        for (long seed: seeds)
        {
            try
            {
                doRunOutOfOrderTest(generator, targetIndex, seed);
            }
            catch (Throwable e)
            {
                logger.error(
                    "Exception thrown in randomized test, seed = " + seed, e);
                throw e;
            }

            generator.reset();
        }
    }

    @Test
    public void simpleProjectionTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        runInOrderTest(generator, 2);
    }

    @Test
    public void filteredProjectionTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        runInOrderTest(generator, 0);
    }

    @Test
    public void largerFrameProjectionTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        runInOrderTest(generator, 2);
    }

    @Test
    public void largerFrameFilteredTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        runInOrderTest(generator, 0);
    }

    @Test
    public void hugeFrameTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(200);

        runInOrderTest(generator, 0);
    }

    @Test
    public void simpleOutOfOrderTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        runOutOfOrderTest(generator, 2);
    }

    @Test
    public void largerOutOfOrderTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        runOutOfOrderTest(generator, 2);
    }

    @Test
    public void filteredOutOfOrderTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        runOutOfOrderTest(generator, 0);
    }

    @Test
    public void largerFilteredOutOfOrderTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        runOutOfOrderTest(generator, 0);
    }

    @Test
    public void slightlyDelayedKeyframeTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "slightlyDelayedKeyframeTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        int targetIndex = RtpLayerDesc.getIndex(0, 0, 2);

        for (int i = 0; i < 3; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
        }

        assertTrue(context.accept(firstPacketInfo, RtpLayerDesc.getIndex(0, 0, firstPacket.getTemporalLayerIndex()), targetIndex));
        context.rewriteRtp(firstPacketInfo);

        for (int i = 0; i < 9996; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
            context.rewriteRtp(packetInfo);
        }
    }

    @Test
    public void veryDelayedKeyframeTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "veryDelayedKeyframeTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        int targetIndex = RtpLayerDesc.getIndex(0, 0, 2);

        for (int i = 0; i < 4; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
        }

        assertFalse(context.accept(firstPacketInfo, RtpLayerDesc.getIndex(0, 0, firstPacket.getTemporalLayerIndex()), targetIndex));

        for (int i = 0; i < 10; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9996; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
            context.rewriteRtp(packetInfo);
        }
    }

    @Test
    public void delayedPartialKeyframeTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "delayedPartialKeyframeTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        int targetIndex = RtpLayerDesc.getIndex(0, 0, 2);

        for (int i = 0; i < 11; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
        }

        assertFalse(context.accept(firstPacketInfo, firstPacket.getTemporalLayerIndex(), 2));

        for (int i = 0; i < 30; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9958; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
            context.rewriteRtp(packetInfo);
        }
    }

    @Test
    public void twoStreamsNoSwitchingTest() throws RewriteException
    {
        Vp8PacketGenerator generator1 = new Vp8PacketGenerator(3);
        Vp8PacketGenerator generator2 = new Vp8PacketGenerator(3);
        generator2.setSsrc(0xdeadbeefL);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "twoStreamsNoSwitchingTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int targetIndex = RtpLayerDesc.getIndex(1, 0, 2);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        for (int i = 0; i < 10000; i++)
        {
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            assertTrue(context.accept(packetInfo1, RtpLayerDesc.getIndex(1, 0, packet1.getTemporalLayerIndex()), targetIndex));

            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, RtpLayerDesc.getIndex(0, 0, packet2.getTemporalLayerIndex()), targetIndex));

            context.rewriteRtp(packetInfo1);

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
            }
        }
    }

    @Test
    public void twoStreamsSwitchingTest() throws RewriteException
    {
        Vp8PacketGenerator generator1 = new Vp8PacketGenerator(3);
        Vp8PacketGenerator generator2 = new Vp8PacketGenerator(3);
        generator2.setSsrc(0xdeadbeefL);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "twoStreamsSwitchingTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        int targetIndex = RtpLayerDesc.getIndex(0, 0, 2);

        /* Start by wanting spatial layer 0 */
        for (int i = 0; i < 900; i++)
        {
            RtcpSrPacket srPacket1 = generator1.getSrPacket();
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            if (packet1.isStartOfFrame() && packet1.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo1, RtpLayerDesc.getIndex(0, 0, packet1.getTemporalLayerIndex()), targetIndex));

            context.rewriteRtp(packetInfo1);

            assertTrue(context.rewriteRtcp(srPacket1));
            assertEquals(packet1.getSsrc(), srPacket1.getSenderSsrc());
            assertEquals(packet1.getTimestamp(), srPacket1.getSenderInfo().getRtpTimestamp());

            RtcpSrPacket srPacket2 = generator2.getSrPacket();
            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, RtpLayerDesc.getIndex(1, 0, packet2.getTemporalLayerIndex()), targetIndex));
            assertFalse(context.rewriteRtcp(srPacket2));

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            assertEquals(expectedPicId, packet1.getPictureId());
            assertEquals(expectedTl0PicIdx, packet1.getTL0PICIDX());

            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }
        }

        /* Switch to wanting spatial layer 1, but don't send a keyframe. We should stay at the higher layer. */
        targetIndex = RtpLayerDesc.getIndex(1, 0, 2);
        for (int i = 0; i < 90; i++)
        {
            RtcpSrPacket srPacket1 = generator1.getSrPacket();
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            if (packet1.isStartOfFrame() && packet1.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo1, RtpLayerDesc.getIndex(0, 0, packet1.getTemporalLayerIndex()), targetIndex));

            context.rewriteRtp(packetInfo1);

            assertTrue(context.rewriteRtcp(srPacket1));
            assertEquals(packet1.getSsrc(), srPacket1.getSenderSsrc());
            assertEquals(packet1.getTimestamp(), srPacket1.getSenderInfo().getRtpTimestamp());

            RtcpSrPacket srPacket2 = generator2.getSrPacket();
            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, RtpLayerDesc.getIndex(1, 0, packet2.getTemporalLayerIndex()), targetIndex));
            assertFalse(context.rewriteRtcp(srPacket2));

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            assertEquals(expectedPicId, packet1.getPictureId());
            assertEquals(expectedTl0PicIdx, packet1.getTL0PICIDX());

            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }
        }

        generator1.requestKeyframe();
        generator2.requestKeyframe();

        /* After a keyframe we should accept spatial layer 1 */
        for (int i = 0; i < 9000; i++)
        {
            RtcpSrPacket srPacket1 = generator1.getSrPacket();
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            if (i == 0 && packet1.isStartOfFrame() && packet1.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            /* We will cut off the layer 0 keyframe after 1 packet, once we see the layer 1 keyframe. */
            assertEquals(i == 0, context.accept(packetInfo1, RtpLayerDesc.getIndex(0, 0, packet1.getTemporalLayerIndex()), targetIndex));
            assertEquals(i == 0, context.rewriteRtcp(srPacket1));

            if (i == 0)
            {
                context.rewriteRtp(packetInfo1);
                assertEquals(packet1.getSsrc(), srPacket1.getSenderSsrc());
                assertEquals(packet1.getTimestamp(), srPacket1.getSenderInfo().getRtpTimestamp());
            }

            RtcpSrPacket srPacket2 = generator2.getSrPacket();
            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            if (packet2.isStartOfFrame() && packet2.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo2, RtpLayerDesc.getIndex(1, 0, packet2.getTemporalLayerIndex()), targetIndex));

            context.rewriteRtp(packetInfo2);

            assertTrue(context.rewriteRtcp(srPacket2));
            assertEquals(packet2.getSsrc(), srPacket2.getSenderSsrc());
            assertEquals(packet2.getTimestamp(), srPacket2.getSenderInfo().getRtpTimestamp());

            if (i == 0)
            {
                /* We leave a 2-packet gap for the layer 0 keyframe. */
                expectedSeq += 2;
                /* ts will advance by an extra 3000 samples for the extra frame. */
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                /* pid id and tl0picidx will advance by 1 for the extra keyframe. */
                expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }

            assertEquals(expectedSeq, packet2.getSequenceNumber());
            assertEquals(expectedTs, packet2.getTimestamp());
            assertEquals(expectedPicId, packet2.getPictureId());
            assertEquals(expectedTl0PicIdx, packet2.getTL0PICIDX());
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet2.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }
        }
    }

    @Test
    public void temporalLayerSwitchingTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", "temporalLayerSwitchingTest");

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int targetTid = 0;
        int decodableTid = 0;

        int targetIndex = RtpLayerDesc.getIndex(0, 0, targetTid);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        for (int i = 0; i < 10000; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            boolean accepted = context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex);

            if (packet.isStartOfFrame() && packet.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            if (accepted)
            {
                if (decodableTid < packet.getTemporalLayerIndex())
                {
                    decodableTid = packet.getTemporalLayerIndex();
                }
            }
            else
            {
                if (decodableTid > packet.getTemporalLayerIndex() - 1)
                {
                    decodableTid = packet.getTemporalLayerIndex() - 1;
                }
            }

            if (packet.getTemporalLayerIndex() <= decodableTid)
            {
                assertTrue(accepted);

                context.rewriteRtp(packetInfo);

                assertEquals(expectedSeq, packet.getSequenceNumber());
                assertEquals(expectedTs, packet.getTimestamp());
                assertEquals(expectedPicId, packet.getPictureId());
                assertEquals(expectedTl0PicIdx, packet.getTL0PICIDX());

                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
                if (packet.isEndOfFrame())
                {
                    expectedPicId = VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
                }
            }
            else
            {
                assertFalse(accepted);
            }
            if (packet.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);

                if (i % 97 == 0) /* Prime number so it's out of sync with packet cycles. */
                {
                    targetTid = (targetTid + 2) % 3;
                    targetIndex = RtpLayerDesc.getIndex(0, 0, targetTid);
                }
            }
        }
    }

    private void runLargeDropoutTest(Vp8PacketGenerator generator,
        int targetIndex)
        throws RewriteException
    {
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveSourceProjectionContext context =
            new VP8AdaptiveSourceProjectionContext(diagnosticContext,
                payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        for (int i = 0; i < 1000; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            boolean accepted =
                context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex);

            if (packet.isStartOfFrame() && packet.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx =
                    VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            if (packet.getTemporalLayerIndex() <= targetIndex)
            {
                assertTrue(accepted);

                context.rewriteRtp(packetInfo);

                assertEquals(expectedSeq, packet.getSequenceNumber());
                assertEquals(expectedTs, packet.getTimestamp());
                assertEquals(expectedPicId, packet.getPictureId());
                assertEquals(expectedTl0PicIdx, packet.getTL0PICIDX());

                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
                if (packet.isEndOfFrame())
                {
                    expectedPicId =
                        VpxUtils.applyExtendedPictureIdDelta(expectedPicId, 1);
                }
            }
            else
            {
                assertFalse(accepted);
            }
            if (packet.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
            }
        }

        for (int gap = 64; gap < 65536; gap *= 2)
        {
            for (int i = 0; i < gap; i++)
            {
                generator.nextPacket();
            }

            PacketInfo packetInfo;
            Vp8Packet packet;

            do
            {
                packetInfo = generator.nextPacket();
                packet = packetInfo.packetAs();
            }
            while (packet.getTemporalLayerIndex() > targetIndex);

            assertTrue(context.accept(packetInfo, RtpLayerDesc.getIndex(0, 0, packet.getTemporalLayerIndex()), targetIndex));
            context.rewriteRtp(packetInfo);

            /* Allow any values after a gap. */
            expectedSeq =
                RtpUtils.applySequenceNumberDelta(packet.getSequenceNumber(), 1);
            expectedTs = packet.getTimestamp();
            expectedPicId = packet.getPictureId();
            expectedTl0PicIdx = packet.getTL0PICIDX();

            if (packet.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = VpxUtils
                    .applyExtendedPictureIdDelta(expectedPicId, 1);
            }

            for (int i = 0; i < 1000; i++)
            {
                packetInfo = generator.nextPacket();
                packet = packetInfo.packetAs();

                boolean accepted = context
                    .accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex);

                if (packet.isStartOfFrame()
                    && packet.getTemporalLayerIndex() == 0)
                {
                    expectedTl0PicIdx =
                        VpxUtils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
                }

                if (packet.getTemporalLayerIndex() <= targetIndex)
                {
                    assertTrue(accepted);

                    context.rewriteRtp(packetInfo);

                    assertEquals(expectedSeq, packet.getSequenceNumber());
                    assertEquals(expectedTs, packet.getTimestamp());
                    assertEquals(expectedPicId, packet.getPictureId());
                    assertEquals(expectedTl0PicIdx, packet.getTL0PICIDX());

                    expectedSeq =
                        RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
                    if (packet.isEndOfFrame())
                    {
                        expectedPicId = VpxUtils
                            .applyExtendedPictureIdDelta(expectedPicId, 1);
                    }
                }
                else
                {
                    assertFalse(accepted);
                }
                if (packet.isEndOfFrame())
                {
                    expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                }
            }
        }
    }

    @Test
    public void largeDropoutTest()
        throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);
        runLargeDropoutTest(generator, 2);
    }

    @Test
    public void filteredLargeDropoutTest()
        throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);
        runLargeDropoutTest(generator, 0);
    }

    @Test
    public void largeFrameDropoutTest()
        throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);
        runLargeDropoutTest(generator, 2);
    }

    @Test
    public void filteredLargeFrameDropoutTest()
        throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);
        runLargeDropoutTest(generator, 0);
    }


    private static class Vp8PacketGenerator {
        private static final byte[] vp8PacketTemplate =
            DatatypeConverter.parseHexBinary(
                /* RTP Header */
                "80" + /* V, P, X, CC */
                    "60" + /* M, PT */
                    "0000" + /* Seq */
                    "00000000" + /* TS */
                    "cafebabe" + /* SSRC */
                    /* VP8 Payload descriptor */
                    "90" + /* First byte, X, S set, PID = 0 */
                    "e0" + /* X byte, I, L, T set */
                    "8000" + /* I byte (ext pic id), M set */
                    "00" + /* L byte (tl0 pic idx) */
                    "00" + /* T/K byte (tid) */
                    /* VP8 payload header */
                    "00" + /* P = 0. */
                /* Rest of payload is "don't care" for this code, except
                   keyframe frame size, so make this a valid-ish keyframe header.
                 */
                    "0000" + /* Length = 0. */
                    "9d012a" + /* Keyframe startcode */
                    "0050D002" /* 1280 × 720 (little-endian) */
            );

        final int packetsPerFrame;

        Vp8PacketGenerator(int packetsPerFrame)
        {
            this.packetsPerFrame = packetsPerFrame;

            reset();
        }

        private int seq;
        private long ts;
        private int picId ;
        private int tl0picidx ;
        private int packetOfFrame ;
        private boolean keyframe;
        private boolean keyframeRequested;
        private int tidCycle;
        private long ssrc;

        private int packetCount;
        private int octetCount;
        private int frameCount;

        private static final long baseReceivedTime = 1577836800000L; /* 2020-01-01 00:00:00 UTC */
        private long receivedTime;

        void reset()
        {
            boolean useRandom = true; // switch off to ease debugging
            long seed = System.currentTimeMillis();
            Random random = new Random(seed);

            seq = useRandom ? random.nextInt() % 0x10000 : 0;
            ts = useRandom ? random.nextLong() % 0x100000000L : 0;

            picId = 0;
            tl0picidx = 0;
            packetOfFrame = 0;
            keyframe = true;
            keyframeRequested = false;
            tidCycle = 0;
            ssrc = 0xcafebabeL;

            packetCount = 0;
            octetCount = 0;
            frameCount = 0;

            receivedTime = baseReceivedTime;
        }


        public void setSsrc(long ssrc)
        {
            this.ssrc = ssrc;
        }

        public PacketInfo nextPacket()
        {
            int tid;
            switch(tidCycle % 4)
            {
            case 0:
                tid = 0;
                break;
            case 2:
                tid = 1;
                break;
            case 1:
            case 3:
                tid = 2;
                break;
            default:
                /* Convince Java that yes, tid is always initialized. */
                assert(false); /* Math is broken */
                tid = -1;
            }

            boolean startOfFrame = (packetOfFrame == 0);
            boolean endOfFrame = (packetOfFrame == packetsPerFrame - 1);

            if (startOfFrame && tid == 0) {
                tl0picidx = VpxUtils.applyTl0PicIdxDelta(tl0picidx, 1);
            }

            byte[] buffer = vp8PacketTemplate.clone();

            RtpPacket rtpPacket = new RtpPacket(buffer,0, buffer.length);

            rtpPacket.setSsrc(ssrc);
            rtpPacket.setSequenceNumber(seq);
            rtpPacket.setTimestamp(ts);

            /* Do VP8 manipulations on buffer before constructing Vp8Packet, because
               Vp8Packet computes values at construct-time. */
            DePacketizer.VP8PayloadDescriptor.setStartOfPartition(rtpPacket.buffer,
                rtpPacket.getPayloadOffset(), startOfFrame);
            assertTrue(DePacketizer.VP8PayloadDescriptor.setTemporalLayerIndex(rtpPacket.buffer,
                rtpPacket.getPayloadOffset(), rtpPacket.getPayloadLength(), tid));

            if (startOfFrame) {
                int szVP8PayloadDescriptor = DePacketizer
                    .VP8PayloadDescriptor.getSize(rtpPacket.buffer, rtpPacket.getPayloadOffset(), rtpPacket.getPayloadLength());

                DePacketizer.VP8PayloadHeader.setKeyFrame(rtpPacket.buffer,
                    rtpPacket.getPayloadOffset() + szVP8PayloadDescriptor, keyframe);
            }
            rtpPacket.setMarked(endOfFrame);

            Vp8Packet vp8Packet = rtpPacket.toOtherType(Vp8Packet::new);

            /* Make sure our manipulations of the raw buffer were correct. */
            assertEquals(startOfFrame, vp8Packet.isStartOfFrame());
            assertEquals(tid, vp8Packet.getTemporalLayerIndex());
            if (startOfFrame)
            {
                assertEquals(keyframe, vp8Packet.isKeyframe());
            }

            vp8Packet.setPictureId(picId);
            vp8Packet.setTL0PICIDX(tl0picidx);

            PacketInfo info = new PacketInfo(vp8Packet);
            info.setReceivedTime(receivedTime);

            seq = RtpUtils.applySequenceNumberDelta(seq, 1);
            packetCount++;
            octetCount += vp8Packet.length;

            if (endOfFrame)
            {
                packetOfFrame = 0;
                ts = RtpUtils.applyTimestampDelta(ts, 3000);
                picId = VpxUtils.applyExtendedPictureIdDelta(picId, 1);
                tidCycle++;
                keyframe = keyframeRequested;
                keyframeRequested = false;
                if (keyframe)
                {
                    tidCycle = 0;
                }
                frameCount++;
                receivedTime = baseReceivedTime + frameCount * 100 / 3;
            }
            else
            {
                packetOfFrame++;
            }

            return info;
        }

        private void requestKeyframe()
        {
            if (packetOfFrame == 0)
            {
                keyframe = true;
                keyframeRequested = false;
                tidCycle = 0;
            }
            else
            {
                keyframeRequested = true;
            }
        }

        /* TODO: move this to jitsi-rtp */
        public static void setSIBuilderNtp(SenderInfoBuilder siBuilder, long wallTime)
        {
            final long JAVA_TO_NTP_EPOCH_OFFSET_SECS = 2208988800L;

            long wallSecs = wallTime / 1000;
            long wallMs = wallTime % 1000;

            siBuilder.setNtpTimestampMsw(wallSecs + JAVA_TO_NTP_EPOCH_OFFSET_SECS);
            siBuilder.setNtpTimestampLsw(wallMs * (1L << 32) / 1000);
        }

        public RtcpSrPacket getSrPacket()
        {
            RtcpSrPacketBuilder srPacketBuilder = new RtcpSrPacketBuilder();

            srPacketBuilder.getRtcpHeader().setSenderSsrc(ssrc);

            SenderInfoBuilder siBuilder = srPacketBuilder.getSenderInfo();
            setSIBuilderNtp(srPacketBuilder.getSenderInfo(), receivedTime);
            siBuilder.setRtpTimestamp(ts);
            siBuilder.setSendersOctetCount(packetCount);
            siBuilder.setSendersOctetCount(octetCount);

            return srPacketBuilder.build();
        }
    }
}
