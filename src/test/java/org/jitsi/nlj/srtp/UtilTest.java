package org.jitsi.nlj.srtp;

import org.jitsi.rtp.*;

import java.nio.*;

import static org.jitsi.nlj.srtp.Util.toHex;
import static org.junit.jupiter.api.Assertions.*;

class UtilTest
{
    // ssrc 2997363329 seq num 20540
    static byte[] packetBuf = {
        (byte)0x90, (byte)0x6F, (byte)0x50, (byte)0x3C, (byte)0x46, (byte)0x1F, (byte)0xF4, (byte)0x98, (byte)0xB2,
        (byte)0xA8, (byte)0x22, (byte)0x81, (byte)0xBE, (byte)0xDE, (byte)0x00, (byte)0x01, (byte)0x10, (byte)0xFF,
        (byte)0x00, (byte)0x00, (byte)0xF1, (byte)0x7E, (byte)0xBE, (byte)0xA1, (byte)0x31, (byte)0xFC, (byte)0xD9,
        (byte)0xE1, (byte)0x4E, (byte)0xAA, (byte)0x31, (byte)0x02, (byte)0xF6, (byte)0xD4, (byte)0x6F, (byte)0xF1,
        (byte)0xDA, (byte)0x57, (byte)0x4B, (byte)0x8E, (byte)0x67, (byte)0xE1, (byte)0x68, (byte)0x3F, (byte)0x13,
        (byte)0xC3, (byte)0xD9, (byte)0x67, (byte)0x7D, (byte)0x38, (byte)0x1B, (byte)0xDA, (byte)0xD6, (byte)0xAD,
        (byte)0x29, (byte)0xF9, (byte)0x19, (byte)0x65, (byte)0xB9, (byte)0x0D, (byte)0xCB, (byte)0x89, (byte)0xFD,
        (byte)0xF5, (byte)0x9D,

    };
    static byte[] keyingMaterial = {
        (byte)0xFF, (byte)0xE4, (byte)0xEE, (byte)0xCF, (byte)0xE8, (byte)0x00, (byte)0xE7, (byte)0x38, (byte)0xFC,
        (byte)0xDB, (byte)0x24, (byte)0xCA, (byte)0x8A, (byte)0xCC, (byte)0x4E, (byte)0x07, (byte)0x32, (byte)0x19,
        (byte)0x5B, (byte)0x88, (byte)0x36, (byte)0x08, (byte)0xB7, (byte)0x75, (byte)0xEE, (byte)0xFA, (byte)0xA0,
        (byte)0xC2, (byte)0x94, (byte)0x36, (byte)0x65, (byte)0x1E, (byte)0x3F, (byte)0x29, (byte)0xF2, (byte)0x80,
        (byte)0xA3, (byte)0x39, (byte)0x35, (byte)0x61, (byte)0x48, (byte)0x3D, (byte)0xA1, (byte)0x9A, (byte)0x06,
        (byte)0x8D, (byte)0x20, (byte)0x66, (byte)0x3A, (byte)0xC2, (byte)0x71, (byte)0x71, (byte)0x66, (byte)0x92,
        (byte)0x56, (byte)0x9F, (byte)0x2D, (byte)0xA2, (byte)0x70, (byte)0x71
    };

    public static void main(String[] args)
    {
        Util.ProtectionProfileInformation ppi = Util.getProtectionProfileInformation(1);

        Util.SrtpContextFactories factories = Util.createSrtpContextFactories(ppi, keyingMaterial, true);

//        SRTPContextFactory df = factories.decryptFactory;
        SRTPContextFactory df = factories.encryptFactory;

        SRTPCryptoContext ctx = df.getDefaultContext().deriveContext(-1297603967, 0, 0);
        ctx.deriveSrtpKeys(20540);

        SrtpPacket packet = new SrtpPacket(ByteBuffer.wrap(packetBuf));
        System.out.println("pt: " + packet.getHeader().getPayloadType());
        System.out.println("Packet payload offset: " + packet.getPayload().getOffset() + ", length: " + packet.getPayload().getLength());
        ctx.reverseTransformPacket(packet);

        System.out.println("Packet after decrypt: " + toHex(packet.getBuf().array(), 0, packet.getSize()));
    }
}

