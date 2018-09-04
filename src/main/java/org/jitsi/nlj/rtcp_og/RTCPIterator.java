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
package org.jitsi.nlj.rtcp_og;

import org.jitsi.service.neomedia.*;

import java.util.*;

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

/**
 * An {@code Iterator} for RTCP packets contained in a compound RTCP packet.
 * For a {@code PacketTransformer} that splits compound RTCP packets into
 * individual RTCP packets {@see CompoundPacketEngine}.
 *
 * Instances of this class are not thread-safe. If multiple threads access an
 * instance concurrently, it must be synchronized externally.
 *
 * @author George Politis
 */
public class RTCPIterator
        implements Iterator<ByteArrayBuffer>
{
    /**
     * The {@code RawPacket} that holds the RTCP packet to iterate.
     */
    private final ByteArrayBuffer baf;

    /**
     * The offset in the {@link #baf} where the next packet is to be looked for.
     */
    private int nextOff;

    /**
     * The remaining length in {@link #baf}.
     */
    private int remainingLen;

    /**
     * The length of the last next element.
     */
    private int lastLen;

    /**
     * Ctor.
     *
     * @param baf The {@code ByteArrayBuffer} that holds the compound RTCP
     * packet to iterate.
     */
    public RTCPIterator(ByteArrayBuffer baf)
    {
        this.baf = baf;
        if (baf != null)
        {
            this.nextOff = baf.getOffset();
            this.remainingLen = baf.getLength();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext()
    {
        return RTCPUtils.getLength
                (baf.getBuffer(), nextOff, remainingLen) >= 8 /*RTCPHeader.SIZE*/;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteArrayBuffer next()
    {
        int pktLen = RTCPUtils.getLength(
                baf.getBuffer(), nextOff, remainingLen);
        if (pktLen < 8 /*RTCPHeader.SIZE*/)
        {
            throw new IllegalStateException();
        }

        RawPacket next = new RawPacket(baf.getBuffer(), nextOff, pktLen);

        lastLen = pktLen;
        nextOff += pktLen;
        remainingLen -= pktLen;

        if (remainingLen < 0)
        {
            throw new ArrayIndexOutOfBoundsException();
        }

        return next;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove()
    {
        if (lastLen == 0)
        {
            throw new IllegalStateException();
        }

        System.arraycopy(
                baf.getBuffer(), nextOff,
                baf.getBuffer(), nextOff - lastLen, remainingLen);

        nextOff -= lastLen;
        baf.setLength(baf.getLength() - lastLen);

        lastLen = 0;
    }

}

