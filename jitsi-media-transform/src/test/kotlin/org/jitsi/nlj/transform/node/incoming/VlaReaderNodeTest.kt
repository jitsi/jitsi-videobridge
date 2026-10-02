/*
 * Copyright @ 2026 - present 8x8, Inc.
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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.nlj.util.ExtmapAllowMixedChangedHandler
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpExtensionHandler
import org.jitsi.nlj.util.RtpPayloadTypesChangedHandler
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.time.FakeClock
import java.time.Instant

/** Feeds Video Layers Allocation extensions through [VlaReaderNode] and checks what the encodings' liveness makes
 * of them. */
class VlaReaderNodeTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val vlaExtId = 12
    private val streamInformationStore = object : ReadOnlyStreamInformationStore {
        override val rtpExtensions: List<RtpExtension> = emptyList()
        override val rtpPayloadTypes: Map<Byte, PayloadType> = emptyMap()
        override val supportsFir = true
        override val supportsPli = true
        override val supportsRemb = true
        override val supportsTcc = true
        override fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
            if (rtpExtensionType == RtpExtensionType.VLA) handler(vlaExtId)
        }
        override fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler) {}
        override val extmapAllowMixed = false
        override fun onExtmapAllowMixedChanged(handler: ExtmapAllowMixedChangedHandler) {}
        override val primaryMediaSsrcs: Set<Long> = setOf(1L, 2L, 3L)
        override val receiveSsrcs: Set<Long> = setOf(1L, 2L, 3L)
        override fun getLocalPrimarySsrc(secondarySsrc: Long): Long? = null
        override fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? = null
        override fun debugState(mode: DebugStateMode): ObjectNode = JsonNodeFactory.instance.objectNode()
    }

    /** A three-encoding VP8 simulcast source, SSRCs 1, 2 and 3. */
    private val source = MediaSourceDesc(
        arrayOf(
            RtpEncodingDesc(1L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0))),
            RtpEncodingDesc(2L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(1, 0, -1, 360, 30.0))),
            RtpEncodingDesc(3L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(2, 0, -1, 720, 30.0)))
        ),
        "owner",
        "name"
    )
    private val clock = FakeClock()
    private val node = VlaReaderNode(streamInformationStore, StdoutLogger(), clock).also {
        it.handleEvent(SetMediaSourcesEvent(arrayOf(source), arrayOf(source)))
    }

    /**
     * A packet of SSRC [ssrc] with sequence number [seq], carrying the allocation [vla], received at [nowMs]. As in
     * the pipeline, the encoding's liveness tracker records the packet before the node reads the allocation.
     */
    private fun packet(ssrc: Long, nowMs: Long, seq: Int, vararg vla: Byte) {
        source.findRtpEncodingDesc(ssrc)?.liveness?.onPacketReceived(nowMs, seq, seq * 3000L)
        val rtpPacket = RtpPacket(ByteArray(1500), 0, 1500).apply {
            version = 2
            payloadType = 100
            sequenceNumber = seq
            timestamp = seq * 3000L
            this.ssrc = ssrc
        }
        val ext = rtpPacket.addHeaderExtension(vlaExtId, vla.size)
        vla.forEachIndexed { i, b -> ext.buffer[ext.dataOffset + i] = b }
        clock.setTime(Instant.ofEpochMilli(nowMs))
        node.processPacket(PacketInfo(rtpPacket))
    }

    private fun live(nowMs: Long) = source.rtpEncodings.map { it.liveness.isLive(nowMs) }

    init {
        context("An allocation listing every stream") {
            // RID 0, three streams, one spatial layer each; one temporal layer each; 100, 50 and 10 kbps.
            packet(1L, 1000, 1000 / 100, 0x21, 0x00, 0x64, 0x32, 0x0A)
            should("mark every encoding as sent") {
                live(1000) shouldBe listOf(true, true, true)
            }
            context("followed by one covering only the two lowest streams") {
                // RID 0, two streams, one spatial layer each; 100 and 50 kbps.
                packet(1L, 2000, 2000 / 100, 0x11, 0x00, 0x64, 0x32)
                should("mark the top encoding as not sent") {
                    live(2000) shouldBe listOf(true, true, false)
                    source.rtpEncodings[2].liveness.signaledOff shouldBe true
                }
                should("not keep it so once media arrives on it") {
                    source.rtpEncodings[2].liveness.onPacketReceived(2500, 1, 90_000L)
                    live(2500) shouldBe listOf(true, true, true)
                }
            }
            context("followed by one with no active layer on the middle stream") {
                // RID 0, three streams with their own bitmasks: streams 0 and 2 have layer 0, stream 1 none.
                packet(1L, 2000, 2000 / 100, 0x20, 0x10, 0x10, 0x00, 0x64, 0x0A)
                should("mark the middle encoding as not sent") {
                    live(2000) shouldBe listOf(true, false, true)
                }
            }
        }
        context("An allocation carried by a packet of another encoding") {
            // RID 2: the packet's stream is the third.
            packet(3L, 1000, 1000 / 100, -0x5F, 0x00, 0x64, 0x32, 0x0A)
            should("apply to the whole source") {
                live(1000) shouldBe listOf(true, true, true)
            }
        }
        context("An allocation on a packet older than the last one read") {
            packet(1L, 1000, 10, 0x21, 0x00, 0x64, 0x32, 0x0A)
            packet(1L, 2000, 20, 0x11, 0x00, 0x64, 0x32)
            should("be ignored as stale, whatever it says") {
                packet(1L, 2100, 15, 0x21, 0x00, 0x64, 0x32, 0x0A)
                live(2100) shouldBe listOf(true, true, false)
            }
            should("be ignored whichever stream carries it, judged by that stream's own frames") {
                // RID 1: the packets' stream is the second.
                packet(2L, 2050, 30, 0x51, 0x00, 0x64, 0x32)
                packet(2L, 2100, 25, 0x61, 0x00, 0x64, 0x32, 0x0A)
                live(2100) shouldBe listOf(true, true, false)
            }
            should("not be ignored on a late packet of the current frame") {
                // Packet 21 of the same frame as packet 20 arrives after it, with the allocation.
                source.rtpEncodings[0].liveness.onPacketReceived(2100, 21, 20 * 3000L)
                packet(1L, 2100, 20, 0x21, 0x00, 0x64, 0x32, 0x0A)
                live(2100) shouldBe listOf(true, true, true)
            }
        }
        context("An allocation covering more streams than the source has encodings") {
            // RID 0, four streams, one spatial layer each; one temporal layer each; four bitrates.
            packet(1L, 1000, 10, 0x31, 0x00, 0x64, 0x32, 0x0A, 0x05)
            should("mark the encodings it has as sent, and not fail on the rest") {
                live(1000) shouldBe listOf(true, true, true)
            }
        }
        context("With trust in the sender's signaling turned off") {
            withNewConfig("jmt.rtp.encoding-liveness.trust-signaling=false") {
                val distrustful = VlaReaderNode(streamInformationStore, StdoutLogger(), clock).also {
                    it.handleEvent(SetMediaSourcesEvent(arrayOf(source), arrayOf(source)))
                }
                val rtpPacket = RtpPacket(ByteArray(1500), 0, 1500).apply {
                    version = 2
                    payloadType = 100
                    sequenceNumber = 10
                    timestamp = 90_000L
                    ssrc = 1L
                }
                val ext = rtpPacket.addHeaderExtension(vlaExtId, 5)
                byteArrayOf(0x21, 0x00, 0x64, 0x32, 0x0A).forEachIndexed { i, b -> ext.buffer[ext.dataOffset + i] = b }
                clock.setTime(Instant.ofEpochMilli(1000))
                distrustful.processPacket(PacketInfo(rtpPacket))
                should("leave liveness to the media") {
                    live(1000) shouldBe listOf(false, false, false)
                }
            }
        }
        context("An allocation listing no layer for the stream carrying it") {
            // RID 0, three streams with their own bitmasks: stream 0 has no layer, streams 1 and 2 have layer 0.
            packet(1L, 1000, 1000 / 100, 0x20, 0x01, 0x10, 0x00, 0x64, 0x0A)
            should("not mark the packet's own encoding as not sent") {
                live(1000) shouldBe listOf(true, true, true)
            }
        }
        context("An allocation whose RID does not name the packet's encoding") {
            // RID 1 on a packet of the first encoding.
            packet(1L, 1000, 1000 / 100, 0x61, 0x00, 0x64, 0x32, 0x0A)
            should("be ignored") {
                live(1000) shouldBe listOf(true, false, false)
            }
        }
        context("An empty allocation") {
            packet(1L, 1000, 1000 / 100, 0x00)
            should("say nothing about what is sent, beyond the packet's own encoding being live") {
                live(1000) shouldBe listOf(true, false, false)
            }
        }
    }
}
