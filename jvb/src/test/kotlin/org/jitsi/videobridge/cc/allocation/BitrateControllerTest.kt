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

import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import java.time.Instant
import java.util.function.Supplier

class BitrateControllerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val logger = createLogger()
    private val clock = FakeClock()
    private val bc = BitrateControllerWrapper("A", "B", "C", "D", clock = clock)

    init {
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

        context("Signaling with the legacy API") {
            bc.bc.setSelectedEndpoints(listOf("A", "B", "C", "D"))
            // Multiple selected endpoints signals tile-view, and in tile-view we actually run with no selected
            // endpoints.
            bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
            bc.bc.allocationSettings.videoConstraints["A"] shouldBe VideoConstraints(1080)
            bc.bc.setMaxFrameHeight(180)
            bc.bc.allocationSettings.videoConstraints["A"] shouldBe VideoConstraints(180)
        }

        context("Allocation") {
            context("Stage view") {
                context("When LastN is not set") {
                    context("and the dominant speaker is on stage") {
                        listOf(true, false).forEach { legacy ->
                            context("With ${if (legacy) "legacy" else "new"} signaling") {
                                bc.setEndpointOrdering("A", "B", "C", "D")
                                bc.setStageView("A", legacy = legacy)

                                bc.bc.allocationSettings.lastN shouldBe -1
                                bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
                                bc.bc.allocationSettings.onStageEndpoints shouldBe listOf("A")

                                runBweLoop()

                                verifyStageView()
                            }
                        }
                    }
                    context("and a non-dominant speaker is on stage") {
                        listOf(true, false).forEach { legacy ->
                            context("With ${if (legacy) "legacy" else "new"} signaling") {
                                bc.setEndpointOrdering("B", "A", "C", "D")
                                bc.setStageView("A", legacy = legacy)

                                bc.bc.allocationSettings.lastN shouldBe -1
                                bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
                                bc.bc.allocationSettings.onStageEndpoints shouldBe listOf("A")
                                runBweLoop()

                                verifyStageView()
                            }
                        }
                    }
                }
                context("When LastN=0") {
                    listOf(true, false).forEach { legacy ->
                        context("With ${if (legacy) "legacy" else "new"} signaling") {
                            // LastN=0 is used when the client goes in "audio-only" mode.
                            bc.setEndpointOrdering("A", "B", "C", "D")
                            bc.setStageView("A", lastN = 0, legacy = legacy)

                            bc.bc.allocationSettings.lastN shouldBe 0
                            bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
                            bc.bc.allocationSettings.onStageEndpoints shouldBe listOf("A")

                            runBweLoop()

                            verifyLastN0()
                        }
                    }
                }
                context("When LastN=1") {
                    listOf(true, false).forEach { legacy ->
                        context("With ${if (legacy) "legacy" else "new"} signaling") {
                            // LastN=1 is used when the client goes in "audio-only" mode, but someone starts a screenshare.
                            context("and the dominant speaker is on-stage") {
                                bc.setEndpointOrdering("A", "B", "C", "D")
                                bc.setStageView("A", lastN = 1, legacy = legacy)

                                bc.bc.allocationSettings.lastN shouldBe 1
                                bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
                                bc.bc.allocationSettings.onStageEndpoints shouldBe listOf("A")

                                runBweLoop()

                                verifyStageViewLastN1()
                            }
                        }
                    }
                    context("and a non-dominant speaker is on stage") {
                        listOf(true, false).forEach { legacy ->
                            context("With ${if (legacy) "legacy" else "new"} signaling") {
                                bc.setEndpointOrdering("B", "A", "C", "D")
                                bc.setStageView("A", lastN = 1, legacy = legacy)

                                bc.bc.allocationSettings.lastN shouldBe 1
                                bc.bc.allocationSettings.selectedEndpoints shouldBe emptyList()
                                bc.bc.allocationSettings.onStageEndpoints shouldBe listOf("A")

                                runBweLoop()

                                verifyStageViewLastN1()
                            }
                        }
                    }
                }
            }
            context("Tile view") {
                listOf(true, false).forEach { legacy ->
                    context("With ${if (legacy) "legacy" else "new"} signaling") {
                        bc.setEndpointOrdering("A", "B", "C", "D")
                        bc.setTileView("A", "B", "C", "D", legacy = legacy)

                        bc.bc.allocationSettings.lastN shouldBe -1
                        // The legacy API (currently used by jitsi-meet) uses "selected count > 0" to infer TileView,
                        // and in tile view we do not use selected endpoints.
                        bc.bc.allocationSettings.selectedEndpoints shouldBe
                            if (legacy) emptyList() else listOf("A", "B", "C", "D")

                        context("When LastN is not set") {
                            runBweLoop()

                            verifyTileView()
                        }
                        context("When LastN=0") {
                            bc.setTileView("A", "B", "C", "D", lastN = 0, legacy = legacy)
                            runBweLoop()

                            verifyLastN0()
                        }
                        context("When LastN=1") {
                            bc.setTileView("A", "B", "C", "D", lastN = 1, legacy = legacy)
                            runBweLoop()

                            verifyTileViewLastN1()
                        }
                    }
                }
            }
            context("Tile view 360p") {
                listOf(true, false).forEach { legacy ->
                    context("With ${if (legacy) "legacy" else "new"} signaling") {
                        bc.setEndpointOrdering("A", "B", "C", "D")
                        bc.setTileView("A", "B", "C", "D", maxFrameHeight = 360, legacy = legacy)

                        bc.bc.allocationSettings.lastN shouldBe -1
                        // The legacy API (currently used by jitsi-meet) uses "selected count > 0" to infer TileView,
                        // and in tile view we do not use selected endpoints.
                        bc.bc.allocationSettings.selectedEndpoints shouldBe
                            if (legacy) emptyList() else listOf("A", "B", "C", "D")

                        context("When LastN is not set") {
                            runBweLoop()

                            verifyTileView360p()
                        }
                        context("When LastN=0") {
                            bc.setTileView("A", "B", "C", "D", lastN = 0, maxFrameHeight = 360, legacy = legacy)
                            runBweLoop()

                            verifyLastN0()
                        }
                        context("When LastN=1") {
                            bc.setTileView("A", "B", "C", "D", lastN = 1, maxFrameHeight = 360, legacy = legacy)
                            runBweLoop()

                            verifyTileViewLastN1(360)
                        }
                    }
                }
            }
            context("Selected endpoints should override the dominant speaker (with new signaling)") {
                // A is dominant speaker, A and B are selected. With LastN=2 we should always forward the selected
                // endpoints regardless of who is speaking.
                // The exact flow of this scenario was taken from a (non-jitsi-meet) client.
                bc.setEndpointOrdering("A", "B", "C", "D")
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        selectedEndpoints = listOf("A", "B"),
                        constraints = mapOf("A" to VideoConstraints(720), "B" to VideoConstraints(720))
                    )
                )

                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A" to VideoConstraints(720),
                    "B" to VideoConstraints(720),
                    "C" to VideoConstraints(180),
                    "D" to VideoConstraints(180)
                )

                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = 2))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A" to VideoConstraints(720),
                    "B" to VideoConstraints(720),
                    "C" to VideoConstraints(0),
                    "D" to VideoConstraints(0)
                )

                bc.bc.allocationSettings.lastN shouldBe 2
                bc.bc.allocationSettings.selectedEndpoints shouldBe listOf("A", "B")

                clock.elapse(20.secs)
                bc.bwe = 10.mbps
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                // B becomes dominant speaker.
                bc.setEndpointOrdering("B", "A", "C", "D")
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        constraints = mapOf("A" to VideoConstraints(360), "B" to VideoConstraints(360))
                    )
                )
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A" to VideoConstraints(360),
                    "B" to VideoConstraints(360),
                    "C" to VideoConstraints(0),
                    "D" to VideoConstraints(0)
                )

                clock.elapse(2.secs)
                // This should change nothing, the selection didn't change.
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(selectedEndpoints = listOf("A", "B"))
                )
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B"))

                clock.elapse(2.secs)
                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = -1))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A" to VideoConstraints(360),
                    "B" to VideoConstraints(360),
                    "C" to VideoConstraints(180),
                    "D" to VideoConstraints(180)
                )
                bc.forwardedEndpointsHistory.last().event.shouldBe(setOf("A", "B", "C", "D"))

                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = 2))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A" to VideoConstraints(360),
                    "B" to VideoConstraints(360),
                    "C" to VideoConstraints(0),
                    "D" to VideoConstraints(0)
                )
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

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A" to VideoConstraints(720),
            "B" to VideoConstraints(180),
            "C" to VideoConstraints(180),
            "D" to VideoConstraints(180)
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                600.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                650.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                700.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                750.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                800.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                850.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                900.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                960.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            ),
            Event(
                2150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                2350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                2400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                2460.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            )
        )
    }

    private fun verifyLastN0() {
        // No video forwarded even with high BWE.
        bc.forwardedEndpointsHistory.size shouldBe 0

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A" to VideoConstraints(0),
            "B" to VideoConstraints(0),
            "C" to VideoConstraints(0),
            "D" to VideoConstraints(0)
        )

        // TODO: The history contains 3 identical elements, which is probably a bug.
        bc.allocationHistory.last().event.shouldMatch(
            BandwidthAllocation(
                setOf(
                    SingleAllocation("A", targetLayer = noVideo),
                    SingleAllocation("B", targetLayer = noVideo),
                    SingleAllocation("C", targetLayer = noVideo),
                    SingleAllocation("D", targetLayer = noVideo)
                )
            )
        )
    }

    private fun verifyStageViewLastN1() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }

        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A")
        )

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A" to VideoConstraints(720),
            "B" to VideoConstraints(0),
            "C" to VideoConstraints(0),
            "D" to VideoConstraints(0)
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                0.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2010.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = hd30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
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

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = noVideo),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                450.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                610.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            )
        )
    }

    private fun verifyTileView360p() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A"),
            setOf("A", "B"),
            setOf("A", "B", "C"),
            setOf("A", "B", "C", "D")
        )

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = noVideo),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld7_5),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld7_5),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                450.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld15),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld15),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld15)
                    )
                )
            ),
            Event(
                610.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            ),
            Event(
                960.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = ld30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            ),
            Event(
                1310.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = sd30),
                        SingleAllocation("C", targetLayer = ld30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            ),
            Event(
                1660.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = sd30),
                        SingleAllocation("C", targetLayer = sd30),
                        SingleAllocation("D", targetLayer = ld30)
                    )
                )
            ),
            Event(
                2010.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = sd30),
                        SingleAllocation("B", targetLayer = sd30),
                        SingleAllocation("C", targetLayer = sd30),
                        SingleAllocation("D", targetLayer = sd30)
                    )
                )
            )
        )
    }

    private fun verifyTileViewLastN1(maxFrameHeight: Int = 180) {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedEndpointsHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
            setOf("A")
        )

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A" to VideoConstraints(maxFrameHeight),
            "B" to VideoConstraints(0),
            "C" to VideoConstraints(0),
            "D" to VideoConstraints(0)
        )

        val expectedAllocationHistory = mutableListOf(
            // TODO: do we want to oversend in tile view?
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = noVideo),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld7_5),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld15),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            ),
            Event(
                160.kbps, // TODO: why 160 instead of 150? weird.
                BandwidthAllocation(
                    setOf(
                        SingleAllocation("A", targetLayer = ld30),
                        SingleAllocation("B", targetLayer = noVideo),
                        SingleAllocation("C", targetLayer = noVideo),
                        SingleAllocation("D", targetLayer = noVideo)
                    )
                )
            )
        )
        if (maxFrameHeight > 180) {
            expectedAllocationHistory.addAll(
                listOf(
                    Event(
                        510.kbps,
                        BandwidthAllocation(
                            setOf(
                                SingleAllocation("A", targetLayer = sd30),
                                SingleAllocation("B", targetLayer = noVideo),
                                SingleAllocation("C", targetLayer = noVideo),
                                SingleAllocation("D", targetLayer = noVideo)
                            )
                        )
                    )
                )
            )
        }
        bc.allocationHistory.shouldMatchInOrder(*expectedAllocationHistory.toTypedArray())
    }
}

fun List<Event<BandwidthAllocation>>.shouldMatchInOrder(vararg events: Event<BandwidthAllocation>) {
    size shouldBe events.size
    events.forEachIndexed { i, it ->
        this[i].bwe shouldBe it.bwe
        withClue("bwe=${it.bwe}") {
            this[i].event.shouldMatch(it.event)
        }
        // Ignore this.time
    }
}

fun BandwidthAllocation.shouldMatch(other: BandwidthAllocation) {
    allocations.size shouldBe other.allocations.size
    allocations.forEach { thisSingleAllocation ->
        withClue("Allocation for ${thisSingleAllocation.endpointId}") {
            val otherSingleAllocation = other.allocations.find { it.endpointId == thisSingleAllocation.endpointId }
            otherSingleAllocation.shouldNotBeNull()
            thisSingleAllocation.targetLayer?.height shouldBe otherSingleAllocation.targetLayer?.height
            thisSingleAllocation.targetLayer?.frameRate shouldBe otherSingleAllocation.targetLayer?.frameRate
        }
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
    val effectiveConstraintsHistory: History<Map<String, VideoConstraints>> = mutableListOf()
    val forwardedEndpointsHistory: History<Set<String>> = mutableListOf()
    val allocationHistory: History<BandwidthAllocation> = mutableListOf()

    val bc = BitrateController(
        object : BitrateController.EventHandler {
            override fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>) {
                Event(bwe, forwardedEndpoints, clock.instant()).apply {
                    logger.info("Forwarded endpoints changed: $this")
                    forwardedEndpointsHistory.add(this)
                }
            }

            override fun effectiveVideoConstraintsChanged(
                oldEffectiveConstraints: Map<String, VideoConstraints>,
                newEffectiveConstraints: Map<String, VideoConstraints>
            ) {
                Event(bwe, newEffectiveConstraints, clock.instant()).apply {
                    logger.info("Effective constraints changed: $this")
                    effectiveConstraintsHistory.add(this)
                }
            }

            override fun keyframeNeeded(endpointId: String?, ssrc: Long) {}

            override fun allocationChanged(allocation: BandwidthAllocation) {
                Event(
                    bwe,
                    allocation,
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

    fun setStageView(onStageEndpoint: String, maxFrameHeight: Int = 720, legacy: Boolean = true, lastN: Int? = null) {
        if (legacy) {
            lastN?.let { bc.lastN = lastN }
            bc.setMaxFrameHeight(maxFrameHeight)
            bc.setSelectedEndpoints(listOf(onStageEndpoint))
        } else {
            bc.setBandwidthAllocationSettings(
                ReceiverVideoConstraintsMessage(
                    lastN = lastN,
                    onStageEndpoints = listOf(onStageEndpoint),
                    constraints = mapOf(onStageEndpoint to VideoConstraints(720))
                )
            )
        }
    }

    fun setSelectedEndpoints(vararg selectedEndpoints: String, maxFrameHeight: Int? = null) {
        maxFrameHeight?.let { bc.setMaxFrameHeight(it) }
        bc.setSelectedEndpoints(listOf(*selectedEndpoints))
    }

    fun setTileView(
        vararg selectedEndpoints: String,
        maxFrameHeight: Int = 180,
        legacy: Boolean = true,
        lastN: Int? = null
    ) {
        if (legacy) {
            lastN?.let { bc.lastN = lastN }
            setSelectedEndpoints(*selectedEndpoints, maxFrameHeight = maxFrameHeight)
        } else {
            bc.setBandwidthAllocationSettings(
                ReceiverVideoConstraintsMessage(
                    lastN = lastN,
                    selectedEndpoints = listOf(*selectedEndpoints),
                    constraints = selectedEndpoints.map { it to VideoConstraints(maxFrameHeight) }.toMap()
                )
            )
        }
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
    override fun equals(other: Any?): Boolean {
        if (other !is Event<*>) return false
        // Ignore this.time
        return bwe == other.bwe && event == other.event
    }
}

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

val noVideo: RtpLayerDesc? = null

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
