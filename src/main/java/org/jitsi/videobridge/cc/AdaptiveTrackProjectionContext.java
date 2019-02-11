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
import org.jitsi.service.neomedia.format.*;

/**
 * Implementations of this interface are responsible for projecting a specific
 * video track of a specific payload type.
 *
 * One can imagine signaling a specific encoding layout (i.e. 3 temporal layers)
 * and multiple codec support (e.g. VP8 SVC and VP9 SVC). In the bridge such
 * signaling would translate into an AdaptiveTrackProjection instance that would
 * remain active throughout the life of the source video track and would receive
 * updates from the bitrate controller (i.e. ideal index and target index). The
 * specific way of projecting VP9 SVC or VP8 SVC is implemented in "context"
 * classes that know how to deal with codec specificities.
 *
 * @author George Politis
 */
public interface AdaptiveTrackProjectionContext
{
    /**
     * An empty {@link RawPacket} array that is used as a return value when no
     * packets need to be piggy-backed.
     */
    RawPacket[] EMPTY_PACKET_ARR = new RawPacket[0];

    /**
     * Determines whether an RTP packet should be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to accept or not.
     * @param incomingIndex the quality index of the incoming RTP packet.
     * @param targetIndex the target quality index
     * @return true if the packet should be accepted, false otherwise.
     */
    boolean accept(RawPacket rtpPacket, int incomingIndex, int targetIndex);

    /**
     * @return true if this stream context needs a keyframe in order to either
     * start rendering again or there's a pending simulcast switch (depending
     * on the implementation).
     */
    boolean needsKeyframe();

    /**
     * Rewrites the timestamp, sequence number, ssrc and other codec dependend
     * fields of the RTP packet that is specified as an argument. Projecting a
     * video track needs to be invisible to the receiving endpoint so goal here
     * is to make the resulting rtp stream continuous.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @param incomingRawPacketCache the packet cache to pull piggy-backed
     * packets from.
     * @return any RTP packets to piggy-back, or {@link #EMPTY_PACKET_ARR}.
     * @throws RewriteException the underlying code has failed to rewrite the
     * RTP packet that is specified as an argument.
     */
    RawPacket[]
    rewriteRtp(RawPacket rtpPacket, RawPacketCache incomingRawPacketCache)
        throws RewriteException;

    /**
     * Rewrites the RTCP packet that is specified as an argument.
     *
     * @param rtcpPacket the RTCP packet to transform.
     * @return true if the RTCP packet is accepted, false otherwise, in which
     * case it needs to be dropped.
     */
    boolean rewriteRtcp(RawPacket rtcpPacket);

    /**
     * @return the RTP state that describes the max sequence number, max
     * timestamp and other RTP-level details.
     */
    RtpState getRtpState();

    /**
     * @return the {@link MediaFormat} of the RTP packets that this context
     * processes.
     */
    MediaFormat getFormat();
}
