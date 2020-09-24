/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.test_utils

import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.rtp.util.byteBufferOf

data class SrtpData(
    var srtpProfileInformation: SrtpProfileInformation,
    var keyingMaterial: ByteArray,
    var tlsRole: TlsRole
)

data class SourceAssociation(
    var primarySsrc: Long,
    var secondarySsrc: Long,
    var associationType: SsrcAssociationType
)

data class PcapInformation(
    var filePath: String,
    var srtpData: SrtpData,
    var payloadTypes: List<PayloadType>,
    var headerExtensions: List<RtpExtension>,
    var ssrcAssociations: List<SourceAssociation>
)

val DEFAULT_HEADER_EXTENSIONS = listOf(
    RtpExtension(1, RtpExtensionType.SSRC_AUDIO_LEVEL),
    RtpExtension(3, RtpExtensionType.ABS_SEND_TIME),
    RtpExtension(4, RtpExtensionType.RTP_STREAM_ID),
    RtpExtension(5, RtpExtensionType.TRANSPORT_CC)
)

object Pcaps {
    object Incoming {
        val ONE_PARTICIPANT_RTP_RTCP_SIM_RTX = PcapInformation(
            filePath = javaClass.classLoader.getResource(
                "pcaps/1_incoming_participant_rtp_rtcp_sim_rtx.pcap").path,
            srtpData = SrtpData(
                srtpProfileInformation = SrtpProfileInformation(
                    cipherKeyLength = 16,
                    cipherSaltLength = 14,
                    cipherName = 1,
                    authFunctionName = 1,
                    authKeyLength = 20,
                    rtcpAuthTagLength = 10,
                    rtpAuthTagLength = 10
                ),
                keyingMaterial = byteBufferOf(
                    0x70, 0xD5, 0x56, 0xB1,
                    0xC5, 0xB3, 0xC7, 0x7E,
                    0xE6, 0x31, 0xC3, 0xA2,
                    0xC2, 0x93, 0x4E, 0xAD,
                    0xBE, 0x53, 0xDD, 0x22,
                    0xB5, 0x6E, 0xF2, 0xD9,
                    0xB6, 0x67, 0xE9, 0xEF,
                    0xD5, 0xB2, 0x88, 0x6F,
                    0x6C, 0x6F, 0x16, 0xAC,
                    0xA2, 0x13, 0xDF, 0x1D,
                    0x63, 0x60, 0x39, 0x1F,
                    0x23, 0x74, 0xBA, 0x0C,
                    0x5B, 0x0B, 0x14, 0x3E,
                    0x2E, 0x3E, 0x6D, 0x30,
                    0xFF, 0x6F, 0x54, 0x5B
                ).array(),
                tlsRole = TlsRole.CLIENT
            ),
            payloadTypes = listOf(
                RtxPayloadType(96, parameters = mapOf("apt" to "100")),
                Vp8PayloadType(100, rtcpFeedbackSet = setOf("nack pli", "ccm fir")),
                OpusPayloadType(111)
            ),
            headerExtensions = DEFAULT_HEADER_EXTENSIONS,
            ssrcAssociations = listOf(
                SourceAssociation(1632300152, 1929115835, SsrcAssociationType.RTX),
                SourceAssociation(3232245189, 3407277242, SsrcAssociationType.RTX),
                SourceAssociation(1465075899, 3483093671, SsrcAssociationType.RTX)
            )
        )
        val ONE_PARTICIPANT_RTP_RTCP = PcapInformation(
            filePath = javaClass.classLoader.getResource(
                "pcaps/capture_1_incoming_participant_1_rtp_and_rtcp.pcap").path,
            srtpData = SrtpData(
                srtpProfileInformation = SrtpProfileInformation(
                    cipherKeyLength = 16,
                    cipherSaltLength = 14,
                    cipherName = 1,
                    authFunctionName = 1,
                    authKeyLength = 20,
                    rtcpAuthTagLength = 10,
                    rtpAuthTagLength = 10
                ),
                keyingMaterial = byteBufferOf(
                    0x2D, 0x6C, 0x37, 0xC7,
                    0xA3, 0x49, 0x25, 0x82,
                    0x1F, 0x3B, 0x62, 0x0D,
                    0x05, 0x8A, 0x29, 0x64,
                    0x6F, 0x49, 0xD6, 0x04,
                    0xE6, 0xD6, 0x48, 0xE0,
                    0x67, 0x43, 0xF3, 0x1F,
                    0x6D, 0x2F, 0x4B, 0x33,
                    0x6A, 0x61, 0xD8, 0x84,
                    0x00, 0x32, 0x1A, 0x84,
                    0x00, 0x8C, 0xC5, 0xC3,
                    0xCB, 0x18, 0xCE, 0x8D,
                    0x34, 0x3C, 0x2C, 0x70,
                    0x62, 0x26, 0x39, 0x05,
                    0x7D, 0x5A, 0xF9, 0xC7
                ).array(),
                tlsRole = TlsRole.CLIENT
            ),
            payloadTypes = listOf(
                Vp8PayloadType(100),
                OpusPayloadType(111)
            ),
            headerExtensions = DEFAULT_HEADER_EXTENSIONS,
            ssrcAssociations = listOf()
        )
    }
    object Outgoing {
        val ONE_PARITICPANT_RTP_AND_RTCP_DECRYPTED = PcapInformation(
            filePath = javaClass.classLoader.getResource(
                "pcaps/capture_1_incoming_participant_1_rtp_and_rtcp_decrypted_2.pcap").path,
            srtpData = SrtpData(
                srtpProfileInformation = SrtpProfileInformation(
                    cipherKeyLength = 16,
                    cipherSaltLength = 14,
                    cipherName = 1,
                    authFunctionName = 1,
                    authKeyLength = 20,
                    rtcpAuthTagLength = 10,
                    rtpAuthTagLength = 10
                ),
                keyingMaterial = byteBufferOf(
                    0x2D, 0x6C, 0x37, 0xC7,
                    0xA3, 0x49, 0x25, 0x82,
                    0x1F, 0x3B, 0x62, 0x0D,
                    0x05, 0x8A, 0x29, 0x64,
                    0x6F, 0x49, 0xD6, 0x04,
                    0xE6, 0xD6, 0x48, 0xE0,
                    0x67, 0x43, 0xF3, 0x1F,
                    0x6D, 0x2F, 0x4B, 0x33,
                    0x6A, 0x61, 0xD8, 0x84,
                    0x00, 0x32, 0x1A, 0x84,
                    0x00, 0x8C, 0xC5, 0xC3,
                    0xCB, 0x18, 0xCE, 0x8D,
                    0x34, 0x3C, 0x2C, 0x70,
                    0x62, 0x26, 0x39, 0x05,
                    0x7D, 0x5A, 0xF9, 0xC7
                ).array(),
                tlsRole = TlsRole.CLIENT
            ),
            payloadTypes = listOf(
                Vp8PayloadType(100),
                OpusPayloadType(111),
                RtxPayloadType(96, parameters = mapOf("apt" to "100"))
            ),
            headerExtensions = DEFAULT_HEADER_EXTENSIONS,
            ssrcAssociations = listOf()
        )
    }
}
