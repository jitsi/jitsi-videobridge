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

/**
 * This class represents the RTP state and is used in adaptive source context
 * switches (see {@link AdaptiveSourceProjection}).
 *
 * @author George Politis
 */
public class RtpState
{
    /**
     * The SSRC of the RTP stream that this information pertains to.
     */
    public final long ssrc;

    /**
     * The highest sent RTP timestamp.
     */
    public final long maxTimestamp;

    /**
     * The highest sent sequence number.
     */
    public final int maxSequenceNumber;

    /**
     * @param ssrc the SSRC of the RTP stream that this information pertains to.
     * @param maxSequenceNumber the highest sent sequence number.
     * @param maxTimestamp the highest sent RTP timestamp.
     */
    public RtpState(long ssrc, int maxSequenceNumber, long maxTimestamp)
    {
        this.ssrc = ssrc;
        this.maxSequenceNumber = maxSequenceNumber;
        this.maxTimestamp = maxTimestamp;
    }
}
