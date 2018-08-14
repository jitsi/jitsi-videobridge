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

import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.params.*;

/**
 * Implements the interface <tt>org.bouncycastle.crypto.Mac</tt> using the
 * OpenSSL Crypto library.
 *
 * @author Lyubomir Marinov
 */
public class OpenSSLHMAC
    implements Mac
{
    private static native int EVP_MD_size(long md);

    private static native long EVP_sha1();

    private static native long HMAC_CTX_create();

    private static native void HMAC_CTX_destroy(long ctx);

    private static native int HMAC_Final(
            long ctx,
            byte[] md, int mdOff, int mdLen);

    private static native boolean HMAC_Init_ex(
            long ctx,
            byte[] key, int keyLen,
            long md,
            long impl);

    private static native boolean HMAC_Update(
            long ctx,
            byte[] data, int off, int len);

    /**
     * The name of the algorithm implemented by this instance.
     */
    private static final String algorithmName = "SHA-1/HMAC";

    /**
     * The context of the OpenSSL (Crypto) library through which the actual
     * algorithm implementation is invoked by this instance.
     */
    private long ctx;

    /**
     * The key provided in the form of a {@link KeyParameter} in the last
     * invocation of {@link #init(CipherParameters)}.
     */
    private byte[] key;

    /**
     * The block size in bytes for this MAC.
     */
    private final int macSize;

    /**
     * The OpenSSL Crypto type of the message digest implemented by this
     * instance.
     */
    private final long md;

    /**
     * The algorithm of the SHA-1 cryptographic hash function/digest.
     */
    public static final int SHA1 = 1;

    /**
     * Initializes a new <tt>OpenSSLHMAC</tt> instance with a specific digest
     * algorithm.
     *
     * @param digestAlgorithm the algorithm of the digest to initialize the new
     * instance with
     * @see OpenSSLHMAC#SHA1
     */
    public OpenSSLHMAC(int digestAlgorithm)
    {
        if (!OpenSSLWrapperLoader.isLoaded())
            throw new RuntimeException("OpenSSL wrapper not loaded");

        if (digestAlgorithm != OpenSSLHMAC.SHA1)
            throw new IllegalArgumentException(
                    "digestAlgorithm " + digestAlgorithm);

        md = EVP_sha1();
        if (md == 0)
            throw new IllegalStateException("EVP_sha1 == 0");

        macSize = EVP_MD_size(md);
        if (macSize == 0)
            throw new IllegalStateException("EVP_MD_size == 0");

        ctx = HMAC_CTX_create();
        if (ctx == 0)
            throw new RuntimeException("HMAC_CTX_create == 0");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int doFinal(byte[] out, int outOff)
        throws DataLengthException, IllegalStateException
    {
        if (out == null)
            throw new NullPointerException("out");
        if ((outOff < 0) || (out.length <= outOff))
            throw new ArrayIndexOutOfBoundsException(outOff);

        int outLen = out.length - outOff;
        int macSize = getMacSize();

        if (outLen < macSize)
        {
            throw new DataLengthException(
                    "Space in out must be at least " + macSize + "bytes but is "
                        + outLen + " bytes!");
        }

        long ctx = this.ctx;

        if (ctx == 0)
        {
            throw new IllegalStateException("ctx");
        }
        else
        {
            outLen = HMAC_Final(ctx, out, outOff, outLen);
            if (outLen < 0)
            {
                throw new RuntimeException("HMAC_Final");
            }
            else
            {
                // As the javadoc on interface method specifies, the doFinal
                // call leaves this Digest reset.
                reset();
                return outLen;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void finalize()
        throws Throwable
    {
        try
        {
            // Well, the destroying in the finalizer should exist as a backup
            // anyway. There is no way to explicitly invoke the destroying at
            // the time of this writing but it is a start.
            long ctx = this.ctx;

            if (ctx != 0)
            {
                this.ctx = 0;
                HMAC_CTX_destroy(ctx);
            }
        }
        finally
        {
            super.finalize();
        }
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
    public int getMacSize()
    {
        return macSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(CipherParameters params)
        throws IllegalArgumentException
    {
        key = (params instanceof KeyParameter)
                ? ((KeyParameter) params).getKey()
                : null;

        if (key == null)
            throw new IllegalStateException("key == null");
        if (ctx == 0)
            throw new IllegalStateException("ctx == 0");

        if (!HMAC_Init_ex(ctx, key, key.length, md, 0))
            throw new RuntimeException("HMAC_Init_ex() init failed");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset()
    {
        if (key == null)
            throw new IllegalStateException("key == null");
        if (ctx == 0)
            throw new IllegalStateException("ctx == 0");

        // just reset the ctx (keep same key and md)
        if (!HMAC_Init_ex(ctx, null, 0, 0, 0))
            throw new RuntimeException("HMAC_Init_ex() reset failed");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(byte in)
        throws IllegalStateException
    {
        // TODO Auto-generated method stub
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(byte[] in, int off, int len)
        throws DataLengthException, IllegalStateException
    {
        if (len != 0)
        {
            if (in == null)
                throw new NullPointerException("in");
            if ((off < 0) || (in.length <= off))
                throw new ArrayIndexOutOfBoundsException(off);
            if ((len < 0) || (in.length < off + len))
                throw new IllegalArgumentException("len " + len);

            long ctx = this.ctx;

            if (ctx == 0)
                throw new IllegalStateException("ctx");
            else if (!HMAC_Update(ctx, in, off, len))
                throw new RuntimeException("HMAC_Update");
        }
    }
}
