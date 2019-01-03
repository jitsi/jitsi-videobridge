/*
 * Copyright @ 2019 8x8, Inc
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
package org.jitsi.videobridge.cc;

import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;

/**
 * @author George Politis
 */
public interface AdaptiveTrackProjectionContext
{
    /**
     * An empty {@link RawPacket} array that is used as a return value and
     * indicates that the input packet needs to be dropped.
     */
    RawPacket[] DROP_PACKET_ARR = new RawPacket[0];

    /**
     * An empty {@link RawPacket} array that is used as a return value when no
     * packets need to be piggy-backed.
     */
    RawPacket[] EMPTY_PACKET_ARR = new RawPacket[0];

    /**
     * Determines whether a packet needs to be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to project or not.
     * @param targetIndex the target quality index
     * @return true if the packet is accepted, false otherwise.
     */
    boolean accept(RawPacket rtpPacket, int targetIndex);

    /**
     * @return true if this instance needs a keyframe, false otherwise.
     */
    boolean needsKeyframe();

    /**
     * Rewrites the RTP packet that is specified as an argument.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @param incomingRawPacketCache the packet cache to pull piggy-backed
     * packets from.
     * @return any RTP packets to piggy-back, or {@link #DROP_PACKET_ARR} if the
     * packet needs to be dropped.
     */
    RawPacket[]
    rewriteRtp(RawPacket rtpPacket, RawPacketCache incomingRawPacketCache);

    /**
     * Rewrites the RTCP packet that is specified as an argument.
     *
     * @param rtcpPacket the RTCP packet to transform.
     * @return true if the RTCP packet is accepted, false otherwise, in which
     * case it needs to be dropped.
     */
    boolean rewriteRtcp(RawPacket rtcpPacket);
}
