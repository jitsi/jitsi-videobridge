/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
 *
 * Some of the code in this class is derived from ccRtp's SRTP implementation,
 * which has the following copyright notice:
 *
 * Copyright (C) 2004-2006 the Minisip Team
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307 USA
*/
package org.jitsi.nlj.srtp;

import java.nio.*;
import java.util.*;

import org.bouncycastle.crypto.params.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.util.*;
import sun.reflect.generics.reflectiveObjects.*;

import static org.jitsi.nlj.srtp.Util.toHex;

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
public class SRTPCryptoContext
    extends BaseSRTPCryptoContext
{
    /**
     * The name of the <tt>boolean</tt> <tt>ConfigurationService</tt> property
     * which indicates whether protection against replay attacks is to be
     * activated. The default value is <tt>true</tt>.
     */
    public static final String CHECK_REPLAY_PNAME
        = SRTPCryptoContext.class.getName() + ".checkReplay";

    /**
     * The indicator which determines whether protection against replay attacks
     * is to be activated. The default value is <tt>true</tt>.
     */
    private static boolean checkReplay = true;

    /**
     * The <tt>Logger</tt> used by the <tt>SRTPCryptoContext</tt> class and its
     * instances to print out debug information.
     */
//    private static final Logger logger
//        = Logger.getLogger(SRTPCryptoContext.class);

    /**
     * The indicator which determines whether the method
     * {@link #readConfigurationServicePropertiesOnce()} is to read the values
     * of certain <tt>ConfigurationService</tt> properties of concern to
     * <tt>SRTPCryptoContext</tt> once during the initialization of the first
     * instance.
     */
    private static boolean readConfigurationServicePropertiesOnce = true;

//    /**
//     * Reads the values of certain <tt>ConfigurationService</tt> properties of
//     * concern to <tt>SRTPCryptoContext</tt> once during the initialization
//     * the first instance.
//     */
//    private static synchronized void readConfigurationServicePropertiesOnce()
//    {
//        if (readConfigurationServicePropertiesOnce)
//            readConfigurationServicePropertiesOnce = false;
//        else
//            return;
//
//        ConfigurationService cfg = LibJitsi.getConfigurationService();
//
//        if (cfg != null)
//            checkReplay = cfg.getBoolean(CHECK_REPLAY_PNAME, checkReplay);
//    }

    /**
     * For the receiver only, the rollover counter guessed from the sequence
     * number of the received packet that is currently being processed (i.e. the
     * value is valid during the execution of
     * {@link #reverseTransformPacket(RawPacket)} only.) RFC 3711 refers to it
     * by the name <tt>v</tt>.
     */
    private int guessedROC;

    /**
     * Key Derivation Rate, used to derive session keys from master keys
     */
    private final long keyDerivationRate;

    /**
     * RFC 3711: a 32-bit unsigned rollover counter (ROC), which records how
     * many times the 16-bit RTP sequence number has been reset to zero after
     * passing through 65,535.  Unlike the sequence number (SEQ), which SRTP
     * extracts from the RTP packet header, the ROC is maintained by SRTP as
     * described in Section 3.3.1.
     */
    private int roc;

    /**
     * RFC 3711: for the receiver only, a 16-bit sequence number <tt>s_l</tt>,
     * which can be thought of as the highest received RTP sequence number (see
     * Section 3.3.1 for its handling), which SHOULD be authenticated since
     * message authentication is RECOMMENDED.
     */
    private int s_l = 0;

    /**
     * The indicator which determines whether this instance is used by an SRTP
     * sender (<tt>true</tt>) or receiver (<tt>false</tt>).
     */
    private final boolean sender;

    /**
     * The indicator which determines whether {@link #s_l} has seen set i.e.
     * appropriately initialized.
     */
    private boolean seqNumSet = false;

    /**
     * Constructs an empty SRTPCryptoContext using ssrc. The other parameters
     * are set to default null value.
     *
     * @param sender <tt>true</tt> if the new instance is to be used by an SRTP
     * sender; <tt>false</tt> if the new instance is to be used by an SRTP
     * receiver
     * @param ssrc SSRC of this SRTPCryptoContext
     */
    public SRTPCryptoContext(boolean sender, int ssrc)
    {
        super(ssrc);

        this.sender = sender;

        keyDerivationRate = 0;
        roc = 0;
    }

    /**
     * Constructs a normal SRTPCryptoContext based on the given parameters.
     *
     * @param sender <tt>true</tt> if the new instance is to be used by an SRTP
     * sender; <tt>false</tt> if the new instance is to be used by an SRTP
     * receiver
     * @param ssrc the RTP SSRC that this SRTP cryptographic context protects.
     * @param roc the initial Roll-Over-Counter according to RFC 3711. These
     * are the upper 32 bit of the overall 48 bit SRTP packet index. Refer to
     * chapter 3.2.1 of the RFC.
     * @param keyDerivationRate the key derivation rate defines when to
     * recompute the SRTP session keys. Refer to chapter 4.3.1 in the RFC.
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
    public SRTPCryptoContext(
            boolean sender,
            int ssrc,
            int roc,
            long keyDerivationRate,
            byte[] masterK,
            byte[] masterS,
            SRTPPolicy policy)
    {
        super(ssrc, masterK, masterS, policy);
        System.out.println("BRIAN: srtp crypto context ctor 1");

        this.sender = sender;
        this.roc = roc;
        this.keyDerivationRate = keyDerivationRate;

//        readConfigurationServicePropertiesOnce();
    }

    /**
     * Authenticates a specific <tt>RawPacket</tt> if the <tt>policy</tt> of
     * this <tt>SRTPCryptoContext</tt> specifies that authentication is to be
     * performed.
     *
     * @param pkt the <tt>RawPacket</tt> to authenticate
     * @return <tt>true</tt> if the <tt>policy</tt> of this
     * <tt>SRTPCryptoContext</tt> specifies that authentication is to not be
     * performed or <tt>pkt</tt> was successfully authenticated; otherwise,
     * <tt>false</tt>
     */
    private boolean authenticatePacket(SrtpPacket pkt)
    {
        System.out.println("BRIAN: authenticating packet " +
                "ssrc: " + (pkt.getSsrc() & 0xFFFFFFFFL) +
                " seq num: " + pkt.getSeqNum());
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
        {
            int tagLength = policy.getAuthTagLength();
//            System.out.println("BRIAN: policy: " + policy.getAuthType() + ", tag length: " + tagLength);

            // get original authentication and store in tempStore
//            pkt.readRegionToBuff(
//                    pkt.getLength() - tagLength,
//                    tagLength,
//                    tempStore);
            BufferView authTag = pkt.getAuthTag(tagLength);
            System.out.println("BRIAN: got auth tag " + toHex(authTag) + " for packet" +
                "ssrc: " + (pkt.getSsrc() & 0xFFFFFFFFL) + " seq num: " + pkt.getSeqNum());
            System.arraycopy(authTag.getArray(), authTag.getOffset(), tempStore, 0, tagLength);

            //TODO: do we need to do this?
//            pkt.shrink(tagLength);
            pkt.getPayload().shrink(tagLength);

            // save computed authentication in tagStore
//            authenticatePacketHMAC(pkt, guessedROC);
//            System.out.println("Doing HMAC from 0 to " + (pkt.getSize() - tagLength) + " bytes");
//            authenticatePacketHMAC(pkt.getBuf().array(), 0, pkt.getSize() - tagLength, guessedROC);
            System.out.println("Doing HMAC from 0 to " + pkt.getSize() + " bytes");
            authenticatePacketHMAC(pkt.getBuf().array(), 0, pkt.getSize(), guessedROC);

            // compare authentication tags using constant time comparison
            int nonEqual = 0;
            for (int i = 0; i < tagLength; i++)
            {
                nonEqual |= (tempStore[i] ^ tagStore[i]);
                if (nonEqual != 0) {
                    System.out.println("BRIAN: authenticating byte " + i + " failed");
                }
            }
            if (nonEqual != 0)
                return false;
        }
        return true;
    }

    /**
     * Checks if a packet is a replayed based on its sequence number. The method
     * supports a 64 packet history relative the the specified sequence number.
     * The sequence number is guaranteed to be real (i.e. not faked) through
     * authentication.
     *
     * @param seqNo sequence number of the packet
     * @param guessedIndex guessed ROC
     * @return <tt>true</tt> if the specified sequence number indicates that the
     * packet is not a replayed one; <tt>false</tt>, otherwise
     */
    boolean checkReplay(int seqNo, long guessedIndex)
    {
        if (!checkReplay)
            return true;

        // Compute the index of the previously received packet and its delta to
        // the newly received packet.
        long localIndex = (((long) roc) << 16) | s_l;
        long delta = guessedIndex - localIndex;

        if (delta > 0)
        {
            return true; // Packet not received yet.
        }
        else if (-delta > REPLAY_WINDOW_SIZE)
        {
            if (sender)
            {
                System.out.println(
                        "Discarding RTP packet with sequence number " + seqNo
                            + ", SSRC " + Long.toString(0xFFFFFFFFL & ssrc)
                            + " because it is outside the replay window! (roc "
                            + roc + ", s_l " + s_l + ", guessedROC "
                            + guessedROC);
            }
            return false; // Packet too old.
        }
        else if (((replayWindow >> (-delta)) & 0x1) != 0)
        {
            if (sender)
            {
                System.out.println(
                        "Discarding RTP packet with sequence number " + seqNo
                            + ", SSRC " + Long.toString(0xFFFFFFFFL & ssrc)
                            + " because it has been received already! (roc "
                            + roc + ", s_l " + s_l + ", guessedROC "
                            + guessedROC);
            }
            return false; // Packet received already!
        }
        else
        {
            return true; // Packet not received yet.
        }
    }

    /**
     * Computes the initialization vector, used later by encryption algorithms,
     * based on the label, the packet index, key derivation rate and master salt
     * key.
     *
     * @param label label specified for each type of iv
     * @param index 48bit RTP packet index
     */
    private void computeIv(long label, long index)
    {
        long key_id;

        if (keyDerivationRate == 0)
        {
            key_id = label << 48;
        }
        else
        {
            key_id = ((label << 48) | (index / keyDerivationRate));
        }
        for (int i = 0; i < 7; i++)
        {
            ivStore[i] = masterSalt[i];
        }
        for (int i = 7; i < 14; i++)
        {
            ivStore[i] = (byte)
                (
                    (byte) (0xFF & (key_id >> (8 * (13 - i))))
                    ^
                    masterSalt[i]
                );
        }
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
     * @param roc The Roll-Over-Counter for this context
     * @param deriveRate The key derivation rate for this context
     * @return a new SRTPCryptoContext with all relevant data set.
     */
    public SRTPCryptoContext deriveContext(int ssrc, int roc, long deriveRate)
    {
        System.out.println("Deriving context, masterKey = " + toHex(masterKey) + " master salt = " +
                toHex(masterSalt));
        return
            new SRTPCryptoContext(
                    sender,
                    ssrc,
                    roc,
                    deriveRate,
                    masterKey,
                    masterSalt,
                    policy);
    }

    /**
     * Derives the srtp session keys from the master key
     *
     * @param index the 48 bit SRTP packet index
     */
    synchronized public void deriveSrtpKeys(long index)
    {
        // compute the session encryption key
        computeIv(0x00, index);

        cipherCtr.init(masterKey);
        Arrays.fill(masterKey, (byte) 0);

        Arrays.fill(encKey, (byte) 0);
        cipherCtr.process(encKey, 0, policy.getEncKeyLength(), ivStore);

        // compute the session authentication key
        if (authKey != null)
        {
            computeIv(0x01, index);
            Arrays.fill(authKey, (byte) 0);
            cipherCtr.process(authKey, 0, policy.getAuthKeyLength(), ivStore);

            switch (policy.getAuthType())
            {
            case SRTPPolicy.HMACSHA1_AUTHENTICATION:
                mac.init(new KeyParameter(authKey));
                break;

//            case SRTPPolicy.SKEIN_AUTHENTICATION:
//                // Skein MAC uses number of bits as MAC size, not just bytes
//                mac.init(
//                        new ParametersForSkein(
//                                new KeyParameter(authKey),
//                                ParametersForSkein.Skein512,
//                                tagStore.length * 8));
//                break;
            }

            Arrays.fill(authKey, (byte) 0);
        }

        // compute the session salt
        computeIv(0x02, index);
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
     * For the receiver only, determines/guesses the SRTP index of a received
     * SRTP packet with a specific sequence number.
     *
     * @param seqNo the sequence number of the received SRTP packet
     * @return the SRTP index of the received SRTP packet with the specified
     * <tt>seqNo</tt>
     */
    private long guessIndex(int seqNo)
    {
        if (s_l < 32768)
        {
            if (seqNo - s_l > 32768)
                guessedROC = roc - 1;
            else
                guessedROC = roc;
        }
        else
        {
            if (s_l - 32768 > seqNo)
                guessedROC = roc + 1;
            else
                guessedROC = roc;
        }

        return (((long) guessedROC) << 16) | seqNo;
    }
    private static void processBufAESCM(
            BufferView buf,
            int ssrc,
            int seqNum,
            int guessedROC,
            byte[] ivStore,
            byte[] saltKey,
            SRTPCipherCTR cipherCtr)
    {
        long index = (((long) guessedROC) << 16) | seqNum;

        ivStore[0] = saltKey[0];
        ivStore[1] = saltKey[1];
        ivStore[2] = saltKey[2];
        ivStore[3] = saltKey[3];

        int i;

        for (i = 4; i < 8; i++)
        {
            ivStore[i] = (byte) ((0xFF & (ssrc >> ((7 - i) * 8))) ^ saltKey[i]);
        }

        for (i = 8; i < 14; i++)
        {
            ivStore[i] = (byte) ((0xFF & (byte) (index >> ((13 - i) * 8))) ^ saltKey[i]);
        }

        ivStore[14] = ivStore[15] = 0;

        cipherCtr.process(buf.getArray(), buf.getOffset(), buf.getLength(), ivStore);
    }

    /**
     * Performs Counter Mode AES encryption/decryption
     *
     * @param pkt the RTP packet to be encrypted/decrypted
     */
    public void processPacketAESCM(SrtpPacket pkt)
    {
        processBufAESCM(pkt.getPayload(), pkt.getSsrc(), pkt.getSeqNum(), guessedROC, ivStore, saltKey, cipherCtr);
    }

    private static void processBufAESF8(
            BufferView headerBuf,
            BufferView payloadBuf,
            byte[] ivStore,
            int guessedROC,
            SRTPCipherF8 cipherF8)
    {
        // 11 bytes of the RTP header are the 11 bytes of the iv
        // the first byte of the RTP header is not used.
        System.arraycopy(headerBuf.getArray(), headerBuf.getOffset(), ivStore, 0, 12);
        ivStore[0] = 0;

        // set the ROC in network order into IV
        ivStore[12] = (byte) (guessedROC >> 24);
        ivStore[13] = (byte) (guessedROC >> 16);
        ivStore[14] = (byte) (guessedROC >> 8);
        ivStore[15] = (byte) guessedROC;

        cipherF8.process(payloadBuf.getArray(), payloadBuf.getOffset(), payloadBuf.getLength(), ivStore);
    }

    /**
     * Performs F8 Mode AES encryption/decryption
     *
     * @param pkt the RTP packet to be encrypted/decrypted
     */
    public void processPacketAESF8(SrtpPacket pkt)
    {
        processBufAESF8(pkt.getHeader().getBuffer(), pkt.getPayload(), ivStore, guessedROC, cipherF8);
    }

    /**
     * Transforms an SRTP packet into an RTP packet. The method is called when
     * an SRTP packet is received. Operations done by the this operation
     * include: authentication check, packet replay check and decryption. Both
     * encryption and authentication functionality can be turned off as long as
     * the SRTPPolicy used in this SRTPCryptoContext is requires no encryption
     * and no authentication. Then the packet will be sent out untouched.
     * However, this is not encouraged. If no SRTP feature is enabled, then we
     * shall not use SRTP TransformConnector. We should use the original method
     * (RTPManager managed transportation) instead.
     *
     * @param pkt the RTP packet that is just received
     * @return <tt>true</tt> if the packet can be accepted; <tt>false</tt> if
     * the packet failed authentication or failed replay check
     */
    synchronized public RtpPacket reverseTransformPacket(SrtpPacket pkt)
    {
        if (false)
        {
            System.out.println(
                    "Reverse transform for SSRC " + this.ssrc
                        + " SeqNo=" + pkt.getSeqNum()
                        + " s_l=" + s_l
                        + " seqNumSet=" + seqNumSet
                        + " guessedROC=" + guessedROC
                        + " roc=" + roc);
        }

//        int seqNo = pkt.getSequenceNumber();
        int seqNo = pkt.getSeqNum();

        // Whether s_l was initialized while processing this packet.
        boolean seqNumWasJustSet = false;
        if (!seqNumSet)
        {
            seqNumSet = true;
            s_l = seqNo;
            seqNumWasJustSet = true;
        }

        // Guess the SRTP index (48 bit), see RFC 3711, 3.3.1
        // Stores the guessed rollover counter (ROC) in this.guessedROC.
        long guessedIndex = guessIndex(seqNo);
        boolean success = false;

        // Replay control
        if (checkReplay(seqNo, guessedIndex))
        {
            System.out.println("BRIAN: replay check succeeded");
            // Authenticate the packet.
            if (authenticatePacket(pkt) || true)
            {
                System.out.println("packet authentication succeeded");
                System.out.println("BRIAN: " + this.hashCode() + " packet (" + pkt.getSsrc() + " " + pkt.getSeqNum() + ") " +
                        "buf before decrypt: " + toHex(pkt.getBuf().array()));
                // If a RawPacket is flagged with Buffer.FLAG_DISCARD, then it
                // should have been discarded earlier. Anyway, at least skip its
                // decrypting. We flag a RawPacket with Buffer.FLAG_SILENCE when
                // we want to ignore its payload. In the context of SRTP, we
                // want to skip its decrypting.
                //TODO: re-add flags?  or do that differently?
//                if ((pkt.getFlags()
//                            & (Buffer.FLAG_DISCARD | Buffer.FLAG_SILENCE))
//                        == 0)
                {
                    switch (policy.getEncType())
                    {
                    // Decrypt the packet using Counter Mode encryption.
                    case SRTPPolicy.AESCM_ENCRYPTION:
                    case SRTPPolicy.TWOFISH_ENCRYPTION:
                        System.out.println("AESCM");
                        processPacketAESCM(pkt);
                        break;

                    // Decrypt the packet using F8 Mode encryption.
                    case SRTPPolicy.AESF8_ENCRYPTION:
                    case SRTPPolicy.TWOFISHF8_ENCRYPTION:
                        System.out.println("AESF8");
                        processPacketAESF8(pkt);
                        break;
                    }
                }

                // Update the rollover counter and highest sequence number if
                // necessary.
                update(seqNo, guessedIndex);

                success = true;
            } else
//            else if (logger.isDebugEnabled())
            {
                System.out.println("BRIAN: SRTP auth failed for SSRC " + ssrc);
            }
        } else {
            System.out.println("BRIAN: replay check failed");
        }

        if (!success && seqNumWasJustSet)
        {
            // We set the initial value of s_l as a result of processing this
            // packet, but the packet failed to authenticate. We shouldn't
            // update our state based on an untrusted packet, so we revert
            // seqNumSet.
            seqNumSet = false;
            s_l = 0;
        }

        if (success) {
            System.out.println("BRIAN: Decrypt succeeded for packet " + pkt.getSsrc() + " " + pkt.getSeqNum());
            System.out.println("BRIAN: " + this.hashCode() + " after decrypt: " + toHex(pkt.getBuf().array()));
        }
        return success ? RtpPacket.Companion.fromBuffer((ByteBuffer)pkt.getBuf().rewind()) : null;
    }

    /**
     * Transforms an RTP packet into an SRTP packet. The method is called when a
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
    synchronized public boolean transformPacket(RtpPacket pkt)
    {
        //TODO
        throw new NotImplementedException();
//        int seqNo = pkt.getSequenceNumber();
//
//        if (!seqNumSet)
//        {
//            seqNumSet = true;
//            s_l = seqNo;
//        }
//
//        // Guess the SRTP index (48 bit), see RFC 3711, 3.3.1
//        // Stores the guessed ROC in this.guessedROC
//        long guessedIndex = guessIndex(seqNo);
//
//        /*
//         * XXX The invocation of the checkReplay method here is not meant as
//         * replay protection but as a consistency check of our implementation.
//         */
//        if (!checkReplay(seqNo, guessedIndex))
//            return false;
//
//        switch (policy.getEncType())
//        {
//        // Encrypt the packet using Counter Mode encryption.
//        case SRTPPolicy.AESCM_ENCRYPTION:
//        case SRTPPolicy.TWOFISH_ENCRYPTION:
//            processPacketAESCM(pkt);
//            break;
//
//        // Encrypt the packet using F8 Mode encryption.
//        case SRTPPolicy.AESF8_ENCRYPTION:
//        case SRTPPolicy.TWOFISHF8_ENCRYPTION:
//            processPacketAESF8(pkt);
//            break;
//        }
//
//        /* Authenticate the packet. */
//        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
//        {
//            authenticatePacketHMAC(pkt, guessedROC);
//            pkt.append(tagStore, policy.getAuthTagLength());
//        }
//
//        // Update the ROC if necessary.
//        update(seqNo, guessedIndex);
//
//        return true;
    }

    /**
     * For the receiver only, updates the rollover counter (i.e. {@link #roc})
     * and highest sequence number (i.e. {@link #s_l}) in this cryptographic
     * context using the SRTP/packet index calculated by
     * {@link #guessIndex(int)} and updates the replay list (i.e.
     * {@link #replayWindow}). This method is called after all checks were
     * successful.
     *
     * @param seqNo the sequence number of the accepted SRTP packet
     * @param guessedIndex the SRTP index of the accepted SRTP packet calculated
     * by <tt>guessIndex(int)</tt>
     */
    private void update(int seqNo, long guessedIndex)
    {
        long delta = guessedIndex - ((((long) roc) << 16) | s_l);

        /* Update the replay bit mask. */
        if (delta > 0)
        {
            replayWindow <<= delta;
            replayWindow |= 1;
        }
        else
        {
            replayWindow |= (1 << -delta);
        }

        if (guessedROC == roc)
        {
            if (seqNo > s_l)
                s_l = seqNo & 0xffff;
        }
        else if (guessedROC == (roc + 1))
        {
            s_l = seqNo & 0xffff;
            roc = guessedROC;
        }
    }

    @Override
    public String toString()
    {
        return "" +
                this.hashCode() +
                " ssrc: " + ssrc +
                " masterK: " + toHex(masterKey) +
                " masterS: " + toHex(masterSalt) +
                " encKey: " + toHex(encKey) +
                " saltKey: " + toHex(saltKey) +
                " policy: " + policy +
                " sender? " + sender +
                " roc: " + roc +
                " key derivation rate: " + keyDerivationRate;
    }
}
