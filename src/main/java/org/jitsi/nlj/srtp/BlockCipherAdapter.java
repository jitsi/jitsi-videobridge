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

import java.security.*;

import javax.crypto.*;
import javax.crypto.spec.*;

import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.params.*;

/**
 * Adapts the <tt>javax.crypto.Cipher</tt> class to the
 * <tt>org.bouncycastle.crypto.BlockCipher</tt> interface. 
 *
 * @author Lyubomir Marinov
 */
public class BlockCipherAdapter
    implements BlockCipher
{
    /**
     * The <tt>Logger</tt> used by the <tt>BlockCipherAdapter</tt> class and its
     * instance to print out debug information.
     */
//    private static final Logger logger
//        = Logger.getLogger(BlockCipherAdapter.class);

    /**
     * The name of the algorithm implemented by this instance.
     */
    private final String algorithmName;

    /**
     * The block size in bytes of this cipher.
     */
    private final int blockSize;

    /**
     * The <tt>javax.crypto.Cipher</tt> instance which is adapted to the
     * <tt>org.bouncycastle.crypto.BlockCipher</tt> interface by this instance.
     */
    private final Cipher cipher;

    /**
     * Initializes a new <tt>BlockCipherAdapter</tt> instance which is to adapt
     * a specific <tt>javax.crypto.Cipher</tt> instance to the
     * <tt>org.bouncycastle.crypto.BlockCipher</tt> interface.
     *
     * @param cipher the <tt>javax.crypto.Cipher</tt> instance to be adapted to
     * the <tt>org.bouncycastle.crypto.BlockCipher</tt> interface by the new
     * instance
     */
    public BlockCipherAdapter(Cipher cipher)
    {
        if (cipher == null)
            throw new NullPointerException("cipher");

        this.cipher = cipher;

        // The value of the algorithm property of javax.crypto.Cipher is a
        // transformation i.e. it may contain mode and padding. However, the
        // algorithm name alone is necessary elsewhere.
        String algorithmName = cipher.getAlgorithm();

        if (algorithmName != null)
        {
            int endIndex = algorithmName.indexOf('/');

            if (endIndex > 0)
                algorithmName = algorithmName.substring(0, endIndex);

            int len = algorithmName.length();

            if ((len > 4)
                    && (algorithmName.endsWith("_128")
                            || algorithmName.endsWith("_192")
                            || algorithmName.endsWith("_256")))
            {
                algorithmName = algorithmName.substring(0, len - 4);
            }
        }
        this.algorithmName = algorithmName;

        this.blockSize = cipher.getBlockSize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAlgorithmName()
    {
        return algorithmName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBlockSize()
    {
        return blockSize;
    }

    /**
     * Gets the <tt>javax.crypto.Cipher</tt> instance which is adapted to the
     * <tt>org.bouncycastle.crypto.BlockCipher</tt> interface by this instance.
     *
     * @return the <tt>javax.crypto.Cipher</tt> instance which is adapted to the
     * <tt>org.bouncycastle.crypto.BlockCipher</tt> interface by this instance
     */
    public Cipher getCipher()
    {
        return cipher;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(boolean forEncryption, CipherParameters params)
        throws IllegalArgumentException
    {
        Key key = null;

        if (params instanceof KeyParameter)
        {
            byte[] bytes = ((KeyParameter) params).getKey();

            if (bytes != null)
                key = new SecretKeySpec(bytes, getAlgorithmName());
        }

        try
        {
            cipher.init(
                    forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    key);
        }
        catch (InvalidKeyException ike)
        {
            System.out.println("BlockCipherAdapater#init error: " + ike.toString());
            throw new IllegalArgumentException(ike);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int processBlock(byte[] in, int inOff, byte[] out, int outOff)
        throws DataLengthException, IllegalStateException
    {
        try
        {
            return cipher.update(in, inOff, getBlockSize(), out, outOff);
        }
        catch (ShortBufferException sbe)
        {
            System.out.println("BlockCipherAdapter#processBlock error: " + sbe.toString());

            DataLengthException dle = new DataLengthException();

            dle.initCause(sbe);
            throw dle;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset()
    {
        try
        {
            cipher.doFinal();
        }
        catch (GeneralSecurityException gse)
        {
            System.out.println("CipherBlockAdapter#reset error: " + gse.toString());
        }
    }
}
