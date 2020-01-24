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
import org.jitsi.utils.logging2.*;

public class PacketInfoDistributor
{
    private final PacketInfo packetInfo;
    private final int totalReferences;
    private int outstandingReferences;
    private int numClones;
    private final Logger logger;

    /**
     * Whether to enable or disable book keeping.
     */
    public static final Boolean ENABLE_BOOKKEEPING = true;

    /**
     * Gets the current thread ID.
     */
    private static long threadId()
    {
        return Thread.currentThread().getId();
    }


    public PacketInfoDistributor(PacketInfo pi, int count, Logger parentLogger)
    {
        packetInfo = pi;
        totalReferences = count;
        outstandingReferences = count;
        logger = parentLogger.createChildLogger(getClass().toString());
    }

    public synchronized PacketInfo previewPacketInfo()
    {
        if (outstandingReferences <= 0)
        {
            throw new IllegalStateException("Packet previewed after all references taken");
        }
        return packetInfo;
    }

    public synchronized PacketInfo getPacketInfoReference()
    {
        outstandingReferences--;
        if (outstandingReferences < 0) {
            throw new IllegalStateException("Too many references taken");
        }
        PacketInfo ret;
        if (outstandingReferences > 0)
        {
            numClones++;
            ret = packetInfo.clone();
        }
        else
        {
            ret = packetInfo;
        }
        if (ENABLE_BOOKKEEPING)
        {
            logger.info("Thread " + threadId() + " was given buffer "
                + System.identityHashCode(ret.getPacket().getBuffer()) + " for packet " + packetInfo.getPacket().toString());
        }
        return ret;
    }

    public synchronized void releasePacketInfoReference()
    {
        outstandingReferences--;
        if (outstandingReferences < 0) {
            throw new IllegalStateException("Too many references taken");
        }
        if (outstandingReferences == 0)
        {
            if (numClones > 0)
            {
                logger.debug(() -> ("Packet clone optimization failed (after " +
                    numClones + " clones among " + totalReferences + " references)"));
            }
            if (ENABLE_BOOKKEEPING)
            {
                logger.info("Thread " + threadId() +
                    " returned its buffer reference for packet " + packetInfo.getPacket().toString());
            }
            ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
    }
}
