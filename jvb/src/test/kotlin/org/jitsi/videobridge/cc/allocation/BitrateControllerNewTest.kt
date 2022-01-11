/*
 * Copyright @ 2018 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import org.jitsi.config.setNewConfig
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.VideoType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.mbps
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.videobridge.configWithMultiStreamEnabled
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import java.util.function.Supplier

class BitrateControllerNewTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val logger = createLogger()
    private val clock = FakeClock()
    private val bc = BitrateControllerWrapper2(createEndpoints2("A", "B", "C", "D"), clock = clock)
    private val A = bc.endpoints.find { it.id == "A" }!! as TestEndpoint2
    private val B = bc.endpoints.find { it.id == "B" }!! as TestEndpoint2
    private val C = bc.endpoints.find { it.id == "C" }!! as TestEndpoint2
    private val D = bc.endpoints.find { it.id == "D" }!! as TestEndpoint2

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)

        // Config caching must be disabled or otherwise the setNewConfig below will not be effective if the other tests
        // have instantiated MultiStreamConfig instance.
        MetaconfigSettings.cacheEnabled = false

        // We disable the threshold, causing [BandwidthAllocator] to make a new decision every time BWE changes. This is
        // because these tests are designed to test the decisions themselves and not necessarily when they are made.
        setNewConfig(
            "videobridge.cc.bwe-change-threshold=0" +
                "\n" + configWithMultiStreamEnabled, // Also enable multi stream support
            true
        )
    }

    override fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        setNewConfig("", true)
        MetaconfigSettings.cacheEnabled = true
    }

    init {
        context("Prioritization") {
            context("Without selection") {
                val sources = createSources("s6", "s5", "s4", "s3", "s2", "s1")
                val ordered = prioritize2(sources)
                ordered.map { it.sourceName } shouldBe listOf("s6", "s5", "s4", "s3", "s2", "s1")
            }
            context("With one selected") {
                val sources = createSources("s6", "s5", "s4", "s3", "s2", "s1")
                val ordered = prioritize2(sources, listOf("s2"))
                ordered.map { it.sourceName } shouldBe listOf("s2", "s6", "s5", "s4", "s3", "s1")
            }
            context("With multiple selected") {
                val sources = createSources("s6", "s5", "s4", "s3", "s2", "s1")
                val ordered = prioritize2(sources, listOf("s2", "s1", "s5"))
                ordered.map { it.sourceName } shouldBe listOf("s2", "s1", "s5", "s6", "s4", "s3")
            }
        }

        context("Allocation") {
            context("Stage view") {
                context("When LastN is not set") {
                    context("and the dominant speaker is on stage") {
                        listOf(true, false).forEach { screensharing ->
                            context("With ${if (screensharing) "screensharing" else "camera"}") {
                                if (screensharing) {
                                    A.mediaSources[0].videoType = VideoType.DESKTOP
                                }
                                bc.setEndpointOrdering(A, B, C, D)
                                bc.setStageView("A-v0")

                                bc.bc.allocationSettings.lastN shouldBe -1
                                bc.bc.allocationSettings.selectedSources shouldBe emptyList()
                                bc.bc.allocationSettings.onStageSources shouldBe listOf("A-v0")

                                runBweLoop()

                                verifyStageView(screensharing)
                            }
                        }
                    }
                    context("and a non-dominant speaker is on stage") {
                        bc.setEndpointOrdering(B, A, C, D)
                        bc.setStageView("A-v0")

                        bc.bc.allocationSettings.lastN shouldBe -1
                        bc.bc.allocationSettings.selectedSources shouldBe emptyList()
                        bc.bc.allocationSettings.onStageSources shouldBe listOf("A-v0")
                        runBweLoop()

                        verifyStageView()
                    }
                }
                context("When LastN=0") {
                    // LastN=0 is used when the client goes in "audio-only" mode.
                    bc.setEndpointOrdering(A, B, C, D)
                    bc.setStageView("A", lastN = 0)

                    bc.bc.allocationSettings.lastN shouldBe 0
                    bc.bc.allocationSettings.selectedSources shouldBe emptyList()
                    bc.bc.allocationSettings.onStageSources shouldBe listOf("A")

                    runBweLoop()

                    verifyLastN0()
                }
                context("When LastN=1") {
                    // LastN=1 is used when the client goes in "audio-only" mode, but someone starts a screenshare.
                    context("and the dominant speaker is on-stage") {
                        bc.setEndpointOrdering(A, B, C, D)
                        bc.setStageView("A-v0", lastN = 1)

                        bc.bc.allocationSettings.lastN shouldBe 1
                        bc.bc.allocationSettings.selectedSources shouldBe emptyList()
                        bc.bc.allocationSettings.onStageSources shouldBe listOf("A-v0")

                        runBweLoop()

                        verifyStageViewLastN1()
                    }
                    context("and a non-dominant speaker is on stage") {
                        bc.setEndpointOrdering(B, A, C, D)
                        bc.setStageView("A-v0", lastN = 1)

                        bc.bc.allocationSettings.lastN shouldBe 1
                        bc.bc.allocationSettings.selectedSources shouldBe emptyList()
                        bc.bc.allocationSettings.onStageSources shouldBe listOf("A-v0")

                        runBweLoop()

                        verifyStageViewLastN1()
                    }
                }
            }
            context("Tile view") {
                bc.setEndpointOrdering(A, B, C, D)
                bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0")

                bc.bc.allocationSettings.lastN shouldBe -1
                bc.bc.allocationSettings.selectedSources shouldBe
                    listOf("A-v0", "B-v0", "C-v0", "D-v0")

                context("When LastN is not set") {
                    runBweLoop()

                    verifyTileView()
                }
                context("When LastN=0") {
                    bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0", lastN = 0)
                    runBweLoop()

                    verifyLastN0()
                }
                context("When LastN=1") {
                    bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0", lastN = 1)
                    runBweLoop()

                    verifyTileViewLastN1()
                }
            }
            context("Tile view 360p") {
                bc.setEndpointOrdering(A, B, C, D)
                bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0", maxFrameHeight = 360)

                bc.bc.allocationSettings.lastN shouldBe -1
                // The legacy API (currently used by jitsi-meet) uses "selected count > 0" to infer TileView,
                // and in tile view we do not use selected endpoints.
                bc.bc.allocationSettings.selectedSources shouldBe
                    listOf("A-v0", "B-v0", "C-v0", "D-v0")

                context("When LastN is not set") {
                    runBweLoop()

                    verifyTileView360p()
                }
                context("When LastN=0") {
                    bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0", lastN = 0, maxFrameHeight = 360)
                    runBweLoop()

                    verifyLastN0()
                }
                context("When LastN=1") {
                    bc.setTileView("A-v0", "B-v0", "C-v0", "D-v0", lastN = 1, maxFrameHeight = 360)
                    runBweLoop()

                    verifyTileViewLastN1(360)
                }
            }
            context("Selected sources should override the dominant speaker (with new signaling)") {
                // A is dominant speaker, A and B are selected. With LastN=2 we should always forward the selected
                // sources regardless of who is speaking.
                // The exact flow of this scenario was taken from a (non-jitsi-meet) client.
                bc.setEndpointOrdering(A, B, C, D)
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        selectedSources = listOf("A-v0", "B-v0"),
                        constraints = mapOf("A-v0" to VideoConstraints(720), "B-v0" to VideoConstraints(720))
                    )
                )

                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A-v0" to VideoConstraints(720),
                    "B-v0" to VideoConstraints(720),
                    "C-v0" to VideoConstraints(180),
                    "D-v0" to VideoConstraints(180)
                )

                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = 2))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A-v0" to VideoConstraints(720),
                    "B-v0" to VideoConstraints(720),
                    "C-v0" to VideoConstraints(0),
                    "D-v0" to VideoConstraints(0)
                )

                bc.bc.allocationSettings.lastN shouldBe 2
                bc.bc.allocationSettings.selectedSources shouldBe listOf("A-v0", "B-v0")

                clock.elapse(20.secs)
                bc.bwe = 10.mbps
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                // B becomes dominant speaker.
                bc.setEndpointOrdering(B, A, C, D)
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        constraints = mapOf("A-v0" to VideoConstraints(360), "B-v0" to VideoConstraints(360))
                    )
                )
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A-v0" to VideoConstraints(360),
                    "B-v0" to VideoConstraints(360),
                    "C-v0" to VideoConstraints(0),
                    "D-v0" to VideoConstraints(0)
                )

                clock.elapse(2.secs)
                // This should change nothing, the selection didn't change.
                bc.bc.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(selectedSources = listOf("A-v0", "B-v0"))
                )
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = -1))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A-v0" to VideoConstraints(360),
                    "B-v0" to VideoConstraints(360),
                    "C-v0" to VideoConstraints(180),
                    "D-v0" to VideoConstraints(180)
                )
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0", "C-v0", "D-v0"))

                bc.bc.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage(lastN = 2))
                bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
                    "A-v0" to VideoConstraints(360),
                    "B-v0" to VideoConstraints(360),
                    "C-v0" to VideoConstraints(0),
                    "D-v0" to VideoConstraints(0)
                )
                clock.elapse(2.secs)
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                // D is now dominant speaker, but it should not override the selected endpoints.
                bc.setEndpointOrdering(D, B, A, C)
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                bc.bwe = 10.mbps
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                bc.bwe = 0.mbps
                clock.elapse(2.secs)
                bc.bwe = 10.mbps
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))

                clock.elapse(2.secs)
                // C is now dominant speaker, but it should not override the selected endpoints.
                bc.setEndpointOrdering(C, D, A, B)
                bc.forwardedSourcesHistory.last().event.shouldBe(setOf("A-v0", "B-v0"))
            }
        }
    }

    private fun runBweLoop() {
        for (bwe in 0..5_000_000 step 10_000) {
            bc.bwe = bwe.bps
            clock.elapse(100.ms)
        }
        logger.info("Forwarded sources history: ${bc.forwardedSourcesHistory}")
        logger.info("Effective constraints history: ${bc.effectiveConstraintsHistory}")
        logger.info("Allocation history: ${bc.allocationHistory}")
    }

    private fun verifyStageViewScreensharing() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0"),
            setOf("A-v0", "B-v0"),
            setOf("A-v0", "B-v0", "C-v0"),
            setOf("A-v0", "B-v0", "C-v0", "D-v0")
        )

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A-v0" to VideoConstraints(720),
            "B-v0" to VideoConstraints(180),
            "C-v0" to VideoConstraints(180),
            "D-v0" to VideoConstraints(180)
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            // We expect to be oversending when screensharing is used.
            Event(
                0.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                160.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                660.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                1320.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd15),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2000.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2050.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2460.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            )
        )
    }

    private fun verifyStageView(screensharing: Boolean = false) {
        when (screensharing) {
            true -> verifyStageViewScreensharing()
            false -> verifyStageViewCamera()
        }
    }

    private fun verifyStageViewCamera() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0"),
            setOf("A-v0", "B-v0"),
            setOf("A-v0", "B-v0", "C-v0"),
            setOf("A-v0", "B-v0", "C-v0", "D-v0")
        )
        // TODO add forwarded sources history here

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A-v0" to VideoConstraints(720),
            "B-v0" to VideoConstraints(180),
            "C-v0" to VideoConstraints(180),
            "D-v0" to VideoConstraints(180)
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                600.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                650.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                700.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                750.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                800.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                850.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                900.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                960.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            ),
            Event(
                2150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                2300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                2460.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            )
        )
    }

    private fun verifyLastN0() {
        // No video forwarded even with high BWE.
        bc.forwardedSourcesHistory.size shouldBe 0

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A-v0" to VideoConstraints(0),
            "B-v0" to VideoConstraints(0),
            "C-v0" to VideoConstraints(0),
            "D-v0" to VideoConstraints(0)
        )

        // TODO: The history contains 3 identical elements, which is probably a bug.
        bc.allocationHistory.last().event.shouldMatch(
            BandwidthAllocation(
                setOf(
                    SingleAllocation(A, targetLayer = noVideo),
                    SingleAllocation(B, targetLayer = noVideo),
                    SingleAllocation(C, targetLayer = noVideo),
                    SingleAllocation(D, targetLayer = noVideo)
                )
            )
        )
    }

    private fun verifyStageViewLastN1() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.removeIf { it.bwe < 0.bps }

        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0")
        )

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A-v0" to VideoConstraints(720),
            "B-v0" to VideoConstraints(0),
            "C-v0" to VideoConstraints(0),
            "D-v0" to VideoConstraints(0)
        )

        // At this stage the purpose of this is just to document current behavior.
        // TODO: the allocations for bwe=-1 are wrong.
        bc.allocationHistory.removeIf { it.bwe < 0.bps }

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                2010.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = hd30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            )
        )
    }

    private fun verifyTileView() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0"),
            setOf("A-v0", "B-v0"),
            setOf("A-v0", "B-v0", "C-v0"),
            setOf("A-v0", "B-v0", "C-v0", "D-v0")
        )

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = noVideo),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                450.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                610.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            )
        )
    }

    private fun verifyTileView360p() {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0"),
            setOf("A-v0", "B-v0"),
            setOf("A-v0", "B-v0", "C-v0"),
            setOf("A-v0", "B-v0", "C-v0", "D-v0")
        )

        bc.allocationHistory.shouldMatchInOrder(
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = noVideo),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = false
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                150.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                200.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                250.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld7_5),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                300.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld7_5),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                350.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld7_5)
                    )
                )
            ),
            Event(
                400.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                450.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld15),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                500.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld15),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                550.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld15)
                    )
                )
            ),
            Event(
                610.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            ),
            Event(
                960.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = ld30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            ),
            Event(
                1310.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = sd30),
                        SingleAllocation(C, targetLayer = ld30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            ),
            Event(
                1660.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = sd30),
                        SingleAllocation(C, targetLayer = sd30),
                        SingleAllocation(D, targetLayer = ld30)
                    )
                )
            ),
            Event(
                2010.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = sd30),
                        SingleAllocation(B, targetLayer = sd30),
                        SingleAllocation(C, targetLayer = sd30),
                        SingleAllocation(D, targetLayer = sd30)
                    )
                )
            )
        )
    }

    private fun verifyTileViewLastN1(maxFrameHeight: Int = 180) {
        // At this stage the purpose of this is just to document current behavior.
        // TODO: The results with bwe==-1 are wrong.
        bc.forwardedSourcesHistory.removeIf { it.bwe < 0.bps }
        bc.forwardedSourcesHistory.map { it.event }.shouldContainInOrder(
            setOf("A-v0")
        )

        bc.effectiveConstraintsHistory.last().event shouldBe mapOf(
            "A-v0" to VideoConstraints(maxFrameHeight),
            "B-v0" to VideoConstraints(0),
            "C-v0" to VideoConstraints(0),
            "D-v0" to VideoConstraints(0)
        )

        val expectedAllocationHistory = mutableListOf(
            // TODO: do we want to oversend in tile view?
            Event(
                (-1).bps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = noVideo),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    ),
                    oversending = true
                )
            ),
            Event(
                50.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld7_5),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                100.kbps,
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld15),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
                    )
                )
            ),
            Event(
                160.kbps, // TODO: why 160 instead of 150? weird.
                BandwidthAllocation(
                    setOf(
                        SingleAllocation(A, targetLayer = ld30),
                        SingleAllocation(B, targetLayer = noVideo),
                        SingleAllocation(C, targetLayer = noVideo),
                        SingleAllocation(D, targetLayer = noVideo)
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
                                SingleAllocation(A, targetLayer = sd30),
                                SingleAllocation(B, targetLayer = noVideo),
                                SingleAllocation(C, targetLayer = noVideo),
                                SingleAllocation(D, targetLayer = noVideo)
                            )
                        )
                    )
                )
            )
        }
        bc.allocationHistory.shouldMatchInOrder(*expectedAllocationHistory.toTypedArray())
    }
}

class BitrateControllerWrapper2(initialEndpoints: List<MediaSourceContainer>, val clock: FakeClock = FakeClock()) {
    var endpoints: List<MediaSourceContainer> = initialEndpoints
    val logger = createLogger()

    var bwe = (-1).bps
        set(value) {
            logger.debug("Setting bwe=$value")
            field = value
            bc.bandwidthChanged(value.bps.toLong())
        }

    // Save the output.
    val effectiveConstraintsHistory: History<Map<String, VideoConstraints>> = mutableListOf()
    val forwardedSourcesHistory: History<Set<String>> = mutableListOf()
    val allocationHistory: History<BandwidthAllocation> = mutableListOf()

    val bc = BitrateController(
        object : BitrateController.EventHandler {
            override fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>) { }

            override fun forwardedSourcesChanged(forwardedSources: Set<String>) {
                Event(bwe, forwardedSources, clock.instant()).apply {
                    logger.info("Forwarded sources changed: $this")
                    forwardedSourcesHistory.add(this)
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

    fun setEndpointOrdering(vararg endpoints: TestEndpoint2) {
        logger.info("Set endpoints ${endpoints.map{ it.id }.joinToString(",")}")
        this.endpoints = endpoints.toList()
        bc.endpointOrderingChanged()
    }

    fun setStageView(onStageSource: String, lastN: Int? = null) {
        bc.setBandwidthAllocationSettings(
            ReceiverVideoConstraintsMessage(
                lastN = lastN,
                onStageSources = listOf(onStageSource),
                constraints = mapOf(onStageSource to VideoConstraints(720))
            )
        )
    }

    fun setTileView(
        vararg selectedSources: String,
        maxFrameHeight: Int = 180,
        lastN: Int? = null
    ) {
        bc.setBandwidthAllocationSettings(
            ReceiverVideoConstraintsMessage(
                lastN = lastN,
                selectedSources = listOf(*selectedSources),
                constraints = selectedSources.map { it to VideoConstraints(maxFrameHeight) }.toMap()
            )
        )
    }

    init {
        // The BC only starts working 10 seconds after it first received media, so fake that.
        bc.transformRtp(PacketInfo(VideoRtpPacket(ByteArray(100), 0, 100)))
        clock.elapse(15.secs)

        // Adaptivity is disabled when RTX support is not signalled.
        bc.addPayloadType(RtxPayloadType(123, mapOf("apt" to "124")))
    }
}

class TestEndpoint2(
    override val id: String,
    override val mediaSources: Array<MediaSourceDesc> = emptyArray(),
    override val videoType: VideoType = VideoType.CAMERA,
    override val mediaSource: MediaSourceDesc? = null,
) : MediaSourceContainer

fun createEndpoints2(vararg ids: String): MutableList<TestEndpoint2> {
    return MutableList(ids.size) { i ->
        TestEndpoint2(
            ids[i],
            arrayOf(
                createSourceDesc(
                    3 * i + 1,
                    3 * i + 2,
                    3 * i + 3,
                    ids[i] + "-v0",
                    ids[i]
                )
            )
        )
    }
}

fun createSources(vararg ids: String): MutableList<MediaSourceDesc> {
    return MutableList(ids.size) { i ->
        createSourceDesc(
            3 * i + 1,
            3 * i + 2,
            3 * i + 3,
            ids[i],
            null
        )
    }
}

fun createSourceDesc(
    ssrc1: Int,
    ssrc2: Int,
    ssrc3: Int,
    sourceName: String,
    owner: String?
): MediaSourceDesc = MediaSourceDesc(
    arrayOf(
        RtpEncodingDesc(ssrc1.toLong(), arrayOf(ld7_5, ld15, ld30)),
        RtpEncodingDesc(ssrc2.toLong(), arrayOf(sd7_5, sd15, sd30)),
        RtpEncodingDesc(ssrc3.toLong(), arrayOf(hd7_5, hd15, hd30))
    ),
    sourceName = sourceName,
    owner = owner
)
