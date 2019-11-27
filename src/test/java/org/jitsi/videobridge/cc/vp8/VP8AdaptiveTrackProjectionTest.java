package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.junit.*;

import javax.xml.bind.*;
import java.text.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class VP8AdaptiveTrackProjectionTest
{
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();
    private final Logger logger = new LoggerImpl(getClass().getName());
    private final PayloadType payloadType = new Vp8PayloadType((byte)96,
        new ConcurrentHashMap<>(), new CopyOnWriteArraySet<>());

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

    private Vp8Packet buildPacket(int seq, long ts,
        boolean startOfFrame, boolean endOfFrame, boolean keyframe,
        int tid, int picId, int tl0picidx)
    {
        byte[] buffer = vp8PacketTemplate.clone();

        RtpPacket rtpPacket = new RtpPacket(buffer,0, buffer.length);

        rtpPacket.setSequenceNumber(seq);
        rtpPacket.setTimestamp(ts);

        /* Do VP8 manipulations on buffer before constructing Vp8Packet, because
           Vp8Packet computes values at construct-time. */
        DePacketizer.VP8PayloadDescriptor.setStartOfPartition(rtpPacket.buffer,
            rtpPacket.getPayloadOffset(), startOfFrame);
        DePacketizer.VP8PayloadDescriptor.setTemporalLayerIndex(rtpPacket.buffer,
            rtpPacket.getPayloadOffset(), rtpPacket.length, tid);

        if (startOfFrame) {
            int szVP8PayloadDescriptor = DePacketizer
                .VP8PayloadDescriptor.getSize(rtpPacket.buffer, rtpPacket.getPayloadOffset(), rtpPacket.length);

            DePacketizer.VP8PayloadHeader.setKeyFrame(rtpPacket.buffer,
                rtpPacket.getPayloadOffset() + szVP8PayloadDescriptor, keyframe);
        }
        rtpPacket.setMarked(endOfFrame);

        Vp8Packet vp8Packet = rtpPacket.toOtherType(Vp8Packet::new);

        vp8Packet.setPictureId(picId);
        vp8Packet.setTL0PICIDX(tl0picidx);

        return vp8Packet;
    }

    @Test
    public void simpleProjectionTest() throws RewriteException
    {
        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        Vp8Packet packet = buildPacket(1, 0, true, true, true, 0, 0, 0);

        assertTrue(context.accept(packet, 0, 0));

        context.rewriteRtp(packet);

        assertEquals(10001, packet.getSequenceNumber());
        assertEquals(1003000, packet.getTimestamp());
    }
}
