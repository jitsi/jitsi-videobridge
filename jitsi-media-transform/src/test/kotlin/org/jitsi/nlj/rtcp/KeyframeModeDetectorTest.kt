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

package org.jitsi.nlj.rtcp

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc

class KeyframeModeDetectorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val source = MediaSourceDesc(
        arrayOf(
            RtpEncodingDesc(1L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0))),
            RtpEncodingDesc(2L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(1, 0, -1, 360, 30.0))),
            RtpEncodingDesc(3L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(2, 0, -1, 720, 30.0)))
        ),
        "owner",
        "name"
    )
    private val response = KeyframeModeDetector.RESPONSE_WINDOW_MS

    /** A detector of [source], reading the configuration as it is when created. */
    private fun newDetector() = KeyframeModeDetector(StdoutLogger()).also { it.setMediaSources(arrayOf(source)) }
    private var detector = newDetector()

    /** Marks encodings [ssrcs] as live at [nowMs], as they are judged when a request is made. */
    private fun live(nowMs: Long, vararg ssrcs: Long) =
        source.rtpEncodings.filter { it.primarySSRC in ssrcs }.forEach { it.liveness.onPacketReceived(nowMs) }

    /** A media packet on each of [ssrcs] at [nowMs], as the receive pipeline reports and records it. */
    private fun sending(nowMs: Long, vararg ssrcs: Long) {
        detector.onPacketObserved(nowMs)
        live(nowMs, *ssrcs)
    }

    /** The state of source 1 as of [nowMs], closing an observation which is due, as the media path would. */
    private fun stats(nowMs: Long): JsonNode {
        detector.onPacketObserved(nowMs)
        return detector.debugState()["source_1"]
    }

    /** The mode of the source with [ssrc] as of [nowMs], after closing an observation which is due, as the media path
     * would. */
    private fun modeAt(ssrc: Long, nowMs: Long): SenderKeyframeMode {
        detector.onPacketObserved(nowMs)
        return detector.getMode(ssrc)
    }

    /**
     * A request for [ssrc] at [t] answered by keyframes on [answers] (SSRC to arrival time), all encodings live at
     * the request and still sending afterwards.
     */
    private fun observe(t: Long, ssrc: Long, answers: Map<Long, Long>) {
        live(t, 1L, 2L, 3L)
        detector.onKeyframeRequested(ssrc, t)
        answers.toList().sortedBy { it.second }.forEach { (s, at) -> detector.onKeyframeObserved(s, at) }
        sending(t + 1000, 1L, 2L, 3L)
    }

    init {
        context("with no observations") {
            should("not know the mode") {
                modeAt(1L, 0) shouldBe SenderKeyframeMode.UNKNOWN
                modeAt(3L, 0) shouldBe SenderKeyframeMode.UNKNOWN
            }
            should("count a request against every encoding and send source-wide requests to the primary SSRC") {
                detector.limiterKeys(3L) shouldContainExactly listOf(1L, 2L, 3L)
                detector.limiterKeys(1L) shouldContainExactly listOf(1L, 2L, 3L)
                detector.requestSsrcsForSource(1L, 0) shouldContainExactly listOf(1L)
            }
            should("send a source-wide request to an encoding being sent when the requested encoding is not") {
                live(30000, 2L, 3L)
                detector.requestSsrcsForSource(1L, 30000) shouldContainExactly listOf(2L)
                detector.requestSsrcsForSource(3L, 30000) shouldContainExactly listOf(3L)
            }
            should("leave an SSRC of no known source alone") {
                detector.limiterKeys(99L) shouldContainExactly listOf(99L)
                modeAt(99L, 0) shouldBe SenderKeyframeMode.UNKNOWN
            }
        }

        context("a request answered by keyframes on every live encoding, one of which had just resumed") {
            // Every encoding flowing at 30 fps until 4500, then stopped; the request comes at 5000, all still live.
            for (t in 0..4500 step 30) {
                source.rtpEncodings.forEachIndexed { i, enc ->
                    enc.liveness.onPacketReceived(t.toLong(), t / 30, t * 90L + i)
                }
            }
            detector.onKeyframeRequested(3L, 5000)
            detector.onKeyframeObserved(3L, 5100)
            detector.onKeyframeObserved(1L, 5110)
            // Encoding 2 has sent nothing since 4500; its keyframe at 5600 starts a new run of frames.
            source.rtpEncodings[1].liveness.onPacketReceived(5600, 200, 5600 * 90L)
            detector.onKeyframeObserved(2L, 5600)
            should("be discarded, since a resumed encoding generates a keyframe in either mode") {
                val closed = 5600 + KeyframeModeDetector.CLUSTER_WINDOW_MS + 1
                stats(closed)["num_discarded"].asInt() shouldBe 1
                stats(closed)["num_clustered"].asInt() shouldBe 0
            }
        }

        context("a request answered by keyframes on every live encoding together") {
            // The large keyframe is paced out well behind the smaller keyframes, as on a constrained uplink.
            observe(0, 3L, mapOf(1L to 100L, 2L to 105L, 3L to 700L))
            should("be evidence for the clustered mode, once a cluster window has passed since the answer") {
                stats(200)["num_clustered"].asInt() shouldBe 0
                stats(response + 1)["num_clustered"].asInt() shouldBe 0
                val closed = 700 + KeyframeModeDetector.CLUSTER_WINDOW_MS + 1
                stats(closed)["num_clustered"].asInt() shouldBe 1
                modeAt(1L, closed) shouldBe SenderKeyframeMode.UNKNOWN
            }
            context("twice") {
                observe(5000, 1L, mapOf(1L to 5100L, 2L to 5100L, 3L to 5110L))
                should("settle the mode as clustered") {
                    modeAt(3L, 10000) shouldBe SenderKeyframeMode.CLUSTERED
                    stats(10000)["num_clustered"].asInt() shouldBe 2
                    detector.limiterKeys(3L) shouldContainExactly listOf(1L, 2L, 3L)
                    detector.requestSsrcsForSource(1L, 10000) shouldContainExactly listOf(1L)
                }
                context("and then answered on the requested encoding alone, three times") {
                    observe(10000, 3L, mapOf(3L to 10100L))
                    observe(15000, 2L, mapOf(2L to 15100L))
                    should("not change the mode yet") {
                        modeAt(3L, 20000) shouldBe SenderKeyframeMode.CLUSTERED
                    }
                    observe(20000, 1L, mapOf(1L to 20100L))
                    should("change the mode to per-encoding") {
                        modeAt(3L, 25000) shouldBe SenderKeyframeMode.PER_ENCODING
                        detector.limiterKeys(3L) shouldContainExactly listOf(3L)
                    }
                }
            }
        }

        context("a request answered by a keyframe on the requested encoding alone") {
            observe(0, 3L, mapOf(3L to 110L))
            should("be evidence for the per-encoding mode") {
                stats(response + 1)["num_per_encoding"].asInt() shouldBe 1
                modeAt(1L, response + 1) shouldBe SenderKeyframeMode.UNKNOWN
            }
            context("three times") {
                observe(5000, 2L, mapOf(2L to 5100L))
                observe(5000 + 2500, 3L, mapOf(3L to 7600L))
                observe(10000, 1L, mapOf(1L to 10100L))
                /* The media path closes the last observation. */
                sending(10000 + response + 1, 1L, 2L, 3L)
                should("settle the mode as per-encoding, which takes one observation more than clustered") {
                    modeAt(3L, 10000) shouldBe SenderKeyframeMode.PER_ENCODING
                }
                should("count a request against its own encoding only") {
                    detector.limiterKeys(3L) shouldContainExactly listOf(3L)
                    detector.limiterKeys(1L) shouldContainExactly listOf(1L)
                }
                should("send source-wide requests to every live encoding") {
                    live(20000, 1L, 3L)
                    detector.requestSsrcsForSource(1L, 20000) shouldContainExactly listOf(1L, 3L)
                }
                should("fall back to the primary SSRC when no encoding is live") {
                    detector.requestSsrcsForSource(1L, 60000) shouldContainExactly listOf(1L)
                }
                context("and then answered on every encoding, twice") {
                    observe(15000, 3L, mapOf(1L to 15100L, 2L to 15105L, 3L to 15110L))
                    observe(20000, 3L, mapOf(1L to 20100L, 2L to 20105L, 3L to 20110L))
                    should("change the mode back to clustered") {
                        modeAt(3L, 25000) shouldBe SenderKeyframeMode.CLUSTERED
                    }
                }
            }
            context("followed by an observation of the other kind") {
                observe(5000, 3L, mapOf(1L to 5100L, 2L to 5100L, 3L to 5110L))
                should("not settle the mode") {
                    modeAt(3L, 10000) shouldBe SenderKeyframeMode.UNKNOWN
                    stats(10000)["evidence_count"].asInt() shouldBe 1
                }
            }
        }

        context("a request which is not answered on the requested encoding") {
            observe(0, 3L, mapOf(1L to 100L))
            should("be no evidence") {
                val s = stats(response + 1)
                s["num_unanswered"].asInt() shouldBe 1
                s["num_clustered"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 0
            }
        }

        context("a request while only one encoding is live") {
            live(0, 1L)
            detector.onKeyframeRequested(1L, 0)
            detector.onKeyframeObserved(1L, 100)
            should("be no evidence") {
                val s = stats(response + 1)
                s["num_clustered"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 0
            }
        }

        context("a request answered together with keyframes on encodings which were not live") {
            live(0, 1L)
            detector.onKeyframeRequested(1L, 0)
            listOf(1L, 2L, 3L).forEach { detector.onKeyframeObserved(it, 100) }
            should("be discarded, since the sender may have just turned them on") {
                val s = stats(response + 1)
                s["num_discarded"].asInt() shouldBe 1
                s["num_clustered"].asInt() shouldBe 0
            }
        }

        context("a request followed by another for the source before it is answered") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            detector.onKeyframeRequested(1L, 50)
            context("answered on the two encodings requested, while the third kept sending") {
                detector.onKeyframeObserved(3L, 100)
                detector.onKeyframeObserved(1L, 150)
                sending(500, 2L)
                should("be evidence for the per-encoding mode from the encoding not requested") {
                    // The second request extended the observation by a response window.
                    val s = stats(50 + response + 1)
                    s["num_per_encoding"].asInt() shouldBe 1
                    s["num_discarded"].asInt() shouldBe 0
                }
            }
            context("answered on every encoding together") {
                detector.onKeyframeObserved(1L, 100)
                detector.onKeyframeObserved(2L, 105)
                detector.onKeyframeObserved(3L, 110)
                should("be evidence for the clustered mode") {
                    stats(50 + response + 1)["num_clustered"].asInt() shouldBe 1
                }
            }
        }

        context("a request joining an observation shortly before it would have closed") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(1L, 0)
            detector.onKeyframeObserved(1L, 100)
            live(1400, 1L, 2L, 3L)
            detector.onKeyframeRequested(2L, 1400)
            // Answered after the original window, within the extended window.
            detector.onKeyframeObserved(2L, 1650)
            sending(1700, 3L)
            should("await its answer, and count it as an answer rather than a companion keyframe") {
                val s = stats(1400 + response + 1)
                s["num_per_encoding"].asInt() shouldBe 1
                s["num_clustered"].asInt() shouldBe 0
            }
        }

        context("a request joining after the answer extended the observation past the cap on joins") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            listOf(1400L, 2800L, 4200L).forEach {
                live(it, 1L, 2L, 3L)
                detector.onKeyframeRequested(3L, it)
            }
            detector.onKeyframeObserved(3L, 4900)
            live(4950, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 4950)
            detector.onKeyframeObserved(1L, 5300)
            detector.onKeyframeObserved(2L, 5310)
            should("not shorten it, so that the companion keyframes still count") {
                detector.debugState()["open_observations"].asInt() shouldBe 1
                stats(4900 + KeyframeModeDetector.CLUSTER_WINDOW_MS + 1)["num_clustered"].asInt() shouldBe 1
            }
        }

        context("a request joining for an encoding which came on after the observation opened") {
            live(0, 1L, 2L)
            detector.onKeyframeRequested(1L, 0)
            detector.onKeyframeObserved(1L, 100)
            live(200, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 200)
            detector.onKeyframeObserved(3L, 300)
            sending(1000, 1L, 2L, 3L)
            should("expect its answer rather than discard the observation for it") {
                val s = stats(200 + response + 1)
                s["num_discarded"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 1
            }
        }

        context("a companion keyframe answering an unobserved request, followed by another such request") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            detector.onKeyframeRequested(1L, 100, observable = false)
            detector.onKeyframeObserved(3L, 250)
            detector.onKeyframeObserved(1L, 300)
            detector.onKeyframeRequested(1L, 400, observable = false)
            sending(1000, 1L, 2L, 3L)
            should("still be recognized as an answer, not testimony") {
                val s = stats(response + 1)
                s["num_clustered"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 1
            }
        }

        context("a late answer to a request from a closed observation") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(2L, 0)
            // Unanswered within its window.
            stats(response + 1)["num_unanswered"].asInt() shouldBe 1
            live(1600, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 1600)
            // The late answer to the earlier request arrives during the new observation, near the new answer.
            detector.onKeyframeObserved(2L, 1650)
            detector.onKeyframeObserved(3L, 1800)
            sending(2000, 1L)
            should("not count as a companion keyframe") {
                val s = stats(1600 + response + 1)
                s["num_clustered"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 1
            }
        }

        context("a source removed while an observation of it is open") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            detector.debugState()["open_observations"].asInt() shouldBe 1
            detector.setMediaSources(arrayOf())
            should("forget the observation, so that the packet path's short cut is restored") {
                detector.debugState()["source_1"] shouldBe null
                detector.debugState()["open_observations"].asInt() shouldBe 0
            }
        }

        context("an observation whose source goes silent past its deadline") {
            val other = MediaSourceDesc(
                arrayOf(RtpEncodingDesc(11L, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0)))),
                "owner",
                "other"
            )
            detector.setMediaSources(arrayOf(source, other))
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            should("be closed by a packet of another source") {
                detector.onPacketObserved(response + 1)
                // Asked for at a time before the deadline, so this reflects the closing above, not a closing of its
                // own.
                detector.debugState()["open_observations"].asInt() shouldBe 0
                detector.debugState()["source_1"]["num_unanswered"].asInt() shouldBe 1
            }
        }

        context("a companion keyframe followed by a request for its encoding within the observation") {
            observe(0, 3L, mapOf(1L to 100L, 2L to 105L, 3L to 110L))
            // A set of requests for every encoding, which observes nothing, but is remembered as requests.
            listOf(1L, 2L, 3L).forEach { detector.onKeyframeRequested(it, 500, observable = false) }
            should("still count the keyframe, since it came before the request") {
                stats(response + 1)["num_clustered"].asInt() shouldBe 1
            }
        }

        context("a request for an encoding which is not being sent") {
            live(0, 1L, 2L)
            detector.onKeyframeRequested(3L, 0)
            should("open no observation, since only an encoding being turned on could answer it") {
                detector.debugState()["open_observations"].asInt() shouldBe 0
            }
        }

        context("a set of requests for every encoding, not to be observed") {
            live(0, 1L, 2L, 3L)
            listOf(1L, 2L, 3L).forEach { detector.onKeyframeRequested(it, 0, observable = false) }
            should("open no observation") {
                detector.debugState()["open_observations"].asInt() shouldBe 0
            }
            context("followed by an observable request on another encoding") {
                live(500, 1L, 2L, 3L)
                detector.onKeyframeRequested(1L, 500)
                // The late answers to the set arrive during the observation, together with its own answer.
                detector.onKeyframeObserved(2L, 600)
                detector.onKeyframeObserved(3L, 610)
                detector.onKeyframeObserved(1L, 620)
                should("not take their answers for companion keyframes") {
                    val s = stats(500 + response + 1)
                    s["num_clustered"].asInt() shouldBe 0
                }
            }
        }

        context("requests for every live encoding at once, as sent ahead of a dominant speaker change") {
            live(0, 1L, 2L, 3L)
            listOf(1L, 2L, 3L).forEach { detector.onKeyframeRequested(it, 0) }
            listOf(1L, 2L, 3L).forEach { detector.onKeyframeObserved(it, 100) }
            should("be inconclusive, since no encoding was left to observe") {
                val s = stats(response + 1)
                s["num_inconclusive"].asInt() shouldBe 1
                s["num_clustered"].asInt() shouldBe 0
                s["num_per_encoding"].asInt() shouldBe 0
            }
        }

        context("a request answered on the requested encoding alone while the other encodings stopped sending") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            detector.onKeyframeObserved(3L, 100)
            should("be no evidence, since the sender may have turned them off") {
                val s = stats(response + 1)
                s["num_inconclusive"].asInt() shouldBe 1
                s["num_per_encoding"].asInt() shouldBe 0
            }
            context("except one which kept sending") {
                // Close the observation above first, so that only the observation below is evaluated.
                stats(response + 1)["num_inconclusive"].asInt() shouldBe 1
                live(5000, 1L, 2L, 3L)
                detector.onKeyframeRequested(3L, 5000)
                detector.onKeyframeObserved(3L, 5100)
                sending(5500, 2L)
                should("be evidence for the per-encoding mode from that one") {
                    stats(5000 + response + 1)["num_per_encoding"].asInt() shouldBe 1
                }
            }
        }

        context("a request answered late, with the other encodings' keyframes following after the response window") {
            live(0, 1L, 2L, 3L)
            detector.onKeyframeRequested(3L, 0)
            detector.onKeyframeObserved(3L, 1400)
            detector.onKeyframeObserved(1L, 1600)
            detector.onKeyframeObserved(2L, 1650)
            should("stay open for a cluster window after the answer, and be evidence for the clustered mode") {
                detector.debugState()["open_observations"].asInt() shouldBe 1
                val s = stats(1400 + KeyframeModeDetector.CLUSTER_WINDOW_MS + 1)
                s["num_clustered"].asInt() shouldBe 1
                s["num_per_encoding"].asInt() shouldBe 0
            }
        }

        context("a request answered on the other encodings only long after") {
            observe(0, 3L, mapOf(3L to 100L, 1L to 1200L, 2L to 1250L))
            should("be discarded, since such keyframes have no cause but the sender's own in either mode") {
                val s = stats(response + 1)
                s["num_discarded"].asInt() shouldBe 1
                s["num_per_encoding"].asInt() shouldBe 0
                s["num_clustered"].asInt() shouldBe 0
            }
        }

        context("a request naming an encoding's secondary SSRC") {
            source.rtpEncodings[2].addSecondarySsrc(33L, SsrcAssociationType.RTX)
            detector.setMediaSources(arrayOf(source))
            live(0, 1L, 2L, 3L)
            should("be limited and fanned out by the encoding's primary SSRC") {
                detector.primarySsrc(33L) shouldBe 3L
                detector.primarySsrc(99L) shouldBe 99L
                detector.sourceSsrc(33L) shouldBe 1L
                detector.sourceSsrc(99L) shouldBe 99L
                detector.limiterKeys(33L) shouldBe listOf(1L, 2L, 3L)
                detector.requestSsrcsForSource(33L, 0) shouldBe listOf(3L)
                withNewConfig("jmt.keyframe.sender-mode=per-encoding") {
                    newDetector().limiterKeys(33L) shouldBe listOf(3L)
                }
            }
        }

        context("with the mode fixed by configuration") {
            withNewConfig("jmt.keyframe.sender-mode=per-encoding") {
                detector = newDetector()
                should("report the configured mode without observations") {
                    modeAt(3L, 0) shouldBe SenderKeyframeMode.PER_ENCODING
                    detector.limiterKeys(3L) shouldContainExactly listOf(3L)
                }
            }
            withNewConfig("jmt.keyframe.sender-mode=clustered") {
                detector = newDetector()
                observe(0, 3L, mapOf(3L to 100L))
                observe(5000, 3L, mapOf(3L to 5100L))
                should("ignore observations") {
                    modeAt(3L, 10000) shouldBe SenderKeyframeMode.CLUSTERED
                    detector.limiterKeys(3L) shouldContainExactly listOf(1L, 2L, 3L)
                }
            }
            withNewConfig("jmt.keyframe.sender-mode=per_ssrc") {
                should("reject an unrecognized value") {
                    shouldThrowAny { KeyframeModeConfig.senderMode }
                }
            }
        }
    }
}
