/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.transform.srtp;

import org.bouncycastle.crypto.params.*;
import org.jitsi.bccontrib.params.*;
//import org.jitsi.rtp.*;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.srtcp.*;
import org.jitsi.util.*;

import java.nio.*;
import java.util.*;

/**
 * SRTPCryptoContext class is the core class of SRTP implementation. There can
 * be multiple SRTP sources in one SRTP session. And each SRTP stream has a
 * corresponding SRTPCryptoContext object, identified by SSRC. In this way,
 * different sources can be protected independently.
 *
 * SRTPCryptoContext class acts as a manager class and maintains all the
 * information used in SRTP transformation. It is responsible for deriving
 * encryption/salting/authentication keys from master keys. And it will invoke
 * certain class to encrypt/decrypt (transform/reverse transform) RTP packets.
 * It will hold a replay check db and do replay check against incoming packets.
 *
 * Refer to section 3.2 in RFC3711 for detailed description of cryptographic
 * context.
 *
 * Cryptographic related parameters, i.e. encryption mode / authentication mode,
 * master encryption key and master salt key are determined outside the scope of
 * SRTP implementation. They can be assigned manually, or can be assigned
 * automatically using some key management protocol, such as MIKEY (RFC3830),
 * SDES (RFC4568) or Phil Zimmermann's ZRTP protocol (RFC6189).
 *
 * @author Bing SU (nova.su@gmail.com)
 * @author Lyubomir Marinov
 */
public class SRTCPCryptoContext
    extends BaseSRTPCryptoContext
{
    private static final Logger logger
            = Logger.getLogger(org.jitsi.impl.neomedia.transform.srtp.SRTCPCryptoContext.class);
    /**
     * Index received so far
     */
    private int receivedIndex = 0;

    /**
     * Index sent so far
     */
    private int sentIndex = 0;

    /**
     * Construct an empty SRTPCryptoContext using ssrc. The other parameters are
     * set to default null value.
     *
     * @param ssrc SSRC of this SRTPCryptoContext
     */
    public SRTCPCryptoContext(int ssrc)
    {
        super(ssrc);
    }

    /**
     * Construct a normal SRTPCryptoContext based on the given parameters.
     *
     * @param ssrc the RTP SSRC that this SRTP cryptographic context protects.
     * @param masterK byte array holding the master key for this SRTP
     * cryptographic context. Refer to chapter 3.2.1 of the RFC about the role
     * of the master key.
     * @param masterS byte array holding the master salt for this SRTP
     * cryptographic context. It is used to computer the initialization vector
     * that in turn is input to compute the session key, session authentication
     * key and the session salt.
     * @param policy SRTP policy for this SRTP cryptographic context, defined
     * the encryption algorithm, the authentication algorithm, etc
     */
    @SuppressWarnings("fallthrough")
    public SRTCPCryptoContext(
            int ssrc,
            byte[] masterK,
            byte[] masterS,
            SRTPPolicy policy)
    {
        super(ssrc, masterK, masterS, policy);
    }

    /**
     * Checks if a packet is a replayed on based on its sequence number. The
     * method supports a 64 packet history relative to the given sequence
     * number. Sequence Number is guaranteed to be real (not faked) through
     * authentication.
     *
     * @param index index number of the SRTCP packet
     * @return true if this sequence number indicates the packet is not a
     * replayed one, false if not
     */
    boolean checkReplay(int index)
    {
        // compute the index of previously received packet and its
        // delta to the new received packet
        long delta = index - receivedIndex;

        if (delta > 0)
            return true; // Packet not yet received
        else if (-delta > REPLAY_WINDOW_SIZE)
            return false; // Packet too old
        else if (((this.replayWindow >> (-delta)) & 0x1) != 0)
            return false; // Packet already received!
        else
            return true; // Packet not yet received
    }

    /**
     * Computes the initialization vector, used later by encryption algorithms,
     * based on the label.
     *
     * @param label label specified for each type of iv
     */
    private void computeIv(byte label)
    {
        System.arraycopy(masterSalt, 0, ivStore, 0, 14);
        ivStore[7] ^= label;
        ivStore[14] = ivStore[15] = 0;
    }

    /**
     * Derives a new SRTPCryptoContext for use with a new SSRC. The method
     * returns a new SRTPCryptoContext initialized with the data of this
     * SRTPCryptoContext. Replacing the SSRC, Roll-over-Counter, and the key
     * derivation rate the application cab use this SRTPCryptoContext to
     * encrypt/decrypt a new stream (Synchronization source) inside one RTP
     * session. Before the application can use this SRTPCryptoContext it must
     * call the deriveSrtpKeys method.
     *
     * @param ssrc The SSRC for this context
     * @return a new SRTPCryptoContext with all relevant data set.
     */
    public SRTCPCryptoContext deriveContext(int ssrc)
    {
        return new SRTCPCryptoContext(ssrc, masterKey, masterSalt, policy);
    }

    /**
     * Derives the srtcp session keys from the master key.
     */
    synchronized public void deriveSrtcpKeys()
    {
        // compute the session encryption key
        computeIv((byte) 3);

        cipherCtr.init(masterKey);
        Arrays.fill(masterKey, (byte) 0);

        Arrays.fill(encKey, (byte) 0);
        cipherCtr.process(encKey, 0, policy.getEncKeyLength(), ivStore);

        if (authKey != null)
        {
            computeIv((byte) 4);
            Arrays.fill(authKey, (byte) 0);
            cipherCtr.process(authKey, 0, policy.getAuthKeyLength(), ivStore);

            switch (policy.getAuthType())
            {
            case SRTPPolicy.HMACSHA1_AUTHENTICATION:
                mac.init(new KeyParameter(authKey));
                break;

            case SRTPPolicy.SKEIN_AUTHENTICATION:
                // Skein MAC uses number of bits as MAC size, not just bytes
                mac.init(
                        new ParametersForSkein(
                                new KeyParameter(authKey),
                                ParametersForSkein.Skein512,
                                tagStore.length * 8));
                break;
            }

            Arrays.fill(authKey, (byte) 0);
        }

        // compute the session salt
        computeIv((byte) 5);
        Arrays.fill(saltKey, (byte) 0);
        cipherCtr.process(saltKey, 0, policy.getSaltKeyLength(), ivStore);
        Arrays.fill(masterSalt, (byte) 0);

        // As last step: initialize cipher with derived encryption key.
        if (cipherF8 != null)
            cipherF8.init(encKey, saltKey);
        cipherCtr.init(encKey);
        Arrays.fill(encKey, (byte) 0);
    }

    /**
     * Performs Counter Mode AES encryption/decryption
     * @param rtcoSenderSsrc the ssrc of the SRTP stream to which this data belongs
     *                       (i.e. the sender ssrc)
     * @param srtcpIndex the SRTCP index
     * @param data the RTCP/SRTCP packet payload to encrypt/decrypt.  Will be
     *             encrypted/decrypted in place.
     */
    private void processDataAESCM(ByteBuffer data, int rtcoSenderSsrc, int srtcpIndex)
    {
        /* Compute the CM IV (refer to chapter 4.1.1 in RFC 3711):
         *
         * k_s   XX XX XX XX XX XX XX XX XX XX XX XX XX XX
         * SSRC              XX XX XX XX
         * index                               XX XX XX XX
         * ------------------------------------------------------XOR
         * IV    XX XX XX XX XX XX XX XX XX XX XX XX XX XX 00 00
         *        0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
         */
        ivStore[0] = saltKey[0];
        ivStore[1] = saltKey[1];
        ivStore[2] = saltKey[2];
        ivStore[3] = saltKey[3];

        // The shifts transform the ssrc and index into network order
        ivStore[4] = (byte) (((rtcoSenderSsrc >> 24) & 0xff) ^ saltKey[4]);
        ivStore[5] = (byte) (((rtcoSenderSsrc >> 16) & 0xff) ^ saltKey[5]);
        ivStore[6] = (byte) (((rtcoSenderSsrc >> 8) & 0xff) ^ saltKey[6]);
        ivStore[7] = (byte) ((rtcoSenderSsrc & 0xff) ^ saltKey[7]);

        ivStore[8] = saltKey[8];
        ivStore[9] = saltKey[9];

        ivStore[10] = (byte) (((srtcpIndex >> 24) & 0xff) ^ saltKey[10]);
        ivStore[11] = (byte) (((srtcpIndex >> 16) & 0xff) ^ saltKey[11]);
        ivStore[12] = (byte) (((srtcpIndex >> 8) & 0xff) ^ saltKey[12]);
        ivStore[13] = (byte) ((srtcpIndex & 0xff) ^ saltKey[13]);

        ivStore[14] = ivStore[15] = 0;

        cipherCtr.process(data.array(), data.arrayOffset(), data.limit(),
                ivStore);
    }

    /**
     * Performs F8 Mode AES encryption/decryption
     *
     * @param header the header of the packet being processed
     * @param payload the payload of the packet being processed.  Will be
     *                encrypted/decrypted in place
     * @param srtcpIndex the SRTCP index
     */
    private void processDataAESF8(ByteBuffer header, ByteBuffer payload, int srtcpIndex)
    {
        // 4 bytes of the iv are zero
        // the first byte of the RTP header is not used.
        ivStore[0] = 0;
        ivStore[1] = 0;
        ivStore[2] = 0;
        ivStore[3] = 0;

        // Need the encryption flag
        srtcpIndex = srtcpIndex | 0x80000000;

        // set the index and the encrypt flag in network order into IV
        ivStore[4] = (byte) (srtcpIndex >> 24);
        ivStore[5] = (byte) (srtcpIndex >> 16);
        ivStore[6] = (byte) (srtcpIndex >> 8);
        ivStore[7] = (byte) srtcpIndex;

        // The fixed header follows and fills the rest of the IV
        // TODO(brian): using this method instead of array copy means we can read the data correctly with
        // header being read-only.  make sure it works (don't know how to force aesf8 crypto right now)
        header.get(ivStore, 8, 8);

        cipherF8.process(payload.array(), payload.arrayOffset(), payload.limit(), ivStore);
    }

    /**
     * Transform a SRTCP packet into a RTCP packet. The method is called when an
     * SRTCP packet was received. Operations done by the method include:
     * authentication check, packet replay check and decryption. Both encryption
     * and authentication functionality can be turned off as long as the
     * SRTPPolicy used in this SRTPCryptoContext requires no encryption and no
     * authentication. Then the packet will be sent out untouched. However, this
     * is not encouraged. If no SRTCP feature is enabled, then we shall not use
     * SRTP TransformConnector. We should use the original method (RTPManager
     * managed transportation) instead.
     *
     * @param srtcpPacket the received RTCP packet
     * @return an unparsed packet containing the decrypted data, or null if authentication
     * or decryption failed.
     *
     */
    synchronized public UnparsedPacket reverseTransformPacket(SrtcpPacket srtcpPacket)
    {
        boolean decrypt = false;
        int tagLength = policy.getAuthTagLength();

        boolean isEncrypted = srtcpPacket.isEncrypted(tagLength);
        if (isEncrypted)
        {
            decrypt = true;
        }

        int index = srtcpPacket.getSrtcpIndex(tagLength);

        /* Replay control */
        if (!checkReplay(index))
        {
            return null;
        }
//        System.out.println("Packet before auth:\n" + ByteBufferKt.toHex(srtcpPacket.getBuffer()));
//        System.out.println("size: " + srtcpPacket.getBuffer().limit() + ", tag length: " + policy.getAuthTagLength());

        /* Authenticate the packet */
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
        {
            // get original authentication data and store in tempStore
            ByteBuffer authTag = srtcpPacket.getAuthTag(tagLength);
            authTag.get(tempStore, 0, tagLength);

            // Shrink packet to remove the authentication tag and index
            // because this is part of authenicated data
            srtcpPacket.removeAuthTagAndSrtcpIndex(tagLength);
//            System.out.println("Packet before auth, after removing auth tag and srtcp index:\n" + ByteBufferKt.toHex(srtcpPacket.getBuffer()));
//            System.out.println("Auth tag: " + ByteBufferKt.toHex(authTag));


            // compute, then save authentication in tagStore
            //TODO(brian): i don't understand the value we pass in here.  it's supposed to be the ROC, but we pass
            // in the entire srtcp index field (including the encrypted flag)?  these are separate in SrtcpPacket
            // so have to re-add the E flag here
            int rocIn = isEncrypted ? index | 0x80000000 : index;
//            System.out.println("rocIn: " + rocIn);
            authenticatePacketHMAC(srtcpPacket.getBuffer(), rocIn);
//            System.out.println("tempStore: " + ByteBufferKt.toHex(ByteBuffer.wrap(tempStore, 0, tagLength)));
//            System.out.println("tagStore: " + ByteBufferKt.toHex(ByteBuffer.wrap(tagStore, 0, tagLength)));

            // compare authentication tags using constant time comparison
            int nonEqual = 0;
            for (int i = 0; i < tagLength; i++)
            {
                nonEqual |= (tempStore[i] ^ tagStore[i]);
            }
            if (nonEqual != 0)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("SRTCP auth failed");
                }
                return null;
            }
        }

        UnparsedPacket decryptedPacket;
        if (decrypt)
        {
//            System.out.println("RTCP packet before decrypt:\n" + ByteBufferKt.toHex(srtcpPacket.getBuffer()));
//            System.out.println("size: " + srtcpPacket.getBuffer().limit());
            int rtcpSenderSsrc = (int)srtcpPacket.getHeader().getSenderSsrc();
            RtcpPacketForCrypto srtcpPacketForDecryption = srtcpPacket.prepareForCrypto();
            /* Decrypt the packet using Counter Mode encryption */
            if (policy.getEncType() == SRTPPolicy.AESCM_ENCRYPTION
                    || policy.getEncType() == SRTPPolicy.TWOFISH_ENCRYPTION)
            {
                processDataAESCM(srtcpPacketForDecryption.getPayload(), rtcpSenderSsrc, index);
            }

            /* Decrypt the packet using F8 Mode encryption */
            else if (policy.getEncType() == SRTPPolicy.AESF8_ENCRYPTION
                    || policy.getEncType() == SRTPPolicy.TWOFISHF8_ENCRYPTION)
            {
                processDataAESF8(srtcpPacketForDecryption.getHeader().getBuffer(), srtcpPacketForDecryption.getPayload(), index);
            }
            decryptedPacket = new UnparsedPacket(srtcpPacketForDecryption.getBuffer());
        }
        else
        {
            decryptedPacket = new UnparsedPacket(srtcpPacket.getBuffer());
        }
        update(index);

//        System.out.println("RTCP packet after decrypt:\n" + ByteBufferKt.toHex(decryptedPacket.getBuffer()));
        return decryptedPacket;
    }


    /**
     * Transform a RTP packet into a SRTP packet. The method is called when a
     * normal RTP packet ready to be sent. Operations done by the transformation
     * may include: encryption, using either Counter Mode encryption, or F8 Mode
     * encryption, adding authentication tag, currently HMC SHA1 method. Both
     * encryption and authentication functionality can be turned off as long as
     * the SRTPPolicy used in this SRTPCryptoContext is requires no encryption
     * and no authentication. Then the packet will be sent out untouched.
     * However, this is not encouraged. If no SRTP feature is enabled, then we
     * shall not use SRTP TransformConnector. We should use the original method
     * (RTPManager managed transportation) instead.
     *
     * @param pkt the RTP packet that is going to be sent out
     */
    synchronized public SrtcpPacket transformPacket(RtcpPacket pkt)
    {
        boolean encrypt = false;
        RtcpPacketForCrypto rtcpPacketForEncryption = pkt.prepareForCrypto();
        /* Encrypt the packet using Counter Mode encryption */
        if (policy.getEncType() == SRTPPolicy.AESCM_ENCRYPTION ||
                policy.getEncType() == SRTPPolicy.TWOFISH_ENCRYPTION)
        {
            int rtcpSenderSsrc = (int)pkt.getHeader().getSenderSsrc();
            processDataAESCM(rtcpPacketForEncryption.getPayload(), rtcpSenderSsrc, sentIndex);
            encrypt = true;
        }
        /* Encrypt the packet using F8 Mode encryption */
        else if (policy.getEncType() == SRTPPolicy.AESF8_ENCRYPTION ||
                policy.getEncType() == SRTPPolicy.TWOFISHF8_ENCRYPTION)
        {
            processDataAESF8(pkt.getHeader().getBuffer(), rtcpPacketForEncryption.getPayload(), sentIndex);
            encrypt = true;
        }
        SrtcpPacket srtcpPacket = rtcpPacketForEncryption.toOtherRtcpPacketType((header, backingBuffer) ->
                new SrtcpPacket(header, rtcpPacketForEncryption.getPayload(), backingBuffer));
        int index = 0;
        if (encrypt)
        {
            index = sentIndex | 0x80000000;
        }

        // Authenticate the packet
        // The authenticate method gets the index via parameter and stores
        // it in network order in rbStore variable.
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
        {
            authenticatePacketHMAC(srtcpPacket.getBuffer(), index);
            //TODO(brian): add separate helper to allocate space for srtcp index and auth tag at once?
            srtcpPacket.addSrtcpIndex(ByteBuffer.wrap(rbStore, 0, 4).getInt());
            srtcpPacket.addAuthTag(ByteBuffer.wrap(tagStore, 0, policy.getAuthTagLength()));
        }
        sentIndex++;
        sentIndex &= ~0x80000000;       // clear possible overflow

        return srtcpPacket;
    }

    /**
     * Updates the SRTP packet index. The method is called after all checks were
     * successful.
     *
     * @param index index number of the accepted packet
     */
    private void update(int index)
    {
        int delta = receivedIndex - index;

        /* update the replay bit mask */
        if (delta > 0)
        {
            replayWindow = replayWindow << delta;
            replayWindow |= 1;
        }
        else
        {
            replayWindow |= ( 1 << delta );
        }

        receivedIndex = index;
    }
}
