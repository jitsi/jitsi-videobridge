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
package org.jitsi.impl.neomedia.codec.video.vp8;

/**
 * A class that represents the VP8 Payload Header structure defined
 * in {@link "http://tools.ietf.org/html/draft-ietf-payload-vp8-10"}
 */
public class VP8PayloadHeader
{
    /**
     * S bit of the Payload Descriptor.
     */
    private static final byte S_BIT = (byte) 0x01;

    /**
     * Returns true if the <tt>P</tt> (inverse key frame flag) field of the
     * VP8 Payload Header at offset <tt>offset</tt> in <tt>input</tt> is 0.
     *
     * @return true if the <tt>P</tt> (inverse key frame flag) field of the
     * VP8 Payload Header at offset <tt>offset</tt> in <tt>input</tt> is 0,
     * false otherwise.
     */
    public static boolean isKeyFrame(byte[] input, int offset)
    {
        // When set to 0 the current frame is a key frame.  When set to 1
        // the current frame is an interframe. Defined in [RFC6386]

        return (input[offset] & S_BIT) == 0;
    }
}
