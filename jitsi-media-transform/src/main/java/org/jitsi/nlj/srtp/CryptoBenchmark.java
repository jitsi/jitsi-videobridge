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
 */
package org.jitsi.nlj.srtp;

import java.nio.*;
import java.security.*;
import java.util.*;

import javax.crypto.*;

import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.Mac;
import org.bouncycastle.crypto.digests.*;
import org.bouncycastle.crypto.engines.*;
import org.bouncycastle.crypto.macs.*;
import org.bouncycastle.crypto.params.*;

/**
 *
 * @author Lyubomir Marinov
 */
public class CryptoBenchmark
{
    public static void main(String[] args)
        throws Exception
    {
        boolean benchmarkJavaxCryptoCipher = false;

        for (String arg : args)
        {
            if ("-javax-crypto-cipher".equalsIgnoreCase(arg))
                benchmarkJavaxCryptoCipher = true;
        }

        Provider sunPKCS11
            = new sun.security.pkcs11.SunPKCS11(
                    "--name=CryptoBenchmark\\n"
                        + "nssDbMode=noDb\\n"
                        + "attributes=compatibility");
        Provider sunJCE = Security.getProvider("SunJCE");

//        for (Provider provider : new Provider[] { sunPKCS11, sunJCE })
//            for (Provider.Service service : provider.getServices())
//                if ("Cipher".equalsIgnoreCase(service.getType()))
//                    System.err.println(service);

        // org.bouncycastle.crypto.Digest & java.security.MessageDigest
        Digest[] digests
            = {
                new SHA1Digest(),
            };
        MessageDigest[] messageDigests
            = {
                MessageDigest.getInstance("SHA-1"),
                MessageDigest.getInstance("SHA-1", sunPKCS11)
            };
        int maxDigestSize = 0;
        int maxByteLength = 0;

        for (Digest digest : digests)
        {
            int digestSize = digest.getDigestSize();

            if (maxDigestSize < digestSize)
                maxDigestSize = digestSize;

            int byteLength
                = (digest instanceof ExtendedDigest)
                    ? ((ExtendedDigest) digest).getByteLength()
                    : 64;

            if (maxByteLength < byteLength)
                maxByteLength = byteLength;

            System.err.println(
                    digest.getClass().getName() + ": digestSize " + digestSize
                        + ", byteLength " + byteLength + ".");
        }
        for (MessageDigest messageDigest : messageDigests)
        {
            int digestLength = messageDigest.getDigestLength();

            if (maxDigestSize < digestLength)
                maxDigestSize = digestLength;

            System.err.println(
                    messageDigest.getProvider().getClass().getName()
                        + ": digestLength " + digestLength + ".");
        }

        // org.bouncycastle.crypto.BlockCipher
        BlockCipher[] ciphers
            = {
                new AESFastEngine(),
                new BlockCipherAdapter(
                        Cipher.getInstance("AES_128/ECB/NoPadding", sunPKCS11)),
                new BlockCipherAdapter(
                        Cipher.getInstance("AES_128/ECB/NoPadding", sunJCE))
            };

        for (BlockCipher cipher : ciphers)
        {
            Class<?> clazz;

            if (cipher instanceof BlockCipherAdapter)
            {
                clazz
                    = ((BlockCipherAdapter) cipher).getCipher().getProvider()
                        .getClass();
            }
            else
            {
                clazz = cipher.getClass();
            }
            System.err.println(
                    clazz.getName() + ": blockSize " + cipher.getBlockSize());
        }

        // org.bouncycastle.crypto.Mac
        Mac[] macs
            = {
                new HMac(new SHA1Digest()),
                new OpenSSLHMAC(OpenSSLHMAC.SHA1)
            };

        Random random = new Random(System.currentTimeMillis());
        byte[] in = new byte[1024 * maxByteLength];
        ByteBuffer inNIO = ByteBuffer.allocateDirect(in.length);
        byte[] out = new byte[maxDigestSize];
        ByteBuffer outNIO = ByteBuffer.allocateDirect(out.length);
        long time0;
        int dMax = Math.max(digests.length, messageDigests.length);
        final int iEnd = 1000, jEnd = 1000;
//        Base64.Encoder byteEncoder = Base64.getEncoder().withoutPadding();

        inNIO.order(ByteOrder.nativeOrder());
        outNIO.order(ByteOrder.nativeOrder());

        for (int i = 0; i < iEnd; ++i)
        {
            System.err.println("========================================");

            random.nextBytes(in);
            inNIO.clear();
            inNIO.put(in);

            // org.bouncycastle.crypto.BlockCipher
            time0 = 0;
            for (BlockCipher blockCipher : ciphers)
            {
                Cipher cipher;
                Class<?> clazz;

                if (blockCipher instanceof BlockCipherAdapter)
                {
                    cipher = ((BlockCipherAdapter) blockCipher).getCipher();
                    clazz = cipher.getProvider().getClass();
                }
                else
                {
                    cipher = null;
                    clazz = blockCipher.getClass();
                }

                int blockSize = blockCipher.getBlockSize();

                blockCipher.init(true, new KeyParameter(in, 0, blockSize));

                long startTime, endTime;
                int offEnd = in.length - blockSize;

                if (cipher != null && benchmarkJavaxCryptoCipher)
                {
                    startTime = System.nanoTime();
                    for (int j = 0; j < jEnd; ++j)
                    {
                        for (int off = 0; off < offEnd;)
                        {
                            int nextOff = off + blockSize;

                            inNIO.limit(nextOff);
                            inNIO.position(off);
                            outNIO.clear();
                            cipher.update(inNIO, outNIO);
                            off = nextOff;
                        }
//                        cipher.doFinal();
                    }
                    endTime = System.nanoTime();

                    outNIO.clear();
                    outNIO.get(out);
                }
                else
                {
                    startTime = System.nanoTime();
                    for (int j = 0; j < jEnd; ++j)
                    {
                        for (int off = 0; off < offEnd;)
                        {
                            blockCipher.processBlock(in, off, out, 0);
                            off += blockSize;
                        }
//                        blockCipher.reset();
                    }
                    endTime = System.nanoTime();
                }

                long time = endTime - startTime;

                if (time0 == 0)
                    time0 = time;
                Arrays.fill(out, blockSize, out.length, (byte) 0);
                System.err.println(
                        clazz.getName() + ": ratio "
                            + String.format("%.2f", time / (double) time0)
                            + ", time " + time + ", out "
                            /*+ byteEncoder.encodeToString(out)*/ + ".");
            }

            // org.bouncycastle.crypto.Digest & java.security.MessageDigest
            System.err.println("----------------------------------------");

            time0 = 0;
            for (int d = 0; d < dMax; ++d)
            {
                Arrays.fill(out, (byte) 0);

                // org.bouncycastle.crypto.Digest
                Digest digest = (d < digests.length) ? digests[d] : null;
                int byteLength
                    = (digest instanceof ExtendedDigest)
                        ? ((ExtendedDigest) digest).getByteLength()
                        : 64;
                long startTime, endTime;
                int offEnd = in.length - byteLength;

                if (digest != null)
                {
                    startTime = System.nanoTime();
                    for (int j = 0; j < jEnd; ++j)
                    {
                        for (int off = 0; off < offEnd;)
                        {
                            digest.update(in, off, byteLength);
                            off += byteLength;
                        }
                        digest.doFinal(out, 0);
                    }
                    endTime = System.nanoTime();

                    long time = endTime - startTime;

                    if (time0 == 0)
                        time0 = time;
                    System.err.println(
                            digest.getClass().getName() + ": ratio "
                                + String.format("%.2f", time / (double) time0)
                                + ", time " + time + ", digest "
                                /*+ byteEncoder.encodeToString(out)*/ + ".");
                }

                // java.security.MessageDigest
                MessageDigest messageDigest
                    = (d < messageDigests.length) ? messageDigests[d] : null;

                if (messageDigest != null)
                {
                    startTime = System.nanoTime();
                    for (int j = 0; j < jEnd; ++j)
                    {
                        for (int off = 0; off < offEnd;)
                        {
                            messageDigest.update(in, off, byteLength);
                            off += byteLength;
                        }
                        messageDigest.digest();
                    }
                    endTime = System.nanoTime();

                    long time = endTime - startTime;

                    if (time0 == 0)
                        time0 = time;
                    System.err.println(
                            messageDigest.getProvider().getClass().getName()
                                + ": ratio "
                                + String.format("%.2f", time / (double) time0)
                                + ", time " + (endTime - startTime)
                                + ", digest " /*+ byteEncoder.encodeToString(t)*/
                                + ".");
                }
            }

            // org.bouncycastle.crypto.Mac
            System.err.println("----------------------------------------");

            time0 = 0;
            for (Mac mac : macs)
            {
                mac.init(new KeyParameter(in, 0, maxByteLength));

                long startTime, endTime;
                int offEnd = in.length - maxByteLength;

                startTime = System.nanoTime();
                for (int j = 0; j < jEnd; ++j)
                {
                    for (int off = 0; off < offEnd; off = off + maxByteLength)
                        mac.update(in, off, maxByteLength);
                    mac.doFinal(out, 0);
                }
                endTime = System.nanoTime();

                int macSize = mac.getMacSize();
                long time = endTime - startTime;

                if (time0 == 0)
                    time0 = time;
                Arrays.fill(out, macSize, out.length, (byte) 0);
                System.err.println(
                        mac.getClass().getName() + ": ratio "
                            + String.format("%.2f", time / (double) time0)
                            + ", time " + time + ", out "
                            /*+ byteEncoder.encodeToString(out)*/ + ".");
            }
        }
    }
}
