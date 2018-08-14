package org.jitsi.nlj.srtp;

import org.jitsi.rtp.*;

import java.nio.*;

import static org.jitsi.nlj.srtp.Util.toHex;

class SRTPCryptoContextTest
{
    byte[] masterKey = {
            (byte)0xBE, (byte)0x8F, (byte)0x81, 0x60, 0x33, 0x7C,
            (byte)0xB2, (byte)0xB2, (byte)0xF9, 0x29, (byte)0x9D,
            0x04, (byte)0x93, 0x38, (byte)0xAE, 0x2A
    };

    byte[] masterSalt = {
            0x08, 0x50, (byte)0xBA, (byte)0xBD, (byte)0xAE, 0x3C, (byte)0xC2, (byte)0x9F, (byte)0x9F,
            0x70, 0x33, (byte)0xDE, 0x25, 0x2E
    };

    byte[] packetBefore = {
            (byte)0x90, 0x6F, 0x2D, 0x41, 0x47, (byte)0xAA, 0x72, (byte)0xFF, 0x2A, 0x2D, (byte)0x86, (byte)0xC1, (byte)0xBE, (byte)0xDE,
            0x00, 0x01, 0x10, (byte)0xFF, 0x00, 0x00, (byte)0xA3, 0x4E, 0x59, 0x2F, (byte)0xDE, 0x39, 0x43, 0x7F, 0x40, (byte)0xE4, (byte)0xE4,
            0x20, (byte)0xDA, 0x25, 0x2A, 0x05, 0x53, 0x15, 0x6D, 0x4A, (byte)0xBF, (byte)0xFD, 0x0F, (byte)0xF0, 0x79, 0x10, 0x06, (byte)0xDD,
            (byte)0xA0, 0x3F, 0x30, (byte)0xD7, (byte)0xA2, 0x22, 0x75, (byte)0xAA, (byte)0xA2, 0x6B, (byte)0xFA,
            (byte)0x9D, 0x5A, 0x24, (byte)0xF9, (byte)0xE7, 0x24
    };
    SRTPPolicy policy = new SRTPPolicy(
            1,
            16,
            1,
            20,
            10,
            14
    );

    SRTPCryptoContext defaultContext = new SRTPCryptoContext(
            false,
            0,
            0,
            0,
            masterKey,
            masterSalt,
            policy
    );


    public static void main(String[] args)
    {
        SRTPCryptoContextTest test = new SRTPCryptoContextTest();
        SRTPCryptoContext derived = test.defaultContext.deriveContext(707626689, 0, 0);

        System.out.println("Default context: " + test.defaultContext);
        System.out.println("Got derived: " + derived);
        derived.deriveSrtpKeys(11585L);
        System.out.println("After derive keys: " + derived);

        RtpPacket newPacket = derived.reverseTransformPacket(new SrtpPacket(ByteBuffer.wrap(test.packetBefore)));

        System.out.println("Packet after: length = " + newPacket.getSize() + ", offset: 0" + ", " +
                "payload length: " + newPacket.getPayload().getLength() + " payload offset: " + newPacket.getPayload().getOffset() +
                "payload: " + toHex(newPacket.getPayload()));


    }
}

