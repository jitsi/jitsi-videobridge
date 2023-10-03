/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import io.kotest.assertions.fail
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeLessThan
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.util.bps
import org.jitsi.utils.time.FakeClock
import java.time.Instant

/**
 * This test reads a trace file containing bandwidth estimations and bitrates for individual layers captured from a
 * "real" conference. The conference had a total of 7 endpoints (one receiver and 6 senders), and the bridge->receiver
 * BWE was artificially reset to 0 just prior to taking the trace, simulating a ramp-up from 0.
 *
 * While [BitrateControllerTest] tests the individual decisions made by [BandwidthAllocator] when the bitrates of the
 * layers are fixed, here we test for the number of allocation changes which are visible to the user as changes in the
 * number and/or quality of the videos being shown. Specifically, we want to limit the "flickering" which happens as
 * a result of fluctuations in the bitrates of the layers and the changes in BWE.
 */
class BitrateControllerTraceTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val clock = FakeClock()
    private val a = Endpoint("A")
    private val b = Endpoint("B")
    private val c = Endpoint("C")
    private val d = Endpoint("D")
    private val e = Endpoint("E")
    private val f = Endpoint("F")
    private val bc = BitrateControllerWrapper(listOf(a, b, c, d, e, f), clock = clock).apply {
        bc.endpointOrderingChanged()
    }

    init {
        val bweEvents = javaClass.getResource("/bwe-events.csv") ?: fail("Can not read bwe-events.csv")
        val parsedLines = bweEvents.readText().lines().drop(1).dropLast(1).map { ParsedLine(it) }.toList()

        context("Number of allocation changes") {

            println("Read ${parsedLines.size} events.")
            parsedLines.forEach { line ->
                clock.setTime(line.time)
                a.layer7.bitrate = line.bps_a_7.bps
                a.layer30.bitrate = line.bps_a_30.bps
                b.layer7.bitrate = line.bps_b_7.bps
                b.layer30.bitrate = line.bps_b_30.bps
                c.layer7.bitrate = line.bps_c_7.bps
                c.layer30.bitrate = line.bps_c_30.bps
                d.layer7.bitrate = line.bps_d_7.bps
                d.layer30.bitrate = line.bps_d_30.bps
                e.layer7.bitrate = line.bps_e_7.bps
                e.layer30.bitrate = line.bps_e_30.bps
                f.layer7.bitrate = line.bps_f_7.bps
                f.layer30.bitrate = line.bps_f_30.bps
                bc.bwe = line.bwe.bps
            }

            println("Finished. Made a total of ${bc.allocationHistory.size} allocation changes.")
            // Ideally as the BWE ramps up from 0 we'd see the endpoints being added sequentially, with some changes
            // from 7 fps to 30 fps.
            bc.allocationHistory.size shouldBeLessThan 20
        }
    }

    class Endpoint(
        override val id: String,
        override val mediaSources: Array<MediaSourceDesc> = emptyArray()
    ) : MediaSourceContainer {
        val layer7 = MockRtpLayerDesc(tid = 0, eid = 0, height = 180, frameRate = 7.5, 0.bps)
        val layer30 = MockRtpLayerDesc(tid = 2, eid = 0, height = 180, frameRate = 30.0, bitrate = 0.bps)
    }
}

/**
 * Parse a timestamp from the CSV file as an [Instant]. The actual instant doesn't matter as long as the relationships
 * between the timestamps in the file are preserved.
 */
private fun parseTs(
    /**
     * A timestamp in this format: 19:52:49.234
     */
    ts: String
) = Instant.parse("2020-01-01T${ts}Z")

private data class ParsedLine(
    val time: Instant,
    val bwe: Long,
    val bps_a_7: Long,
    val bps_a_30: Long,
    val bps_b_7: Long,
    val bps_b_30: Long,
    val bps_c_7: Long,
    val bps_c_30: Long,
    val bps_d_7: Long,
    val bps_d_30: Long,
    val bps_e_7: Long,
    val bps_e_30: Long,
    val bps_f_7: Long,
    val bps_f_30: Long
) {
    /**
     * ts,bwe,bps_a_7,bps_a_30,bps_b_7,bps_b_30,bps_c_7,bps_c_30,bps_d_7,bps_d_30,bps_e_7,bps_e_30,bps_f_7,bps_f_30
     * 19:52:49.234,33400,93560,157626,86717,160533,54922,135183,93405,156291,92150,155582,92397,152019
     */
    constructor(line: String) : this(
        parseTs(line.split(",")[0]),
        line.split(",")[1].toLong(),
        line.split(",")[2].toLong(),
        line.split(",")[3].toLong(),
        line.split(",")[4].toLong(),
        line.split(",")[5].toLong(),
        line.split(",")[6].toLong(),
        line.split(",")[7].toLong(),
        line.split(",")[8].toLong(),
        line.split(",")[9].toLong(),
        line.split(",")[10].toLong(),
        line.split(",")[11].toLong(),
        line.split(",")[12].toLong(),
        line.split(",")[13].toLong()
    )
}
