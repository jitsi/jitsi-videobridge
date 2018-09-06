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
package org.jitsi.impl.neomedia.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * De-duplicates RTP packets from incoming RTP streams. A more space-efficient
 * implementation using a replay context is possible but one needs to be careful
 * with the window size not to drop valid retransmissions
 * (https://github.com/jitsi/libjitsi/pull/263#discussion_r100417318).
 *
 * @author George Politis
 */
public class PaddingTermination
    extends SinglePacketTransformerAdapter
    implements TransformEngine
{
    /**
     * The size of the seen sequence numbers set to hold per SSRC. As long as
     * Chrome does not probe with ancient packets (> 5 seconds (300 packets per
     * second) this size should be a sufficiently large size to prevent padding
     * packets.
     */
    private static final int SEEN_SET_SZ = 1500;

    /**
     * Ctor.
     */
    public PaddingTermination()
    {
        super(RTPPacketPredicate.INSTANCE);
    }

    /**
     * The {@code ReplayContext} for every SSRC that this instance has seen.
     */
    private final Map<Long, Set<Integer>> replayContexts = new TreeMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        Long mediaSSRC = pkt.getSSRCAsLong();

        // NOTE dropping padding from the main RTP stream is not supported
        // because we can't rewrite to hide the gaps. Padding packets in the
        // RTX stream are detected and killed in the RtxTransformer, so this
        // instance should not see any RTX packets (it's after RTX in the
        // chain).
        Set<Integer> replayContext = replayContexts.get(mediaSSRC);
        if (replayContext == null)
        {
            replayContext = Collections.newSetFromMap(
                new LRUCache<Integer, Boolean>(SEEN_SET_SZ));

            replayContexts.put(mediaSSRC, replayContext);
        }

        return replayContext.add(pkt.getSequenceNumber()) ? pkt : null;
    }

}
