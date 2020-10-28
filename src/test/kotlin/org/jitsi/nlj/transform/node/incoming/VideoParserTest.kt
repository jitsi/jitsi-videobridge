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

package org.jitsi.nlj.transform.node.incoming

import io.kotest.assertions.fail
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpExtensionHandler
import org.jitsi.nlj.util.RtpPayloadTypesChangedHandler
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.createLogger

class VideoParserTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val streamInformationStore = object : ReadOnlyStreamInformationStore {
        override val rtpExtensions: List<RtpExtension> = mutableListOf()
        override val rtpPayloadTypes: Map<Byte, PayloadType> =
            mutableMapOf(100.toByte() to Vp8PayloadType(100.toByte()))
        override var supportsFir: Boolean = true
        override var supportsPli: Boolean = true
        override var supportsRemb: Boolean = true
        override var supportsTcc: Boolean = true
        override fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
            // no-op
        }
        override fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler) {
            // no-op
        }

        override val primaryMediaSsrcs: Set<Long> = setOf(123L, 456L, 789L)
        override val primaryVideoSsrcs: Set<Long> = setOf(123L, 456L)
        override val receiveSsrcs: Set<Long> = setOf(123L, 456L, 789L, 321L, 654L)

        override fun getLocalPrimarySsrc(secondarySsrc: Long): Long? = null

        override fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? = null

        override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("dummy")
    }

    private val parser = VideoParser(streamInformationStore, createLogger())

    private val vp8PacketBuf = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=100,SeqNum=16535
        0x90, 0x64, 0x40, 0x97,
        // Timestamp: 3899068446
        0xe8, 0x67, 0x10, 0x1e,
        // SSRC: 2828806853
        0xa8, 0x9c, 0x2a, 0xc5,
        // 1 extension
        0xbe, 0xde, 0x00, 0x01,
        0x51, 0x00, 0x02, 0x00
    ) + org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    private val vp8Packet = Vp8Packet(vp8PacketBuf, 0, vp8PacketBuf.size)
    private val packetInfo = mockk<PacketInfo>(relaxed = true) {
        every { packetAs<RtpPacket>() } returns vp8Packet
        every { packet } returns vp8Packet
    }

    init {
        context("When parsing a VP8 packet") {
            context("with no encoding signaled") {
                parser.onOutput { _ ->
                    fail("Should not forward the packet")
                }
                parser.processPacket(packetInfo)
            }
        }
    }
}
