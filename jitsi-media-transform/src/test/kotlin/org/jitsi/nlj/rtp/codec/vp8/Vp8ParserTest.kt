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
package org.jitsi.nlj.rtp.codec.vp8

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc

class Vp8ParserTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val ssrcA = 0x11111111L
    private val ssrcB = 0x22222222L

    /** Three temporal layers of encoding [eid], each depending on the one below, as signaled VP8 layers are. */
    private fun layers(eid: Int, height: Int): Array<RtpLayerDesc> {
        val t0 = VpxRtpLayerDesc(eid, 0, -1, height, 7.5)
        val t1 = VpxRtpLayerDesc(eid, 1, -1, height, 15.0, dependencyLayers = arrayOf(t0))
        val t2 = VpxRtpLayerDesc(eid, 2, -1, height, 30.0, dependencyLayers = arrayOf(t1))
        return arrayOf(t0, t1, t2)
    }

    private val source = MediaSourceDesc(
        arrayOf(RtpEncodingDesc(ssrcA, layers(0, 180)), RtpEncodingDesc(ssrcB, layers(1, 360))),
        "owner",
        "name"
    )
    private val encodingA = source.rtpEncodings[0]
    private val encodingB = source.rtpEncodings[1]

    private val parser = Vp8Parser(source, StdoutLogger())

    private val baseTimestamp = 100_000_000L

    /**
     * The first packet of a VP8 keyframe of [width] by [height], with a minimal payload descriptor. The dimensions are
     * written as given, so the scale bits above bit 13 can be set. Without [startCode] the keyframe header's start
     * code is wrong; [truncate] cuts that many bytes off the end of the packet.
     */
    private fun keyframe(
        ssrc: Long,
        timestamp: Long,
        width: Int,
        height: Int,
        sequenceNumber: Int = 1,
        startCode: Boolean = true,
        truncate: Int = 0
    ): Vp8Packet {
        val buf = byteArrayOf(
            // RTP header: V=2, PT=100, sequence number, timestamp, SSRC.
            0x80.toByte(), 100, (sequenceNumber shr 8).toByte(), sequenceNumber.toByte(),
            (timestamp shr 24).toByte(), (timestamp shr 16).toByte(), (timestamp shr 8).toByte(), timestamp.toByte(),
            (ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte(),
            // VP8 payload descriptor: start of partition 0, no extensions.
            0x10,
            // VP8 payload header: a keyframe, shown. Its first partition size of 0 is not a valid frame, but nothing
            // here reads the partition.
            0x10, 0, 0,
            // VP8 keyframe header: start code, then width and height, little-endian with the scale in the top bits.
            0x9d.toByte(), 0x01, if (startCode) 0x2a else 0x2b,
            width.toByte(), (width shr 8).toByte(),
            height.toByte(), (height shr 8).toByte(),
            0, 0, 0, 0
        )
        val length = buf.size - truncate
        return Vp8Packet(buf.copyOf(length), 0, length)
    }

    /** Parses [packet], returning whether it flagged the layering as changed. */
    private fun parse(packet: Vp8Packet): Boolean = PacketInfo(packet).also { parser.parse(it) }.layeringChanged

    private fun RtpEncodingDesc.heights() = layers.map { it.height }

    init {
        context("A keyframe of a new size") {
            val layersBefore = source.rtpLayers
            val packet = keyframe(ssrcA, baseTimestamp, 1280, 720)
            val changed = parse(packet)
            should("be a keyframe of that size") {
                packet.isKeyframe shouldBe true
                packet.height shouldBe 720
            }
            should("set the height of the encoding's layers, in place") {
                encodingA.heights() shouldContainExactly listOf(720, 720, 720)
                source.rtpLayers.forEachIndexed { i, layer -> layer shouldBeSameInstanceAs layersBefore[i] }
                source.findRtpLayerDescs(packet).map { it.height } shouldContainExactly listOf(720)
            }
            should("leave the other encoding alone") {
                encodingB.heights() shouldContainExactly listOf(360, 360, 360)
            }
            should("flag the layering as changed") {
                changed shouldBe true
            }
            context("followed by a newer keyframe of the same size") {
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp + 90_000, 1280, 720))
                should("change nothing") {
                    encodingA.heights() shouldContainExactly listOf(720, 720, 720)
                    changedAgain shouldBe false
                }
            }
            context("followed by an older keyframe of another size, reordered or retransmitted") {
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 90_000, 640, 360, sequenceNumber = 0))
                should("leave the newer keyframe's size") {
                    encodingA.heights() shouldContainExactly listOf(720, 720, 720)
                    changedAgain shouldBe false
                }
            }
            context("followed by a keyframe of another size exactly two seconds older") {
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 2 * 90_000, 640, 360, sequenceNumber = 0))
                should("still take it for a reordered or retransmitted one") {
                    encodingA.heights() shouldContainExactly listOf(720, 720, 720)
                    changedAgain shouldBe false
                }
            }
            context("followed by an older keyframe on the other encoding") {
                parse(keyframe(ssrcB, baseTimestamp - 90_000, 1280, 720))
                should("apply its size, since keyframes are ordered per encoding") {
                    encodingB.heights() shouldContainExactly listOf(720, 720, 720)
                }
            }
            context("followed by a keyframe of another size from long before, as after a restart of the timestamps") {
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 10 * 90_000, 640, 360))
                should("apply its size") {
                    encodingA.heights() shouldContainExactly listOf(360, 360, 360)
                    changedAgain shouldBe true
                }
            }
            context("followed by a keyframe a little older by timestamp but newer by sequence number") {
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 90_000, 640, 360, sequenceNumber = 50))
                should("apply its size, since the sender restarted its timestamps rather than resent the keyframe") {
                    encodingA.heights() shouldContainExactly listOf(360, 360, 360)
                    changedAgain shouldBe true
                }
            }
            context("followed by the signaled layers with their heights, as when the source is reset") {
                source.setEncodingLayers(layers(0, 180), ssrcA)
                should("take the signaled heights again") {
                    encodingA.heights() shouldContainExactly listOf(180, 180, 180)
                }
                context("and a keyframe older than the one applied") {
                    val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 90_000, 640, 360, sequenceNumber = 0))
                    should("still be ignored, since the keyframes seen are remembered across the reset") {
                        encodingA.heights() shouldContainExactly listOf(180, 180, 180)
                        changedAgain shouldBe false
                    }
                }
            }
            context("after the parser's source changed to one without the encoding's SSRC and back") {
                val other = MediaSourceDesc(arrayOf(RtpEncodingDesc(0x33333333L, layers(0, 180))), "owner", "other")
                parser.source = other
                parser.source = source
                val changedAgain = parse(keyframe(ssrcA, baseTimestamp - 90_000, 640, 360, sequenceNumber = 0))
                should("have forgotten the keyframes seen on it, and apply an older keyframe's size") {
                    encodingA.heights() shouldContainExactly listOf(360, 360, 360)
                    changedAgain shouldBe true
                }
            }
        }
        context("A keyframe header with scale bits set") {
            parse(keyframe(ssrcA, baseTimestamp, 1280 or (1 shl 14), 720 or (2 shl 14)))
            should("take the dimensions without the scale bits") {
                encodingA.heights() shouldContainExactly listOf(720, 720, 720)
            }
        }
        context("A keyframe header with zero dimensions") {
            val changed = parse(keyframe(ssrcA, baseTimestamp, 0, 0))
            should("be ignored") {
                encodingA.heights() shouldContainExactly listOf(180, 180, 180)
                changed shouldBe false
            }
        }
        context("A keyframe header with a wrong start code") {
            val packet = keyframe(ssrcA, baseTimestamp, 1280, 720, startCode = false)
            val changed = parse(packet)
            should("have no size, and be ignored") {
                packet.height shouldBe -1
                packet.width shouldBe -1
                encodingA.heights() shouldContainExactly listOf(180, 180, 180)
                changed shouldBe false
            }
        }
        context("A keyframe packet truncated within the keyframe header") {
            val packet = keyframe(ssrcA, baseTimestamp, 1280, 720, truncate = 6)
            val changed = parse(packet)
            should("have no size, and be ignored") {
                packet.isKeyframe shouldBe true
                packet.height shouldBe -1
                encodingA.heights() shouldContainExactly listOf(180, 180, 180)
                changed shouldBe false
            }
        }
        context("A keyframe just after the RTP timestamp wrapped around") {
            parse(keyframe(ssrcA, 1000, 1280, 720))
            parse(keyframe(ssrcA, (1000L - 90_000) and 0xFFFF_FFFFL, 640, 360, sequenceNumber = 0))
            should("still take a keyframe from just before the wrap for an older one") {
                encodingA.heights() shouldContainExactly listOf(720, 720, 720)
            }
        }
        context("A portrait keyframe") {
            parse(keyframe(ssrcA, baseTimestamp, 720, 1280))
            should("set the height to the lesser dimension, as for other codecs") {
                encodingA.heights() shouldContainExactly listOf(720, 720, 720)
            }
        }
    }
}
