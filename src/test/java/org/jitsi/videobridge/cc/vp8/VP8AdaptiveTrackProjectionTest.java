package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.junit.*;

import javax.xml.bind.*;
import java.text.*;
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

        Vp8Packet packet = generator.nextPacket();

        assertTrue(context.accept(packet, 0, 0));

        context.rewriteRtp(packet);

        assertEquals(10001, packet.getSequenceNumber());
        assertEquals(1003000, packet.getTimestamp());
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
        for (int i = 0; i < 10000; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            boolean accepted = context.accept(packet, packet.getTemporalLayerIndex(), targetIndex);

            if (packet.getTemporalLayerIndex() <= targetIndex)
            {
                assertTrue(accepted);

                context.rewriteRtp(packet);

                assertEquals(expectedSeq, packet.getSequenceNumber());
                assertEquals(expectedTs, packet.getTimestamp());
                expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);
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
        DiagnosticContext diagnosticContext = new DiagnosticContext();
        diagnosticContext.put("test", Thread.currentThread().getStackTrace()[2].getMethodName());

        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        final long expectedTsOffset = RtpUtils.applyTimestampDelta(initialState.maxTimestamp, 3000);

        final int reorderSize = 64;
        ArrayList<Vp8Packet> buffer = new ArrayList<>(reorderSize);

        for (int i = 0; i < reorderSize; i++)
        {
            buffer.add(generator.nextPacket());
        }

        long seed = System.currentTimeMillis(); // Pass an explicit seed for reproducible tests
        //  long seed = 1575666289164L;
        Random random = new Random(seed);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext,
                payloadType,
                initialState, logger);

        int latestSeq = buffer.get(0).getSequenceNumber();

        TreeMap<Integer, ProjectedPacket> projectedPackets = new TreeMap<>();

        for (int i = 0; i < 10000; i++)
        {
            Vp8Packet packet = buffer.get(0);
            int origSeq = packet.getSequenceNumber();
            long origTs = packet.getTimestamp();

            if (RtpUtils.isOlderSequenceNumberThan(latestSeq, origSeq))
            {
                latestSeq = origSeq;
            }
            boolean accepted = context.accept(packet, packet.getTemporalLayerIndex(), targetIndex);

            if (RtpUtils.isOlderSequenceNumberThan(origSeq, RtpUtils.applySequenceNumberDelta(latestSeq, -VP8FrameMap.FRAME_MAP_SIZE))) {
                assertFalse(accepted);
            }
            else if (packet.getTemporalLayerIndex() <= targetIndex)
            {
                assertTrue(accepted);

                context.rewriteRtp(packet);

                assertEquals(RtpUtils.applyTimestampDelta(origTs, expectedTsOffset), packet.getTimestamp());
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

        Vp8Packet firstPacket = generator.nextPacket();

        for (int i = 0; i < 3; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertFalse(context.accept(packet, packet.getTemporalLayerIndex(), 2));
        }

        assertTrue(context.accept(firstPacket, firstPacket.getTemporalLayerIndex(), 2));
        context.rewriteRtp(firstPacket);

        for (int i = 0; i < 9996; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertTrue(context.accept(packet, packet.getTemporalLayerIndex(), 2));
            context.rewriteRtp(packet);
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

        Vp8Packet firstPacket = generator.nextPacket();

        for (int i = 0; i < 4; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertFalse(context.accept(packet, packet.getTemporalLayerIndex(), 2));
        }

        assertFalse(context.accept(firstPacket, firstPacket.getTemporalLayerIndex(), 2));

        for (int i = 0; i < 10; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertFalse(context.accept(packet, packet.getTemporalLayerIndex(), 2));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9996; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertTrue(context.accept(packet, packet.getTemporalLayerIndex(), 2));
            context.rewriteRtp(packet);
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

        Vp8Packet firstPacket = generator.nextPacket();

        for (int i = 0; i < 11; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertFalse(context.accept(packet, packet.getTemporalLayerIndex(), 2));
        }

        assertFalse(context.accept(firstPacket, firstPacket.getTemporalLayerIndex(), 2));

        for (int i = 0; i < 30; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertFalse(context.accept(packet, packet.getTemporalLayerIndex(), 2));
        }

        generator.requestKeyframe();

        for (int i = 0; i < 9958; i++)
        {
            Vp8Packet packet = generator.nextPacket();

            assertTrue(context.accept(packet, packet.getTemporalLayerIndex(), 2));
            context.rewriteRtp(packet);
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
            Vp8Packet packet1 = generator1.nextPacket();

            assertTrue(context.accept(packet1, packet1.getTemporalLayerIndex() + 3, 5));

            Vp8Packet packet2 = generator2.nextPacket();
            assertFalse(context.accept(packet2, packet2.getTemporalLayerIndex(), 5));

            context.rewriteRtp(packet1);

            assertEquals(expectedSeq, packet1.getSequenceNumber());
            assertEquals(expectedTs, packet1.getTimestamp());
            expectedSeq = RtpUtils.applySequenceNumberDelta(expectedSeq, 1);

            if (packet1.isEndOfFrame())
            {
                expectedTs = RtpUtils.applyTimestampDelta(expectedTs, 3000);
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

        public void setSsrc(long ssrc)
        {
            this.ssrc = ssrc;
        }

        public Vp8Packet nextPacket()
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

            seq = RtpUtils.applySequenceNumberDelta(seq, 1);
            if (endOfFrame)
            {
                packetOfFrame = 0;
                ts = RtpUtils.applyTimestampDelta(ts, 3000);
                picId++;
                picId %= DePacketizer.VP8PayloadDescriptor.EXTENDED_PICTURE_ID_MASK;
                tidCycle++;
                keyframe = keyframeRequested;
                keyframeRequested = false;
                if (keyframe)
                {
                    tidCycle = 0;
                }

                if (tid == 0) {
                    tl0picidx++;
                    tl0picidx %= DePacketizer.VP8PayloadDescriptor.TL0PICIDX_MASK;
                }
            }
            else
            {
                packetOfFrame++;
            }

            return vp8Packet;
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
    }
}
