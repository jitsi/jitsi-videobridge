package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.junit.*;

import javax.xml.bind.*;
import java.text.*;
import java.util.concurrent.*;

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
                "fefefefe" /* Rest of payload is "don't care" for this code. */
                           /* TODO: height */
            );

    private Vp8Packet buildPacket(int seq, long ts,
        boolean startOfFrame, boolean endOfFrame, boolean keyframe,
        int tid, int picId, int tl0picidx)
    {
        byte[] buffer = vp8PacketTemplate.clone();
        Vp8Packet packet = new Vp8Packet(buffer, 0, buffer.length);

        packet.setSequenceNumber(seq);
        packet.setTimestamp(ts);

        packet.setPictureId(picId);
        packet.setTL0PICIDX(tl0picidx);

        DePacketizer.VP8PayloadDescriptor.setStartOfPartition(packet.buffer,
            packet.getPayloadOffset(), startOfFrame);
        DePacketizer.VP8PayloadDescriptor.setTemporalLayerIndex(packet.buffer,
            packet.getPayloadOffset(), packet.length, tid);

        if (startOfFrame) {
            int szVP8PayloadDescriptor = DePacketizer
                .VP8PayloadDescriptor.getSize(packet.buffer, packet.getPayloadOffset(), packet.length);

            DePacketizer.VP8PayloadHeader.setKeyFrame(packet.buffer,
                packet.getPayloadOffset() + szVP8PayloadDescriptor, keyframe);
        }
        packet.setMarked(endOfFrame);

        return null;
    }

    @Test
    public void simpleProjectionTest()
    {
        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);
    }
}
