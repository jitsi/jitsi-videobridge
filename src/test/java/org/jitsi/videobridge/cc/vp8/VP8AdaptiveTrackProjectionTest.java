package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.*;
import org.jitsi.nlj.codec.vp8.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
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

public class VP8AdaptiveTrackProjectionTest
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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        PacketInfo packetInfo = generator.nextPacket();
        Vp8Packet packet = packetInfo.packetAs();

        assertTrue(context.accept(packetInfo, 0, 0));

        context.rewriteRtp(packetInfo);

        assertEquals(10001, packet.getSequenceNumber());
        assertEquals(1003000, packet.getTimestamp());
        assertEquals(0, packet.getPictureId());
        assertEquals(0, packet.getTemporalLayerIndex());
    }

    private void runInOrderTest(Vp8PacketGenerator generator, int targetIndex)
        throws RewriteException
    {
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        for (int i = 0; i < 10000; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            boolean accepted = context.accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex);

            if (packet.isStartOfFrame() && packet.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
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
                    expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
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

        ProjectedPacket(Vp8Packet p, int s)
        {
            packet = p;
            origSeq = s;
        }
    }

    /** Run an out-of-order test on a single stream, randomized order except for the first packet. */
    private void runOutOfOrderTest(Vp8PacketGenerator generator, int targetIndex)
        throws RewriteException
    {
        long seed = System.currentTimeMillis(); // Pass an explicit seed for reproducible tests
        //  long seed = 1576267371838L;

        try
        {
            DiagnosticContext diagnosticContext = new DiagnosticContext();
            diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

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

            VP8AdaptiveTrackProjectionContext context =
                new VP8AdaptiveTrackProjectionContext(diagnosticContext,
                    payloadType,
                    initialState, logger);

            int latestSeq = buffer.get(0).<Vp8Packet>packetAs().getSequenceNumber();

            TreeMap<Integer, ProjectedPacket> projectedPackets = new TreeMap<>();

            for (int i = 0; i < 10000; i++)
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

                if (RtpUtils.isOlderSequenceNumberThan(origSeq,
                        RtpUtils.applySequenceNumberDelta(latestSeq, -VP8FrameMap.FRAME_MAP_SIZE))
                    && !accepted)
                {
                    /* This is fine; packets that are too old get ignored. */
                    /* Note we don't want assertFalse(accepted) here because slightly-too-old packets
                     * that are part of an existing accepted frame will be accepted.
                     */
                }
                else if (packet.getTemporalLayerIndex() <= targetIndex)
                {
                    assertTrue(accepted);

                    context.rewriteRtp(packetInfo);

                    assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.getTimestamp());
                    assertEquals(origTl0PicIdx, packet.getTL0PICIDX());
                    int newSeq = packet.getSequenceNumber();
                    assertFalse(projectedPackets.containsKey(newSeq));
                    projectedPackets.put(newSeq, new ProjectedPacket(packet, origSeq));
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
                    assertTrue(Vp8Utils.getExtendedPictureIdDelta(prevPacket.packet.getPictureId(), packet.packet.getPictureId()) < 0);
                }
                else
                {
                    assertEquals(prevPacket.packet.getPictureId(), packet.packet.getPictureId());
                }

                prevPacket = packet;
            }

            /* Overall, we should not have expanded sequence numbers. */
            ProjectedPacket firstPacket = projectedPackets.firstEntry().getValue();
            ProjectedPacket lastPacket = projectedPackets.lastEntry().getValue();

            int origDelta = RtpUtils.getSequenceNumberDelta(lastPacket.origSeq, firstPacket.origSeq);
            int projDelta = RtpUtils.getSequenceNumberDelta(lastPacket.packet.getSequenceNumber(),
                    firstPacket.packet.getSequenceNumber());
            assertTrue(projDelta <= origDelta);
        }
        catch (Throwable e)
        {
            logger.error("Exception thrown in randomized test, seed = " + seed, e);
            throw e;
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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        for (int i = 0; i < 3; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
        }

        assertTrue(context.accept(firstPacketInfo, firstPacket.getTemporalLayerIndex(), 2));
        context.rewriteRtp(firstPacketInfo);

        for (int i = 0; i < 9996; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        for (int i = 0; i < 4; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
        }

        assertFalse(context.accept(firstPacketInfo, firstPacket.getTemporalLayerIndex(), 2));

        for (int i = 0; i < 10; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9996; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        PacketInfo firstPacketInfo = generator.nextPacket();
        Vp8Packet firstPacket = firstPacketInfo.packetAs();

        for (int i = 0; i < 11; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
        }

        assertFalse(context.accept(firstPacketInfo, firstPacket.getTemporalLayerIndex(), 2));

        for (int i = 0; i < 30; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertFalse(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9958; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            assertTrue(context.accept(packetInfo, packet.getTemporalLayerIndex(), 2));
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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        for (int i = 0; i < 10000; i++)
        {
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            assertTrue(context.accept(packetInfo1, packet1.getTemporalLayerIndex() + 3, 5));

            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, packet2.getTemporalLayerIndex(), 5));

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

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        /* Start by wanting spatial layer 0 */
        for (int i = 0; i < 900; i++)
        {
            RtcpSrPacket srPacket1 = generator1.getSrPacket();
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            if (packet1.isStartOfFrame() && packet1.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo1, packet1.getTemporalLayerIndex(), 2));

            context.rewriteRtp(packetInfo1);

            assertTrue(context.rewriteRtcp(srPacket1));
            assertEquals(packet1.getSsrc(), srPacket1.getSenderSsrc());
            assertEquals(packet1.getTimestamp(), srPacket1.getSenderInfo().getRtpTimestamp());

            RtcpSrPacket srPacket2 = generator2.getSrPacket();
            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, packet2.getTemporalLayerIndex() + 3, 2));
            assertFalse(context.rewriteRtcp(srPacket2));

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            assertEquals(expectedPicId, packet1.getPictureId());
            assertEquals(expectedTl0PicIdx, packet1.getTL0PICIDX());

            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }
        }

        /* Switch to wanting spatial layer 1, but don't send a keyframe. We should stay at the higher layer. */
        for (int i = 0; i < 90; i++)
        {
            RtcpSrPacket srPacket1 = generator1.getSrPacket();
            PacketInfo packetInfo1 = generator1.nextPacket();
            Vp8Packet packet1 = packetInfo1.packetAs();

            if (packet1.isStartOfFrame() && packet1.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo1, packet1.getTemporalLayerIndex(), 5));

            context.rewriteRtp(packetInfo1);

            assertTrue(context.rewriteRtcp(srPacket1));
            assertEquals(packet1.getSsrc(), srPacket1.getSenderSsrc());
            assertEquals(packet1.getTimestamp(), srPacket1.getSenderInfo().getRtpTimestamp());

            RtcpSrPacket srPacket2 = generator2.getSrPacket();
            PacketInfo packetInfo2 = generator2.nextPacket();
            Vp8Packet packet2 = packetInfo2.packetAs();

            assertFalse(context.accept(packetInfo2, packet2.getTemporalLayerIndex() + 3, 5));
            assertFalse(context.rewriteRtcp(srPacket2));

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            assertEquals(expectedPicId, packet1.getPictureId());
            assertEquals(expectedTl0PicIdx, packet1.getTL0PICIDX());

            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
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
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            /* We will cut off the layer 0 keyframe after 1 packet, once we see the layer 1 keyframe. */
            assertEquals(i == 0, context.accept(packetInfo1, packet1.getTemporalLayerIndex(), 5));
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
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            assertTrue(context.accept(packetInfo2, packet2.getTemporalLayerIndex() + 3, 5));

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
                expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }

            assertEquals(expectedSeq, packet2.getSequenceNumber());
            assertEquals(expectedTs, packet2.getTimestamp());
            assertEquals(expectedPicId, packet2.getPictureId());
            assertEquals(expectedTl0PicIdx, packet2.getTL0PICIDX());
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet2.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
                expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
            }
        }
    }

    @Test
    public void temporalLayerSwitchingTest() throws RewriteException
    {
        Vp8PacketGenerator generator = new Vp8PacketGenerator(3);

        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        int targetIndex = 0;
        int decodableIndex = 0;

        int expectedSeq = 10001;
        long expectedTs = 1003000;
        int expectedPicId = 0;
        int expectedTl0PicIdx = 0;

        for (int i = 0; i < 10000; i++)
        {
            PacketInfo packetInfo = generator.nextPacket();
            Vp8Packet packet = packetInfo.packetAs();

            boolean accepted = context.accept(packetInfo, packet.getTemporalLayerIndex(), targetIndex);

            if (packet.isStartOfFrame() && packet.getTemporalLayerIndex() == 0)
            {
                expectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(expectedTl0PicIdx, 1);
            }

            if (accepted)
            {
                if (decodableIndex < packet.getTemporalLayerIndex())
                {
                    decodableIndex = packet.getTemporalLayerIndex();
                }
            }
            else {
                if (decodableIndex > packet.getTemporalLayerIndex() - 1)
                {
                    decodableIndex = packet.getTemporalLayerIndex() - 1;
                }
            }

            if (packet.getTemporalLayerIndex() <= decodableIndex)
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
                    expectedPicId = Vp8Utils.applyExtendedPictureIdDelta(expectedPicId, 1);
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
                    targetIndex = (targetIndex + 2) % 3;
                }
            }
        }
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

        private final int packetsPerFrame;

        Vp8PacketGenerator(int packetsPerFrame)
        {
            this.packetsPerFrame = packetsPerFrame;


            long seed = System.currentTimeMillis();
            Random random = new Random(seed);

            seq = random.nextInt() % 0x10000;
            ts = random.nextLong() % 0x100000000L;
        }

        private int seq = 0;
        private long ts = 0;
        private int picId = 0;
        private int tl0picidx = 0;
        private int packetOfFrame = 0;
        private boolean keyframe = true;
        private boolean keyframeRequested = false;
        private int tidCycle = 0;
        private long ssrc = 0xcafebabeL;

        private int packetCount = 0;
        private int octetCount = 0;
        private int frameCount = 0;

        private long baseReceivedTime = 1577836800000L; /* 2020-01-01 00:00:00 UTC */
        private long receivedTime = baseReceivedTime;

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
                tl0picidx = Vp8Utils.applyTl0PicIdxDelta(tl0picidx, 1);
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
                picId = Vp8Utils.applyExtendedPictureIdDelta(picId, 1);
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
