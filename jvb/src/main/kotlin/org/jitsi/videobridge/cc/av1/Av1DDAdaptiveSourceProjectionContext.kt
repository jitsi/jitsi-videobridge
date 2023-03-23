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
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.AdaptiveSourceProjectionContext
import org.jitsi.videobridge.cc.RtpState
import org.json.simple.JSONArray
import org.json.simple.JSONObject

class Av1DDAdaptiveSourceProjectionContext(
    private val diagnosticContext: DiagnosticContext,
    private val payloadType: PayloadType,
    rtpState: RtpState,
    parentLogger: Logger
) : AdaptiveSourceProjectionContext {
    private val logger: Logger = createChildLogger(parentLogger)

    /**
     * A map that stores the per-encoding AV1 frame maps.
     */
    private val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

    override fun accept(packetInfo: PacketInfo, incomingIndices: Collection<Int>, targetIndex: Int): Boolean {
        val packet = packetInfo.packet

        if (packet !is Av1DDPacket) {
            logger.warn("Packet is not AV1 DD Packet")
            return false
        }

        /* If insertPacketInMap returns null, this is a very old picture, more than Av1FrameMap.PICTURE_MAP_SIZE old,
           or something is wrong with the stream. */
        val result = insertPacketInMap(packet) ?: return false

        val frame = result.frame

        TODO("Not yet implemented")
    }

    /**
     * Insert a packet in the appropriate [Av1DDFrameMap].
     */
    private fun insertPacketInMap(av1Packet: Av1DDPacket) =
        av1FrameMaps.getOrPut(av1Packet.ssrc) { Av1DDFrameMap(logger) }.insertPacket(av1Packet)

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
        debugState["class"] = Av1DDAdaptiveSourceProjectionContext::class.java.simpleName

        val mapSizes = JSONArray()
        for ((key, value) in av1FrameMaps.entries) {
            val sizeInfo = JSONObject()
            sizeInfo["ssrc"] = key
            sizeInfo["size"] = value.size()
            mapSizes.add(sizeInfo)
        }
        debugState["av1FrameMaps"] = mapSizes
//        debugState["vp9QualityFilter"] = vp9QualityFilter.debugState

        debugState["payloadType"] = payloadType.toString()

        return debugState
    }
}
