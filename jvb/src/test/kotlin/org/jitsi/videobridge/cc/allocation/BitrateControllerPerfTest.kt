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

import io.kotest.core.spec.style.StringSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.nanos
import org.jitsi.utils.secs
import java.util.function.Supplier
import kotlin.random.Random
import kotlin.time.ExperimentalTime

/**
 * Keep track of the performance of the bandwidth allocation code.
 *
 * The following results were obtained on an AWS t3.large instance. The tests for stage and tile view were run
 * separately (only one was enabled when the command was run). Shown is the "microseconds per speaker switch", averaged
 * across the 10 runs (warmup excluded). The test was run with `mvn clean test -Dtest=BitrateControllerPerfTest`.
 *
 * Tested three versions of the code:
 * 1. Pre-refactoring: on top of 3bf235fb9a2c9ce8caa52de9544f0adcd3933752
 * 2. After refactoring (PR #1530): 98af5107c99e057f51fdf0f60acc2d8aa9b364bb
 * 3. After refactoring and optimization (PR #1557): ef237f692677d3e5c81b7050b17103a001d3edec
 *
 * Tile View:
 * pre-ref: 4.16
 * after refactoring: 5.84
 * after optimization: 4.44
 *
 * Stage View:
 * pre-ref: 4.15
 * after refactoring: 6.56
 * after optimization: 4.48
 *
 *
 */
@ExperimentalTime
class BitrateControllerPerfTest : StringSpec() {
    private val logger = createLogger()
    private val clock = FakeClock()
    private val random = Random(93232)

    private val endpointIds = mutableListOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
    private val endpoints: MutableList<TestEndpoint> = createEndpoints(*endpointIds.toTypedArray())
    private val bc = BitrateController(
        object : BitrateController.EventHandler {
            override fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>) { }
            override fun forwardedSourcesChanged(forwardedSources: Set<String>) { }
            override fun effectiveVideoConstraintsChanged(
                oldEffectiveConstraints: Map<String, VideoConstraints>,
                newEffectiveConstraints: Map<String, VideoConstraints>
            ) { }
            override fun keyframeNeeded(endpointId: String?, ssrc: Long) { }
            override fun allocationChanged(allocation: BandwidthAllocation) { }
        },
        Supplier { endpoints.toList() },
        DiagnosticContext(),
        createLogger(),
        clock
    ).apply {
        // The BC only starts working 10 seconds after it first received media, so fake that.
        transformRtp(PacketInfo(VideoRtpPacket(ByteArray(100), 0, 100)))
        clock.elapse(15.secs)

        // Adaptivity is disabled when RTX support is not signalled.
        addPayloadType(RtxPayloadType(123, mapOf("apt" to "124")))
    }

    init {
        "Tile view".config(enabled = false) {
            repeat(5) {
                run("Warmup", listOf("A", "B", "C", "D", "E"), 180)
            }
            repeat(10) {
                run("Tile view", listOf("A", "B", "C", "D", "E"), 180)
            }
        }
        "Stage view".config(enabled = false) {
            repeat(5) {
                run("Warmup", listOf("A"), 720)
            }
            repeat(10) {
                run("Stage view", listOf("A"), 720)
            }
        }
    }

    private fun run(testName: String, selectedEndpoints: List<String>, maxFrameHeight: Int) {

        val start = System.nanoTime()
        bc.lastN = 7

        // Ramp-up to 5mbps
        for (bwe in 0..5_000_000 step 10_000) {
            bc.bandwidthChanged(bwe.toLong())
            clock.elapse(100.ms)
        }

        bc.setSelectedEndpoints(selectedEndpoints)
        bc.setMaxFrameHeight(maxFrameHeight)
        bc.endpointOrderingChanged()

        // Change the dominant speaker just a couple of times.
        repeat(NUM_SPEAKER_CHANGES) {
            endpoints.selectNewDominantSpeaker()
            bc.endpointOrderingChanged()
            clock.elapse(2.secs)
        }

        val stop = System.nanoTime()
        val totalDuration = (stop - start).nanos
        val microsPerSpeakerChange = ((stop - start).toDouble() / NUM_SPEAKER_CHANGES) / 1000.0
        logger.info("$testName took a total of $totalDuration, $microsPerSpeakerChange µs per speaker change.")
    }

    private fun <T : Any> MutableList<T>.selectNewDominantSpeaker() {
        val newDominantSpeakerIdx = 1 + random.nextInt(size - 1)
        val newDominantSpeaker = this.removeAt(newDominantSpeakerIdx)
        this.add(0, newDominantSpeaker)
    }
}

const val NUM_SPEAKER_CHANGES = 1_000_000
