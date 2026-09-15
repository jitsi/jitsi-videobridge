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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.nlj.util.bits
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock

/**
 * A frame is folded in once a newer frame has started and the tracker's grace period has passed, and an encoding
 * counts towards the cost once it has been observed for the tracker's warm-up period, so tests advance the clock
 * explicitly.
 */
class VideoBitrateCalculatorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val clock = FakeClock()
    private val calculator = VideoBitrateCalculator(StdoutLogger(), clock = clock)

    private fun send(ssrc: Long, timestamp: Long, length: Int, isKeyframe: Boolean, layerId: Int = 0) {
        calculator.processPacket(PacketInfo(FakeParsedVideoPacket(ssrc, timestamp, length, isKeyframe, layerId)))
    }

    private fun setSources(vararg sources: MediaSourceDesc) {
        calculator.handleEvent(SetMediaSourcesEvent(arrayOf(*sources), arrayOf(*sources)))
    }

    init {
        context("with keyframe budget limiting enabled") {
            withNewConfig("jmt.keyframe.budget.enabled=true") {
                context("a K-SVC source") {
                    val ssrc = 0xdeadbeefL
                    // As Vp9Packet describes K-SVC: spatial layers depend on each other only softly, and the
                    // parser turns the soft dependencies off for K-SVC inter frames, so the top layer's cumulative
                    // bitrate excludes the lower spatial layers.
                    val layers = ArrayList<VpxRtpLayerDesc>()
                    for (sid in 0..2) {
                        layers.add(
                            VpxRtpLayerDesc(0, -1, sid, 180 shl sid, 30.0, emptyArray(), layers.toTypedArray()).apply {
                                useSoftDependencies = false
                            }
                        )
                    }
                    val source = MediaSourceDesc(
                        arrayOf(RtpEncodingDesc(ssrc, layers.toTypedArray<RtpLayerDesc>())),
                        "owner",
                        "name"
                    )
                    setSources(source)

                    context("before any keyframe has been seen") {
                        send(ssrc, 1000, 500, false, RtpLayerDesc.getIndex(0, 0, 0))
                        clock.elapse(1.secs)
                        send(ssrc, 4000, 500, false, RtpLayerDesc.getIndex(0, 0, 0))
                        should("have no keyframe cost") {
                            calculator.getKeyframeCost(ssrc).shouldBeNull()
                        }
                    }

                    context("after a keyframe marked only on spatial layer 0") {
                        repeat(3) { send(ssrc, 1000, 1000, true, RtpLayerDesc.getIndex(0, 0, 0)) }
                        repeat(2) { send(ssrc, 1000, 1000, false, RtpLayerDesc.getIndex(0, 1, 0)) }
                        repeat(4) { send(ssrc, 1000, 1000, false, RtpLayerDesc.getIndex(0, 2, 0)) }
                        context("but before the stream has warmed up") {
                            clock.elapse(300.ms)
                            send(ssrc, 4000, 500, false, RtpLayerDesc.getIndex(0, 0, 0))
                            should("have no keyframe cost") {
                                calculator.getKeyframeCost(ssrc).shouldBeNull()
                            }
                        }
                        context("once the stream has warmed up") {
                            clock.elapse(1.secs)
                            send(ssrc, 4000, 500, false, RtpLayerDesc.getIndex(0, 0, 0))
                            should("cost the size of all of the keyframe's spatial layers") {
                                val cost = calculator.getKeyframeCost(ssrc).shouldNotBeNull()
                                cost.keyframeSize shouldBe (9 * 1000 * 8L).bits
                                // 9500 bytes over exactly 1s since the first packet.
                                cost.sourceBitrate.bps shouldBe 9500 * 8L
                            }
                            should("count all spatial layers in the source bitrate, not only the top layer's") {
                                val cost = calculator.getKeyframeCost(ssrc).shouldNotBeNull()
                                cost.sourceBitrate.bps shouldBeGreaterThan layers.last().getBitrate(clock.millis()).bps
                            }
                            should("be found by any of the source's SSRCs") {
                                source.rtpEncodings[0].addSecondarySsrc(0x5e7bL, SsrcAssociationType.RTX)
                                calculator.getKeyframeCost(0x5e7bL).shouldNotBeNull()
                            }
                            should("expose the tracker and the cost in the node stats") {
                                val stats = calculator.getNodeStats().toJson()
                                stats["keyframe_budget_enabled"].asBoolean() shouldBe true
                                val tracker = stats["keyframe_tracker_$ssrc"].shouldNotBeNull()
                                tracker["num_keyframes"].asInt() shouldBe 1
                                tracker["mean_keyframe_bits"].asDouble() shouldBe 9 * 1000 * 8.0
                                tracker["last_keyframe_bits"].asLong() shouldBe 9 * 1000 * 8L
                                tracker["stream_bitrate_bps"].asLong() shouldBe 9500 * 8L
                                tracker["warm"].asBoolean() shouldBe true
                                tracker["frames_in_flight"].asInt() shouldBe 1
                                tracker["newest_frame_timestamp"].asLong() shouldBe 4000L
                                tracker["newest_frame_bytes"].asLong() shouldBe 500L
                                tracker["newest_frame_is_keyframe"].asBoolean() shouldBe false
                                val cost = stats["keyframe_cost_$ssrc"]
                                cost["keyframe_bits"].asLong() shouldBe 9 * 1000 * 8L
                                cost["keyframe_bitrate_bps"].asLong() shouldBeGreaterThan 0L
                                cost["keyframe_fraction"].asDouble() shouldBeGreaterThan 0.0
                            }
                        }
                    }

                    context("an unknown SSRC") {
                        should("have no keyframe cost") {
                            calculator.getKeyframeCost(0x12345678L).shouldBeNull()
                        }
                    }
                }

                context("a simulcast source") {
                    val ssrcA = 0xaaaaL
                    val ssrcB = 0xbbbbL
                    val source = MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(ssrcA, arrayOf(VpxRtpLayerDesc(0, -1, -1, 180, 30.0))),
                            RtpEncodingDesc(ssrcB, arrayOf(VpxRtpLayerDesc(1, -1, -1, 360, 30.0)))
                        ),
                        "owner",
                        "name"
                    )
                    setSources(source)

                    // The encodings have independent timestamp bases and their packets interleave. A's keyframe is
                    // 3 packets, B's is 2.
                    send(ssrcA, 1000, 1000, true)
                    send(ssrcB, 5000, 500, false)
                    send(ssrcA, 1000, 1000, false)
                    send(ssrcB, 5000, 500, false)
                    send(ssrcA, 1000, 1000, false)
                    send(ssrcB, 8000, 1000, true)
                    send(ssrcA, 4000, 500, false)
                    send(ssrcB, 8000, 1000, false)
                    clock.elapse(1.secs)
                    send(ssrcA, 7000, 500, false)
                    send(ssrcB, 11000, 500, false)

                    should("cost the sum of one keyframe from each encoding") {
                        val cost = calculator.getKeyframeCost(ssrcA).shouldNotBeNull()
                        cost.keyframeSize shouldBe (3 * 1000 * 8L + 2 * 1000 * 8L).bits
                        // Everything sent, over exactly 1s since the first packet.
                        cost.sourceBitrate.bps shouldBe (3000 + 500 + 500 + 1000 + 500 + 1000 + 500 + 500) * 8L
                    }

                    context("when one encoding stops being sent") {
                        clock.elapse(60.secs)
                        send(ssrcA, 10000, 1000, true)
                        clock.elapse(1.secs)
                        send(ssrcA, 13000, 500, false)
                        should("cost only the keyframes and bitrate of the encodings still being sent") {
                            val cost = calculator.getKeyframeCost(ssrcA).shouldNotBeNull()
                            cost.keyframeSize shouldBe (24000 * 0.75 + 8000 * 0.25).toLong().bits
                            val stats = calculator.getNodeStats().toJson()
                            val trackerA = stats["keyframe_tracker_$ssrcA"]
                            cost.sourceBitrate.bps shouldBe trackerA["stream_bitrate_bps"].asLong()
                        }
                    }

                    context("when the source is removed") {
                        setSources()
                        should("have no keyframe cost") {
                            calculator.getKeyframeCost(ssrcA).shouldBeNull()
                        }
                    }
                }

                context("a source of a codec which is not parsed") {
                    val ssrc = 0xdeadbeefL
                    val source = MediaSourceDesc(
                        arrayOf(RtpEncodingDesc(ssrc, arrayOf(VpxRtpLayerDesc(0, -1, -1, 720, 30.0)))),
                        "owner",
                        "name"
                    )
                    setSources(source)
                    // A parsed keyframe, then unparsed packets, as after a switch to a codec the bridge does not parse.
                    send(ssrc, 1000, 1000, true)
                    calculator.processPacket(PacketInfo(FakeVideoPacket(ssrc, 4000, 2000)))
                    clock.elapse(1.secs)
                    calculator.processPacket(PacketInfo(FakeVideoPacket(ssrc, 7000, 500)))
                    should("count the unparsed packets in the bitrate") {
                        val cost = calculator.getKeyframeCost(ssrc).shouldNotBeNull()
                        cost.keyframeSize shouldBe (1000 * 8L).bits
                        cost.sourceBitrate.bps shouldBe (1000 + 2000 + 500) * 8L
                    }
                }

                context("a simulcast source with an encoding on which no keyframe has been seen") {
                    val ssrcA = 0xaaaaL
                    val ssrcB = 0xbbbbL
                    val source = MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(ssrcA, arrayOf(VpxRtpLayerDesc(0, -1, -1, 180, 30.0))),
                            RtpEncodingDesc(ssrcB, arrayOf(VpxRtpLayerDesc(1, -1, -1, 360, 30.0)))
                        ),
                        "owner",
                        "name"
                    )
                    setSources(source)
                    send(ssrcA, 1000, 1000, true)
                    send(ssrcB, 5000, 5000, false)
                    clock.elapse(1.secs)
                    send(ssrcA, 4000, 500, false)
                    send(ssrcB, 8000, 5000, false)
                    should("leave that encoding out of both the keyframe size and the bitrate") {
                        val cost = calculator.getKeyframeCost(ssrcA).shouldNotBeNull()
                        cost.keyframeSize shouldBe (1000 * 8L).bits
                        cost.sourceBitrate.bps shouldBe (1000 + 500) * 8L
                    }
                }
            }
        }

        context("with keyframe budget limiting disabled") {
            val ssrc = 0xdeadbeefL
            val source = MediaSourceDesc(
                arrayOf(RtpEncodingDesc(ssrc, arrayOf(VpxRtpLayerDesc(0, -1, -1, 720, 30.0)))),
                "owner",
                "name"
            )
            setSources(source)
            send(ssrc, 1000, 1000, true)
            clock.elapse(1.secs)
            send(ssrc, 4000, 500, false)
            should("not measure a keyframe cost") {
                calculator.getKeyframeCost(ssrc).shouldBeNull()
                calculator.getNodeStats().toJson()["keyframe_tracker_$ssrc"].shouldBeNull()
            }
        }
    }
}

/** An unparsed video packet, as for a codec the bridge does not parse. */
private class FakeVideoPacket(ssrc: Long, timestamp: Long, length: Int) :
    VideoRtpPacket(ByteArray(length).also { it[0] = 0x80.toByte() }, 0, length, 0) {
    init {
        this.ssrc = ssrc
        this.timestamp = timestamp
    }
}

/** A video packet with the properties the calculator reads, and nothing else. */
private class FakeParsedVideoPacket(
    ssrc: Long,
    timestamp: Long,
    length: Int,
    override val isKeyframe: Boolean,
    layerId: Int
) : ParsedVideoPacket(ByteArray(length).also { it[0] = 0x80.toByte() }, 0, length, 0) {
    init {
        this.ssrc = ssrc
        this.timestamp = timestamp
    }

    override val layerIds: Collection<Int> = listOf(layerId)
    override val isStartOfFrame: Boolean = true
    override val isEndOfFrame: Boolean = true
    override fun meetsRoutingNeeds() = true
}
