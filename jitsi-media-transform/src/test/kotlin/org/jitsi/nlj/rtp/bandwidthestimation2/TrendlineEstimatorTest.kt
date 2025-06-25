/*
 * Copyright @ 2019 - present 8x8, Inc.
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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger

/**
 * Unit tests of TrendlineEstimator
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/trendline_estimator_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class OneTrendlineEstimatorTest(
    sendTimeGenerator: (Int) -> Long,
    recvTimeGenerator: (Int) -> Long,
    packetSize: Long = kPacketSizeBytes
) {
    val sendTimes = LongArray(kPacketCount, sendTimeGenerator)
    val recvTimes = LongArray(kPacketCount, recvTimeGenerator)
    val packetSizes = LongArray(kPacketCount) { packetSize }

    var count = 1

    val estimator = TrendlineEstimator(createLogger(), DiagnosticContext())

    fun runTestUntilStateChange() {
        check(count >= 1)
        check(count < kPacketCount)

        val initialState = estimator.state()
        while (count < kPacketCount) {
            val recvDelta = recvTimes[count] - recvTimes[count - 1]
            val sendDelta = sendTimes[count] - sendTimes[count - 1]
            estimator.update(
                recvDelta.toDouble(),
                sendDelta.toDouble(),
                sendTimes[count],
                recvTimes[count],
                packetSizes[count],
                true
            )
            if (estimator.state() != initialState) {
                return
            }
            count++
        }
    }

    companion object {
        const val kPacketCount = 25
        const val kPacketSizeBytes = 1200L
    }
}

class TrendlineEstimatorTest : FreeSpec() {
    init {
        "Normal usage" {
            val sendTimeGenerator = PacketTimeGenerator(initialClock = 123456789, timeBetweenPackets = 20.0)
            /* Delivered at the same pace */
            val recvTimeGenerator = PacketTimeGenerator(initialClock = 987654321, timeBetweenPackets = 20.0)

            val test = OneTrendlineEstimatorTest(sendTimeGenerator::get, recvTimeGenerator::get)
            test.estimator.state() shouldBe BandwidthUsage.kBwNormal
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwNormal
            test.count shouldBe OneTrendlineEstimatorTest.kPacketCount // All packets processed
        }

        "Overusing" {
            val sendTimeGenerator = PacketTimeGenerator(initialClock = 123456789, timeBetweenPackets = 20.0)
            /* 10% slower delivery */
            val recvTimeGenerator = PacketTimeGenerator(initialClock = 987654321, timeBetweenPackets = 1.1 * 20.0)

            val test = OneTrendlineEstimatorTest(sendTimeGenerator::get, recvTimeGenerator::get)
            test.estimator.state() shouldBe BandwidthUsage.kBwNormal
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwOverusing
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwOverusing
            test.count shouldBe OneTrendlineEstimatorTest.kPacketCount // All packets processed
        }

        "Underusing" {
            val sendTimeGenerator = PacketTimeGenerator(initialClock = 123456789, timeBetweenPackets = 20.0)
            /* 15% faster delivery */
            val recvTimeGenerator = PacketTimeGenerator(initialClock = 987654321, timeBetweenPackets = 0.85 * 20.0)

            val test = OneTrendlineEstimatorTest(sendTimeGenerator::get, recvTimeGenerator::get)
            test.estimator.state() shouldBe BandwidthUsage.kBwNormal
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwUnderusing
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwUnderusing
            test.count shouldBe OneTrendlineEstimatorTest.kPacketCount // All packets processed
        }

        "IncludesSmallPacketsByDefault" {
            val sendTimeGenerator = PacketTimeGenerator(initialClock = 123456789, timeBetweenPackets = 20.0)
            /* 10% slower delivery */
            val recvTimeGenerator = PacketTimeGenerator(initialClock = 987654321, timeBetweenPackets = 1.1 * 20.0)

            val test = OneTrendlineEstimatorTest(sendTimeGenerator::get, recvTimeGenerator::get, 100)
            test.estimator.state() shouldBe BandwidthUsage.kBwNormal
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwOverusing
            test.runTestUntilStateChange()
            test.estimator.state() shouldBe BandwidthUsage.kBwOverusing
            test.count shouldBe OneTrendlineEstimatorTest.kPacketCount // All packets processed
        }
    }
}

class PacketTimeGenerator(
    private val initialClock: Long,
    private val timeBetweenPackets: Double
) {
    private var packets = 0

    fun get(idx: Int): Long = (initialClock + timeBetweenPackets * idx).toLong()
}
