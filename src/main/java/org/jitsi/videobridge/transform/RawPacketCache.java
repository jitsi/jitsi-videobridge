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
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;

/**
 * An simple interface which allows a packet to be retrieved from a
 * cache/storage by an SSRC identifier and a sequence number.
 * @author Boris Grozev
 */
public interface RawPacketCache
{
    /**
     * Gets the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     */
    public RawPacket get(long ssrc, int seq);
}
