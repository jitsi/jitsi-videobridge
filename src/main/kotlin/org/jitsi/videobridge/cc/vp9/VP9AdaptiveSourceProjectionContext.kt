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
package org.jitsi.videobridge.cc.vp9

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.cc.AdaptiveSourceProjectionContext
import org.jitsi.videobridge.cc.RewriteException
import org.jitsi.videobridge.cc.RtpState
import org.jitsi.videobridge.cc.vp8.VP8AdaptiveSourceProjectionContext
import org.json.simple.JSONObject

/**
 * This class represents a projection of a VP9 RTP stream
 * and it is the main entry point for VP9 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 */
class VP9AdaptiveSourceProjectionContext(
    /**
     * The diagnostic context of this instance.
     */
    private val diagnosticContext: DiagnosticContext,
    payloadType: PayloadType,
    private val /* TODO */ rtpState: RtpState,
    parentLogger: Logger
) : AdaptiveSourceProjectionContext {
    private val logger: Logger

    /**
     * The VP9 media format. No essential functionality relies on this field,
     * it's only used as a cache of the [PayloadType] instance for VP9 in
     * case we have to do a context switch (see [AdaptiveSourceProjection]),
     * in order to avoid having to resolve the format.
     */
    private val payloadType: PayloadType
    override fun accept(
        packetInfo: PacketInfo,
        incomingIndex: Int,
        targetIndex: Int
    ): Boolean {
        return false
    }

    override fun needsKeyframe(): Boolean {
        return false
    }

    @Throws(RewriteException::class)
    override fun rewriteRtp(packetInfo: PacketInfo) {
    }

    override fun rewriteRtcp(rtcpSrPacket: RtcpSrPacket): Boolean {
        return false
    }

    override fun getRtpState(): RtpState {
        return rtpState
    }

    override fun getPayloadType(): PayloadType {
        return payloadType
    }

    @Synchronized
    override fun getDebugState(): JSONObject {
        val debugState = JSONObject()
        debugState["class"] = VP8AdaptiveSourceProjectionContext::class.java.simpleName

        /* TODO */

        return debugState
    }

    init {
        logger = parentLogger.createChildLogger(
            VP9AdaptiveSourceProjectionContext::class.java.name)
        this.payloadType = payloadType
    }
}
