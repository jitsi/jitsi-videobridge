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
package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.per
import org.jitsi.nlj.util.times
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.max
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

/** Unit tests for BitrateProber,
 * based on WebRTC modules/pacing/bitrate_prober_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class BitrateProberTest : FreeSpec() {
    private val logger = createLogger()

    init {
        "VerifyStatesAndTimeBetweenProbes" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            prober.isProbing() shouldBe false

            var now = Instant.EPOCH
            val startTime = now
            prober.nextProbeTime(now) shouldBe Instant.MAX

            val kTestBitrate1 = 900.kbps
            val kTestBitrate2 = 1800.kbps
            val kClusterSize = 5
            val kProbeSize = 1000.bytes
            val kMinProbeDuration = 15.ms

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kTestBitrate1,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kTestBitrate2,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 1
                )
            )
            prober.isProbing() shouldBe false

            prober.onIncomingPacket(kProbeSize)
            prober.isProbing() shouldBe true
            prober.currentCluster(now)!!.probeClusterId shouldBe 0

            // First packet should probe as soon as possible
            prober.nextProbeTime(now) shouldBe Instant.MIN

            for (i in 0 until kClusterSize) {
                now = max(now, prober.nextProbeTime(now))
                now shouldBe max(now, prober.nextProbeTime(now))
                prober.currentCluster(now)!!.probeClusterId shouldBe 0
                prober.probeSent(now, kProbeSize)
            }

            Duration.between(startTime, now) shouldBeGreaterThanOrEqualTo kMinProbeDuration
            // Verify that the actual bitrate is withing 10% of the target.
            var bitrate = (kProbeSize * (kClusterSize - 1)).per(Duration.between(startTime, now))
            bitrate shouldBeGreaterThan kTestBitrate1 * 0.9
            bitrate shouldBeLessThan kTestBitrate1 * 1.1

            now = max(now, prober.nextProbeTime(now))
            val probe2Started = now

            for (i in 0 until kClusterSize) {
                now = max(now, prober.nextProbeTime(now))
                now shouldBe prober.nextProbeTime(now)
                prober.currentCluster(now)!!.probeClusterId shouldBe 1
                prober.probeSent(now, kProbeSize)
            }

            val duration = Duration.between(probe2Started, now)
            duration shouldBeGreaterThanOrEqualTo kMinProbeDuration
            // Verify that the actual bitrate is withing 10% of the target.
            bitrate = (kProbeSize * (kClusterSize - 1)).per(duration)
            bitrate shouldBeGreaterThan kTestBitrate2 * 0.9
            bitrate shouldBeLessThan kTestBitrate2 * 1.1

            prober.nextProbeTime(now) shouldBe Instant.MAX
            prober.isProbing() shouldBe false
        }

        "DoesntProbeWithoutRecentPackets" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            val kProbeSize = 1000.bytes

            val now = Instant.EPOCH
            prober.nextProbeTime(now) shouldBe Instant.MAX

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = 900.kbps,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.isProbing() shouldBe false

            prober.onIncomingPacket(kProbeSize)
            prober.isProbing() shouldBe true
            max(now, prober.nextProbeTime(now)) shouldBe now
            prober.probeSent(now, kProbeSize)
        }

        "DiscardsDelayedProbes" {
            val kMaxProbeDelay = 3.ms
            val config = BitrateProberConfig(
                maxProbeDelay = 3.ms
            )
            val prober = BitrateProber(logger, config)
            val kProbeSize = 1000.bytes

            var now = Instant.EPOCH

            // Add two probe clusters.
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = 900.kbps,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )

            prober.onIncomingPacket(kProbeSize)
            prober.isProbing() shouldBe true
            prober.currentCluster(now)!!.probeClusterId shouldBe 0
            // Advance to first probe time and indicate sent probe.
            now = max(now, prober.nextProbeTime(now))
            prober.probeSent(now, kProbeSize)

            // Advance time 1ms past timeout for the next probe.
            val nextProbeTime = prober.nextProbeTime(now)
            nextProbeTime shouldBeGreaterThan now
            now = nextProbeTime + kMaxProbeDelay + 1.ms

            // Still indicates the time we wanted to probe at.
            prober.nextProbeTime(now) shouldBe nextProbeTime
            prober.currentCluster(now) shouldBe null
        }

        "LimitsNumberOfPendingProbeClusters" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            val kProbeSize = 1000.bytes
            var now = Instant.EPOCH
            prober.nextProbeTime(now) shouldBe Instant.MAX
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = 900.kbps,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(kProbeSize)
            prober.isProbing() shouldBe true
            prober.currentCluster(now)!!.probeClusterId shouldBe 0

            for (i in 1 until 11) {
                prober.createProbeCluster(
                    ProbeClusterConfig(
                        atTime = now,
                        targetDataRate = 900.kbps,
                        targetDuration = 15.ms,
                        minProbeDelta = 2.ms,
                        targetProbeCount = 5,
                        id = i
                    )
                )
                prober.onIncomingPacket(kProbeSize)
            }
            // Expect some clusters has been dropped.
            prober.isProbing() shouldBe true
            prober.currentCluster(now)!!.probeClusterId shouldBeGreaterThanOrEqualTo 5

            val maxExpectedProbeTime = now + 1.secs
            while (prober.isProbing() && now < maxExpectedProbeTime) {
                now = max(now, prober.nextProbeTime(now))
                prober.probeSent(now, kProbeSize)
            }
            prober.isProbing() shouldBe false
        }

        "DoesntInitializeProbingForSmallPackets" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            prober.setEnabled(true)
            prober.isProbing() shouldBe false

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = Instant.EPOCH,
                    targetDataRate = 1000.kbps,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(100.bytes)

            prober.isProbing() shouldBe false
        }

        "DoesInitializeProbingForSmallPacketsIfConfigured" {
            val config = BitrateProberConfig(
                minPacketSize = 0.bytes
            )

            val prober = BitrateProber(logger, config)
            prober.setEnabled(true)
            prober.isProbing() shouldBe false

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = Instant.EPOCH,
                    targetDataRate = 1000.kbps,
                    targetDuration = 15.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(10.bytes)

            prober.isProbing() shouldBe true
        }

        "VerifyProbeSizeOnHighBitrate" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)

            val kHighBitrate = 10000.kbps

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = Instant.EPOCH,
                    targetDataRate = kHighBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            // Probe size should ensure a minimum of 1 ms interval.
            prober.recommendedMinProbeSize() shouldBeGreaterThan kHighBitrate * 1.ms
        }

        "ProbeSizeCanBeSetInProbeClusterConfig" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            prober.setEnabled(true)

            val kHighBitrate = 10000.kbps

            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = Instant.EPOCH,
                    targetDataRate = kHighBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 20.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.recommendedMinProbeSize() shouldBe kHighBitrate * 20.ms

            prober.onIncomingPacket(1000.bytes)
            // Next time to send probe should be "min_probe_delta" if the recommended
            // number of bytes has been sent.
            prober.probeSent(Instant.EPOCH, prober.recommendedMinProbeSize())
            prober.nextProbeTime(Instant.EPOCH) shouldBe Instant.EPOCH + 20.ms
        }

        "MinumumNumberOfProbingPackets" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            // Even when probing at a low bitrate we expect a minimum number
            // of packets to be sent.
            val kBitrate = 100.kbps
            val kPacketSize = 1000.bytes

            val now = Instant.EPOCH
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )

            prober.onIncomingPacket(kPacketSize)
            for (i in 0 until 5) {
                prober.isProbing() shouldBe true
                prober.probeSent(now, kPacketSize)
            }

            prober.isProbing() shouldBe false
        }

        "ScaleBytesUsedForProbing" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            val kBitrate = 10000.kbps // 10 Mbps.
            val kPacketSize = 1000.bytes
            val kExpectedDataSent = kBitrate * 15.ms

            val now = Instant.EPOCH
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(kPacketSize)
            var dataSent = DataSize.ZERO
            while (dataSent < kExpectedDataSent) {
                prober.isProbing() shouldBe true
                prober.probeSent(now, kPacketSize)
                dataSent += kPacketSize
            }

            prober.isProbing() shouldBe false
        }

        "HighBitrateProbing" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            val kBitrate = 1000000.kbps // 1 Gbps.
            val kPacketSize = 1000.bytes
            val kExpectedDataSent = kBitrate * 15.ms

            val now = Instant.EPOCH
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(kPacketSize)
            var dataSent = DataSize.ZERO
            while (dataSent < kExpectedDataSent) {
                prober.isProbing() shouldBe true
                prober.probeSent(now, kPacketSize)
                dataSent += kPacketSize
            }

            prober.isProbing() shouldBe false
        }

        "ProbeClusterTimeout" {
            val config = BitrateProberConfig()
            val prober = BitrateProber(logger, config)
            val kBitrate = 300.kbps
            val kSmallPacketSize = 20.bytes
            // Expecting two probe clusters of 5 packets each.
            val kExpectedDataSent = kSmallPacketSize * 2 * 5
            val kTimeout = 5000.ms

            var now = Instant.EPOCH
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 0
                )
            )
            prober.onIncomingPacket(kSmallPacketSize)
            prober.isProbing() shouldBe false
            now += kTimeout
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate / 10,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 1
                )
            )
            prober.onIncomingPacket(kSmallPacketSize)
            prober.isProbing() shouldBe false
            now += 1.ms
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = kBitrate / 10,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    targetProbeCount = 5,
                    id = 2
                )
            )
            prober.onIncomingPacket(kSmallPacketSize)
            prober.isProbing() shouldBe true
            var dataSent = DataSize.ZERO
            while (dataSent < kExpectedDataSent) {
                prober.isProbing() shouldBe true
                prober.probeSent(now, kSmallPacketSize)
                dataSent += kSmallPacketSize
            }

            prober.isProbing() shouldBe false
        }

        "CanProbeImmediatelyIfConfigured" {
            val config = BitrateProberConfig(
                minPacketSize = 0.bytes,
            )
            val prober = BitrateProber(logger, config)
            prober.createProbeCluster(
                ProbeClusterConfig(
                    atTime = Instant.EPOCH,
                    targetDataRate = 300.kbps,
                    targetDuration = 15.ms,
                    minProbeDelta = 2.ms,
                    id = 0
                )
            )
            prober.isProbing() shouldBe true
        }

        "CanProbeImmediatelyAgainAfterProbeIfConfigured" {
            val config = BitrateProberConfig(
                minPacketSize = 0.bytes,
            )
            val prober = BitrateProber(logger, config)
            val clusterConfig = ProbeClusterConfig(
                atTime = Instant.EPOCH,
                targetDataRate = 300.kbps,
                targetDuration = 15.ms,
                minProbeDelta = 2.ms,
                targetProbeCount = 1,
                id = 0
            )
            prober.createProbeCluster(clusterConfig)
            prober.isProbing() shouldBe true
            prober.probeSent(
                Instant.EPOCH + 1.ms,
                clusterConfig.targetDataRate * clusterConfig.targetDuration
            )
            prober.isProbing() shouldBe false

            clusterConfig.id = 2
            clusterConfig.atTime = Instant.EPOCH + 100.ms
            prober.createProbeCluster(clusterConfig)
            prober.isProbing() shouldBe true
        }
    }
}
