/*
 * Copyright @ 2016 Atlassian Pty Ltd
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

/**
 * @see SRTPCipherCTR
 * SRTPCipherCTR implementation using OpenSSL via JNI.
 */
public class SRTPCipherCTROpenSSL extends SRTPCipherCTR
{
    /**
     * The <tt>Logger</tt> used by the <tt>SRTPCipherCTROpenSSL</tt> class to
     * print out debug information.
     */
//    private static final Logger logger =
//        Logger.getLogger(SRTPCipherCTROpenSSL.class);

    private static native long AES128CTR_CTX_create();

    private static native void AES128CTR_CTX_destroy(long ctx);

    private static native boolean AES128CTR_CTX_init(long ctx, byte[] key);

    private static native boolean AES128CTR_CTX_process(long ctx, byte[] iv,
        byte[] inOut, int offset, int len);

    /**
     * the OpenSSL AES128CTR context
     */
    private long ctx;

    public SRTPCipherCTROpenSSL()
    {
        if (!OpenSSLWrapperLoader.isLoaded())
            throw new RuntimeException("OpenSSL wrapper not loaded");

        ctx = AES128CTR_CTX_create();
        if (ctx == 0)
            throw new RuntimeException("CIPHER_CTX_create");
    }

    /**
     * {@inheritDoc}
     */
    public void init(byte[] key)
    {
        if (key.length != BLKLEN)
            throw new IllegalArgumentException("key.length != BLKLEN");

        if (!AES128CTR_CTX_init(ctx, key))
            throw new RuntimeException("AES128CTR_CTX_init");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void finalize() throws Throwable
    {
        try
        {
            // Well, the destroying in the finalizer should exist as a backup
            // anyway. There is no way to explicitly invoke the destroying at
            // the time of this writing but it is a start.
            if (ctx != 0)
            {
                AES128CTR_CTX_destroy(ctx);
                ctx = 0;
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
    public void process(byte[] data, int off, int len, byte[] iv)
    {
        checkProcessArgs(data, off, len, iv);

        if (!AES128CTR_CTX_process(ctx, iv, data, off, len))
            throw new RuntimeException("AES128CTR_CTX_process");
    }
}
