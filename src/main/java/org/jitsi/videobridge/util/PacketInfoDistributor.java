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


package org.jitsi.videobridge.util;

import org.jitsi.nlj.*;

import java.util.function.*;

public class PacketInfoDistributor
{
    private final PacketInfo packetInfo;
    private int totalReferences;
    private int outstandingReferences;

    private Consumer<PacketInfo> prevConsumer = null;

    public PacketInfoDistributor(PacketInfo pi, int count)
    {
        packetInfo = pi;
        totalReferences = count;
        outstandingReferences = count;
        /* We create a PacketInfoDistributor on each packet so we don't want to instantiate a new logger each time. */
    }

    public void setCount(int count)
    {
        if (outstandingReferences < totalReferences)
        {
            throw new IllegalStateException("Count set after references have been taken");
        }
        totalReferences = count;
        outstandingReferences = count;
    }

    public synchronized PacketInfo previewPacketInfo()
    {
        if (outstandingReferences <= 0)
        {
            throw new IllegalStateException("Packet previewed after all references taken");
        }
        return packetInfo;
    }

    public void usePacketInfoReference(Consumer<PacketInfo> consumer)
    {
        // We want to avoid calling 'clone' for the last receiver of this packet
        // since it's unnecessary.  To do so, we'll wait before we clone and send
        // to an interested handler until after we've determined another handler
        // is also interested in the packet.  We'll give the last handler the
        // original packet (without cloning).
        Consumer<PacketInfo> c1 = null, c2 = null;
        PacketInfo p1 = null, p2 = null;
        synchronized(this) {
            outstandingReferences--;
            if (outstandingReferences < 0)
            {
                throw new IllegalStateException("Too many references taken");
            }
            if (prevConsumer != null) {
                c1 = prevConsumer;
                p1 = packetInfo.clone();
            }
            prevConsumer = consumer;
            if (outstandingReferences == 0)
            {
                c2 = prevConsumer;
                p2 = packetInfo;
            }
        }

        if (c1 != null)
        {
            c1.accept(p1);
        }
        if (c2 != null)
        {
            c2.accept(p2);
        }
    }

    public void releasePacketInfoReference()
    {
        Consumer<PacketInfo> c = null;
        PacketInfo p = null;
        synchronized (this)
        {
            outstandingReferences--;
            if (outstandingReferences < 0)
            {
                throw new IllegalStateException("Too many references taken");
            }
            if (outstandingReferences == 0)
            {
                if (prevConsumer != null)
                {
                    c = prevConsumer;
                    p = packetInfo;
                }
                else
                {
                    ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
                }
            }
        }
        if (c != null)
        {
            c.accept(p);
        }
    }
}
