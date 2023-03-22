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
package org.jitsi.videobridge.cc.av1

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.AdaptiveSourceProjectionContext
import org.jitsi.videobridge.cc.RtpState
import org.jitsi.videobridge.cc.vp9.Vp9AdaptiveSourceProjectionContext
import org.json.simple.JSONObject

class Av1DDAdaptiveSourceProjectionContext(
    private val diagnosticContext: DiagnosticContext,
    private val payloadType: PayloadType,
    rtpState: RtpState,
    parentLogger: Logger
) : AdaptiveSourceProjectionContext {
    private val logger: Logger = createChildLogger(parentLogger)

    override fun accept(packetInfo: PacketInfo, incomingIndices: Collection<Int>, targetIndex: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun needsKeyframe(): Boolean {
        TODO("Not yet implemented")
    }

    override fun rewriteRtp(packetInfo: PacketInfo) {
        TODO("Not yet implemented")
    }

    override fun rewriteRtcp(rtcpSrPacket: RtcpSrPacket): Boolean {
        TODO("Not yet implemented")
    }

    override fun getRtpState(): RtpState {
        TODO("Not yet implemented")
    }

    override fun getPayloadType(): PayloadType {
        return payloadType
    }

    override fun getDebugState(): JSONObject {
        val debugState = JSONObject()
        debugState["class"] = Vp9AdaptiveSourceProjectionContext::class.java.simpleName

//        val mapSizes = JSONArray()
//        for ((key, value) in vp9PictureMaps.entries) {
//            val sizeInfo = JSONObject()
//            sizeInfo["ssrc"] = key
//            sizeInfo["size"] = value.size()
//            mapSizes.add(sizeInfo)
//        }
//        debugState["vp9FrameMaps"] = mapSizes
//        debugState["vp9QualityFilter"] = vp9QualityFilter.debugState

        debugState["payloadType"] = payloadType.toString()

        return debugState
    }
}
