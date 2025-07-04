/*
 * Copyright @ 2019 - present 8x8 Inc
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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadTypeEncoding.RED
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RedAudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger

class RtpParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("RTP Parser") {
    private val logger = createChildLogger(parentLogger)

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet
        val payloadTypeNumber = RtpHeader.getPayloadType(packet.buffer, packet.offset).toByte()

        val payloadType = streamInformationStore.rtpPayloadTypes[payloadTypeNumber] ?: run {
            logger.cdebug { "Unknown payload type: $payloadTypeNumber" }
            return null
        }
        packetInfo.payloadType = payloadType

        val rtpPacket = when (payloadType.mediaType) {
            MediaType.AUDIO -> try {
                when (payloadType.encoding) {
                    RED -> packet.toOtherType(::RedAudioRtpPacket)
                    else -> packet.toOtherType(::AudioRtpPacket)
                }
            } catch (e: Exception) {
                logger.info("Dropping audio packet due to parse failure: ${e.message}")
                return null
            }
            MediaType.VIDEO -> try {
                packet.toOtherType(::VideoRtpPacket)
            } catch (e: Exception) {
                logger.info("Dropping video packet due to parse failure: ${e.message}")
                return null
            }
            else -> {
                logger.info("Dropping packet with unrecognized media type: '${payloadType.mediaType}'")
                return null
            }
        }
        packetInfo.packet = rtpPacket
        if (rtpPacket.extensionsProfileType == 0xC0DE || rtpPacket.extensionsProfileType == 0xC2DE) {
            packetInfo.originalHadCryptex = true
        }

        packetInfo.resetPayloadVerification()
        return packetInfo
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
