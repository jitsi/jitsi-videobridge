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

package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.mbps
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Instant
import java.util.function.Supplier

class BitrateControllerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val logger = createLogger()
    private val clock = FakeClock()
    private val bc = BitrateControllerWrapper("A", "B", "C", "D", clock = clock)

    init {
        context("Effective constraints") {
            /*
            TODO: bring back effective constraints tests.
            val conferenceEndpoints = List(5) { i -> Endpoint("endpoint-${i + 1}") }

            context("When nothing is specified (expect 180p)") {
                val lastN = -1
                val videoConstraints = mapOf("endpoint-1" to VideoConstraints(720))

                EndpointMultiRank.makeEndpointMultiRankList(
                    conferenceEndpoints,
                    videoConstraints,
                    lastN
                ).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints(720),
                        "endpoint-2" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-3" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-4" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-5" to VideoConstraints.thumbnailVideoConstraints
                    )
                )
            }

            context("With LastN") {
                val lastN = 3
                val videoConstraints = mapOf<String, VideoConstraints>()

                EndpointMultiRank.makeEndpointMultiRankList(
                    conferenceEndpoints,
                    videoConstraints,
                    lastN
                ).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-2" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-3" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-4" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-5" to VideoConstraints.disabledVideoConstraints
                    )
                )
            }

            context("With explicitly selected ep outside LastN") {
                // This replicates what the client's low-bandwidth mode does when there is a screenshare -
                // it explicitly selects only the share, ignoring the last-N list.
                val lastN = 1
                val videoConstraints = mapOf("endpoint-2" to VideoConstraints(1080))
                EndpointMultiRank.makeEndpointMultiRankList(
                    conferenceEndpoints,
                    videoConstraints,
                    lastN
                ).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-2" to VideoConstraints(1080),
                        "endpoint-3" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-4" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-5" to VideoConstraints.disabledVideoConstraints
                    )
                )
            }
             */
        }

        context("Prioritization") {
            val endpoints = createEndpoints("A", "B", "C", "D", "E", "F")
            context("Without selection") {
                val ordered = prioritize(
                    listOf("F", "E", "D", "C", "B", "A"),
                    emptyList(),
                    endpoints
                )
                ordered.map { it.id } shouldBe listOf("F", "E", "D", "C", "B", "A")
            }
            context("With one selected") {
                val ordered = prioritize(
                    listOf("F", "E", "D", "C", "B", "A"),
                    listOf("B"),
                    endpoints
                )
                ordered.map { it.id } shouldBe listOf("B", "F", "E", "D", "C", "A")
            }
            context("With multiple selected") {
                val ordered = prioritize(
                    listOf("F", "E", "D", "C", "B", "A"),
                    listOf("B", "A", "E"),
                    endpoints
                )
                ordered.map { it.id } shouldBe listOf("B", "A", "E", "F", "D", "C")
            }
        }

        context("Allocation") {

            context("Stage view") {
                context("When LastN is not set") {
                    context("and the dominant speaker is on stage") {
                        bc.setEndpointOrdering("A", "B", "C", "D")
                        bc.setStageView("A")
                        runBweLoop()

                        verifyStageView()
                    }
                    context("and a non-dominant speaker is on stage") {
                        bc.setEndpointOrdering("B", "A", "C", "D")
                        bc.setStageView("A")
                        runBweLoop()

                        verifyStageView()
                    }
                }
                context("When LastN=0") {
                    // LastN=0 is used when the client goes in "audio-only" mode.
                    bc.setEndpointOrdering("A", "B", "C", "D")
                    bc.setStageView("A")
                    bc.setLastN(0)
                    runBweLoop()

                    verifyLastN0()
                }
                context("When LastN=1") {
                    // LastN=1 is used when the client goes in "audio-only" mode, but someone starts a screenshare.
                    context("and the dominant speaker is on-stage") {
                        bc.setEndpointOrdering("A", "B", "C", "D")
                        bc.setStageView("A")
                        bc.setLastN(1)
                        runBweLoop()

                        verifyStageViewLastN1()
                    }
                    context("and a non-dominant speaker is on stage") {
                        bc.setEndpointOrdering("B", "A", "C", "D")
                        bc.setStageView("A")
                        bc.setLastN(1)
                        runBweLoop()

                        verifyStageViewLastN1()
                    }
                }
            }
            context("Tile view") {
                bc.setEndpointOrdering("A", "B", "C", "D")
                bc.setTileView("A", "B", "C", "D")

                context("When LastN is not set") {
                    runBweLoop()

                    verifyTileView()
                }
                context("When LastN=0") {
                    bc.setLastN(0)
                    runBweLoop()

                    verifyLastN0()
                }
                context("When LastN=1") {
                    bc.setLastN(1)
                    runBweLoop()

                    verifyTileViewLastN1()
                }
            }
            context("Selected endpoints should override the dominant speaker") {
                // A is dominant speaker, A and B are selected. With LastN=2 we should always forward the selected
                // endpoints regardless of who is speaking.
                // The exact flow of this scenario was taken from a (non-jitsi-meet) client.
                bc.setEndpointOrdering("A", "B", "C", "D")
                bc.setSelectedEndpoints("A", "B", maxFrameHeight = 720)
                bc.setLastN(2)

                clock.elapse(20.secs)
                bc.bwe = 10.mbps
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                // B becomes dominant speaker.
                bc.setEndpointOrdering("B", "A", "C", "D")
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                bc.setMaxFrameHeight(360)

                clock.elapse(2.secs)
                // This should change nothing, the selection didn't change.
                bc.setSelectedEndpoints("A", "B")
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                bc.setLastN(-1)
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B", "C", "D"))

                clock.elapse(2.secs)
                bc.setMaxFrameHeight(360)
                clock.elapse(2.secs)
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B", "C", "D"))

                bc.setLastN(2)
                clock.elapse(2.secs)
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                // D is now dominant speaker, but it should not override the selected endpoints.
                bc.setEndpointOrdering("D", "B", "A", "C")
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                bc.bwe = 10.mbps
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                bc.bwe = 0.mbps
                clock.elapse(2.secs)
                bc.bwe = 10.mbps
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                // C is now dominant speaker, but it should not override the selected endpoints.
                bc.setEndpointOrdering("C", "D", "A", "B")
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))
            }
        }
    }

    private fun runBweLoop() {
        for (bwe in 0..5_000_000 step 10_000) {
            bc.bwe = bwe.bps
            clock.elapse(100.ms)
        }
        logger.info("Forwarded endpoints history: ${bc.forwardedEndpointsHistory}")
        logger.info("Effective constraints history: ${bc.effectiveConstraintsHistory}")
        logger.info("Allocation history: ${bc.allocationHistory}")
    }

    private fun verifyStageView() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A"),
            setOf("A", "B"),
            setOf("A", "B", "C"),
            setOf("A", "B", "C", "D")
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                listOf(
                    AllocationInfo("A", ld7_5, oversending = true),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                50.kbps,
                listOf(
                    AllocationInfo("A", ld7_5, oversending = false),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                100.kbps,
                listOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                150.kbps,
                listOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                500.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                550.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                600.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                650.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                700.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                750.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                800.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                850.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                900.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                960.kbps,
                listOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld30)
                )
            ),
            Event(
                2150.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                2200.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                2250.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                2300.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                2350.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                2400.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                2460.kbps,
                listOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld30)
                )
            )
        )
    }

    private fun verifyLastN0() {
        // No video forwarded even with high BWE.
        bc.forwardedEndpointsHistory.size shouldBe 0

        // TODO: The history contains 3 identical elements, which is probably a bug.
        bc.allocationHistory.last().event shouldBe setOf(
            AllocationInfo("A", noVideo),
            AllocationInfo("B", noVideo),
            AllocationInfo("C", noVideo),
            AllocationInfo("D", noVideo)
        )
    }

    private fun verifyStageViewLastN1() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }

        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A")
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                setOf(
                    AllocationInfo("A", ld7_5, oversending = true),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                50.kbps,
                setOf(
                    AllocationInfo("A", ld7_5, oversending = false),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                100.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                150.kbps,
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                500.kbps,
                setOf(
                    AllocationInfo("A", sd30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                2010.kbps,
                setOf(
                    AllocationInfo("A", hd30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            )
        )
    }

    private fun verifyTileView() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A"),
            setOf("A", "B"),
            setOf("A", "B", "C"),
            setOf("A", "B", "C", "D")
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                setOf(
                    // TODO: do we want to oversend in tile view?
                    AllocationInfo("A", ld7_5, oversending = true),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                50.kbps,
                setOf(
                    AllocationInfo("A", ld7_5, oversending = false),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                100.kbps,
                setOf(
                    AllocationInfo("A", ld7_5),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                150.kbps,
                setOf(
                    AllocationInfo("A", ld7_5),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                200.kbps,
                setOf(
                    AllocationInfo("A", ld7_5),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                250.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", ld7_5),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                300.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld7_5),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                350.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld7_5)
                )
            ),
            Event(
                400.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                450.kbps,
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", ld15),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                500.kbps,
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld15),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                550.kbps,
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld15)
                )
            ),
            Event(
                610.kbps,
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", ld30),
                    AllocationInfo("C", ld30),
                    AllocationInfo("D", ld30)
                )
            )
        )
    }

    private fun verifyTileViewLastN1() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A")
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                setOf(
                    // TODO: do we want to oversend in tile view?
                    AllocationInfo("A", ld7_5, oversending = true),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                50.kbps,
                setOf(
                    AllocationInfo("A", ld7_5, oversending = false),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                100.kbps,
                setOf(
                    AllocationInfo("A", ld15),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            ),
            Event(
                160.kbps, // TODO: why 160 instead of 150? weird.
                setOf(
                    AllocationInfo("A", ld30),
                    AllocationInfo("B", noVideo),
                    AllocationInfo("C", noVideo),
                    AllocationInfo("D", noVideo)
                )
            )
        )
    }
}

fun List<Event<Collection<AllocationInfo>>>.shouldMatchInOrder(vararg events: Event<Collection<AllocationInfo>>) {
    size shouldBe events.size
    events.forEachIndexed { i, it ->
        this[i].bwe shouldBe it.bwe
        this[i].event.toSet() shouldBe it.event.toSet()
        // Ignore this.time
    }
}

private class BitrateControllerWrapper(vararg endpointIds: String, val clock: FakeClock = FakeClock()) {
    val endpoints: List<Endpoint> = createEndpoints(*endpointIds)
    val logger = createLogger()

    var bwe = (-1).bps
        set(value) {
            logger.debug("Setting bwe=$value")
            field = value
            bc.bandwidthChanged(value.bps.toLong())
        }

    // Save the output.
    val effectiveConstraintsHistory: History<Map<String, VideoConstraints2>> = mutableListOf()
    val forwardedEndpointsHistory: History<Set<String>> = mutableListOf()
    val allocationHistory: History<Collection<AllocationInfo>> = mutableListOf()

    val bc = BitrateController(
        object : BitrateController.EventHandler {
            override fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>) {
                Event(bwe, forwardedEndpoints, clock.instant()).apply {
                    logger.info("Forwarded endpoints changed: $this")
                    forwardedEndpointsHistory.add(this)
                }
            }

            override fun effectiveVideoConstraintsChanged(
                oldEffectiveConstraints: Map<String, VideoConstraints2>,
                newEffectiveConstraints: Map<String, VideoConstraints2>
            ) {
                Event(bwe, newEffectiveConstraints, clock.instant()).apply {
                    logger.info("Effective constraints changed: $this")
                    effectiveConstraintsHistory.add(this)
                }
            }

            override fun keyframeNeeded(endpointId: String?, ssrc: Long) {}

            override fun allocationChanged(allocation: Allocation) {
                Event<Collection<AllocationInfo>>(
                    bwe,
                    allocation.allocations.map { it.toEndpointAllocationInfo() }.toSet(),
                    clock.instant()
                ).apply {
                    logger.info("Allocation changed: $this")
                    allocationHistory.add(this)
                }
            }
        },
        Supplier { endpoints },
        DiagnosticContext(),
        logger,
        clock
    )

    fun setEndpointOrdering(vararg endpoints: String) {
        logger.info("Set endpoints ${endpoints.joinToString(",")}")
        bc.endpointOrderingChanged(mutableListOf(*endpoints))
    }

    fun setStageView(onStageEndpoint: String, maxFrameHeight: Int = 720) {
        bc.setMaxFrameHeight(maxFrameHeight)
        bc.setSelectedEndpoints(listOf(onStageEndpoint))
    }

    fun setSelectedEndpoints(vararg selectedEndpoints: String, maxFrameHeight: Int? = null) {
        maxFrameHeight?.let { bc.setMaxFrameHeight(it) }
        bc.setSelectedEndpoints(listOf(*selectedEndpoints))
    }

    fun setTileView(vararg selectedEndpoints: String) {
        setSelectedEndpoints(*selectedEndpoints, maxFrameHeight = 180)
    }

    fun setMaxFrameHeight(maxFrameHeight: Int) {
        bc.setMaxFrameHeight(maxFrameHeight)
    }

    fun setLastN(n: Int) {
        logger.info("Set LastN $n")
        bc.lastN = n
    }

    init {
        // The BC only starts working 10 seconds after it first received media, so fake that.
        bc.transformRtp(PacketInfo(VideoRtpPacket(ByteArray(100), 0, 100)))
        clock.elapse(15.secs)

        // Adaptivity is disabled when RTX support is not signalled.
        bc.addPayloadType(RtxPayloadType(123, mapOf("apt" to "124")))
    }
}

typealias History<T> = MutableList<Event<T>>
data class Event<T>(
    val bwe: Bandwidth,
    val event: T,
    val time: Instant = Instant.MIN
) {
    override fun toString(): String = "\n[time=${time.toEpochMilli()} bwe=$bwe] $event"
}

/**
 * Describe the layer that is currently forwarded to an endpoint in a human-readable way.
 */
data class AllocationInfo(
    val id: String,
    val height: Int,
    val fps: Double,
    val bitrate: Bandwidth,
    val oversending: Boolean = false
) {
    constructor(id: String, layer: RtpLayerDesc, oversending: Boolean = false) :
        this(id, layer.height, layer.frameRate, layer.getBitrate(0), oversending)

    override fun toString(): String =
        "\n\t[id=$id, height=$height, fps=$fps, bitrate=$bitrate, oversending=$oversending]"

    override fun equals(other: Any?): Boolean {
        if (other !is AllocationInfo) return false

        return id == other.id && height == other.height && fps == other.fps && bitrate == other.bitrate &&
            oversending == other.oversending
    }
}

fun SingleAllocation.toEndpointAllocationInfo() =
    AllocationInfo(
        endpointId,
        targetLayer?.height ?: 0,
        targetLayer?.frameRate ?: 0.0,
        targetLayer?.getBitrate(0) ?: 0.bps, // getBitrate(0) is fine with our Mock RtpLayerDesc
        oversending
    )

class Endpoint(
    override val id: String,
    mediaSource: MediaSourceDesc? = null
) : MediaSourceContainer {
    override val mediaSources = mediaSource?.let { arrayOf(mediaSource) } ?: emptyArray()
}

fun createEndpoints(vararg ids: String): List<Endpoint> {
    return List(ids.size) { i ->
        Endpoint(
            ids[i],
            createSource(
                3 * i + 1,
                3 * i + 2,
                3 * i + 3
            )
        )
    }
}

fun createSource(ssrc1: Int, ssrc2: Int, ssrc3: Int): MediaSourceDesc = MediaSourceDesc(
    arrayOf(
        RtpEncodingDesc(ssrc1.toLong(), arrayOf(ld7_5, ld15, ld30)),
        RtpEncodingDesc(ssrc2.toLong(), arrayOf(sd7_5, sd15, sd30)),
        RtpEncodingDesc(ssrc3.toLong(), arrayOf(hd7_5, hd15, hd30))
    )
)

val bitrateLd = 150.kbps
val bitrateSd = 500.kbps
val bitrateHd = 2000.kbps

val ld7_5
    get() = createLayer(tid = 0, eid = 0, height = 180, frameRate = 7.5, bitrate = bitrateLd * 0.33)
val ld15
    get() = createLayer(tid = 1, eid = 0, height = 180, frameRate = 15.0, bitrate = bitrateLd * 0.66)
val ld30
    get() = createLayer(tid = 2, eid = 0, height = 180, frameRate = 30.0, bitrate = bitrateLd)

val sd7_5
    get() = createLayer(tid = 0, eid = 1, height = 360, frameRate = 7.5, bitrate = bitrateSd * 0.33)
val sd15
    get() = createLayer(tid = 1, eid = 1, height = 360, frameRate = 15.0, bitrate = bitrateSd * 0.66)
val sd30
    get() = createLayer(tid = 2, eid = 1, height = 360, frameRate = 30.0, bitrate = bitrateSd)

val hd7_5
    get() = createLayer(tid = 0, eid = 2, height = 720, frameRate = 7.5, bitrate = bitrateHd * 0.33)
val hd15
    get() = createLayer(tid = 1, eid = 2, height = 720, frameRate = 15.0, bitrate = bitrateHd * 0.66)
val hd30
    get() = createLayer(tid = 2, eid = 2, height = 720, frameRate = 30.0, bitrate = bitrateHd)

val noVideo
    get() = createLayer(tid = -1, eid = -1, height = 0, frameRate = 0.0, bitrate = 0.bps)

fun createLayer(
    tid: Int,
    eid: Int,
    height: Int,
    frameRate: Double,
    /**
     * Note: this mock impl does not model the dependency layers, so the cumulative bitrate should be provided.
     */
    bitrate: Bandwidth
): RtpLayerDesc {
    val sid = -1

    // Use a real RtpLayerDesc, because mocking absolutely kills the performance.
    return object : RtpLayerDesc(eid, tid, sid, height, frameRate, dependencyLayers = null) {
        override fun getBitrate(nowMs: Long): Bandwidth = bitrate
    }
}
