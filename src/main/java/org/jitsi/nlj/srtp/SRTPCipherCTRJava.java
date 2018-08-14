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
 * @see SRTPCipherCTR
 * SRTPCipherCTR implementation using Java and a <tt>BlockCipher</tt>.
 *
 * You can use any <tt>BlockCipher</tt> with <tt>BLKLEN</tt> bytes key and
 * block size like TwofishEngine instead of AES.
 */
public class SRTPCipherCTRJava extends SRTPCipherCTR
{
    private final byte[] tmpCipherBlock = new byte[BLKLEN];
    private final BlockCipher cipher;

    public SRTPCipherCTRJava(BlockCipher cipher)
    {
        this.cipher = cipher;
    }

    /**
     * {@inheritDoc}
     */
    public void init(byte[] key)
    {
        if (key.length != 16 && key.length != 24 && key.length != 32)
            throw new IllegalArgumentException("Not an AES key length");

        cipher.init(true, new KeyParameter(key));
    }

    /**
     * {@inheritDoc}
     */
    public void process(byte[] data, int off, int len, byte[] iv)
    {
        checkProcessArgs(data, off, len, iv);

        int l = len, o = off;
        while (l >= BLKLEN)
        {
            cipher.processBlock(iv, 0, tmpCipherBlock, 0);
            //incr counter
            if(++iv[15] == 0) ++iv[14];
            //unroll XOR loop to force java to optimise it
            data[o+0]  ^= tmpCipherBlock[0];
            data[o+1]  ^= tmpCipherBlock[1];
            data[o+2]  ^= tmpCipherBlock[2];
            data[o+3]  ^= tmpCipherBlock[3];
            data[o+4]  ^= tmpCipherBlock[4];
            data[o+5]  ^= tmpCipherBlock[5];
            data[o+6]  ^= tmpCipherBlock[6];
            data[o+7]  ^= tmpCipherBlock[7];
            data[o+8]  ^= tmpCipherBlock[8];
            data[o+9]  ^= tmpCipherBlock[9];
            data[o+10] ^= tmpCipherBlock[10];
            data[o+11] ^= tmpCipherBlock[11];
            data[o+12] ^= tmpCipherBlock[12];
            data[o+13] ^= tmpCipherBlock[13];
            data[o+14] ^= tmpCipherBlock[14];
            data[o+15] ^= tmpCipherBlock[15];
            l -= BLKLEN;
            o += BLKLEN;
        }

        if (l > 0)
        {
            cipher.processBlock(iv, 0, tmpCipherBlock, 0);
            //incr counter
            if(++iv[15] == 0) ++iv[14];
            for (int i = 0; i < l; i++)
                data[o+i] ^= tmpCipherBlock[i];
        }
    }
}
