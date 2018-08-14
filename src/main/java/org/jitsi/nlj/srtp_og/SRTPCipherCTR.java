/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.srtp_og;

/**
 * @author bb
 */
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

/**
 * SRTPCipherCTR implementations implement SRTP Counter Mode Encryption.
 *
 * SRTP Counter Mode is standard block cipher counter mode with special key and
 * special counter initial value (iv). We only increment last 16 bits of the
 * counter, so we can only encrypt 2^16 * <tt>BLKLEN</tt> of data.
 *
 * SRTP Counter Mode AES Encryption algorithm is defined in RFC3711, section
 * 4.1.1.
 */
abstract class SRTPCipherCTR
{
    protected static final int BLKLEN = 16;

    /**
     * (Re)Initialize the cipher with key
     *
     * @param key the key. key.length == BLKLEN
     */
    public abstract void init(byte[] key);

    /**
     * Process (encrypt/decrypt) data from offset for len bytes iv can be
     * modified by this function but you MUST never reuse an IV so it's ok
     *
     * @param data byte array to be processed
     * @param off the offset
     * @param len the length
     * @param iv initial value of the counter (can be modified).
     *           iv.length == BLKLEN
     */
    public abstract void process(byte[] data, int off, int len, byte[] iv);

    /**
     * Check the validity of process function arguments
     */
    protected static void checkProcessArgs(byte[] data, int off, int len, byte[] iv)
    {
        if (iv.length != BLKLEN)
            throw new IllegalArgumentException("iv.length != BLKLEN");
        if (off < 0)
            throw new IllegalArgumentException("off < 0");
        if (len < 0)
            throw new IllegalArgumentException("len < 0");
        if (off + len > data.length)
            throw new IllegalArgumentException("off + len > data.length");
        /*
         * we increment only the last 16 bits of the iv, so we can encrypt
         * a maximum of 2^16 blocks, ie 1048576 bytes
         */
        if (data.length > 1048576)
            throw new IllegalArgumentException("data.length > 1048576");
    }
}

