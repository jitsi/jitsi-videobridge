/*
 * Copyright @ 2019-present 8x8, Inc
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

package org.jitsi.videobridge.cc.vp9;

import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.*;
import org.json.simple.*;

/**
 * This class represents a projection of a VP9 RTP stream
 * and it is the main entry point for VP9 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 */
public class VP9AdaptiveSourceProjectionContext
    implements AdaptiveSourceProjectionContext
{
    private final Logger logger;

    /**
     * The diagnostic context of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The VP9 media format. No essential functionality relies on this field,
     * it's only used as a cache of the {@link PayloadType} instance for VP9 in
     * case we have to do a context switch (see {@link AdaptiveSourceProjection}),
     * in order to avoid having to resolve the format.
     */
    private final PayloadType payloadType;

    public VP9AdaptiveSourceProjectionContext(
        DiagnosticContext diagnosticContext,
        PayloadType payloadType,
        RtpState rtpState,
        Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(
            VP9AdaptiveSourceProjectionContext.class.getName());
        this.payloadType = payloadType;
    }

    @Override
    public boolean accept(PacketInfo packetInfo, int incomingIndex,
        int targetIndex)
    {
        return false;
    }

    @Override
    public boolean needsKeyframe()
    {
        return false;
    }

    @Override
    public void rewriteRtp(PacketInfo packetInfo) throws RewriteException
    {

    }

    @Override
    public boolean rewriteRtcp(RtcpSrPacket rtcpSrPacket)
    {
        return false;
    }

    @Override
    public RtpState getRtpState()
    {
        return null;
    }

    @Override
    public PayloadType getPayloadType()
    {
        return payloadType;
    }

    @Override
    public JSONObject getDebugState()
    {
        return null;
    }
}
