/*
 * Copyright @ 2019-present 8x8, Inc
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.spyk
import io.mockk.verifySequence
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import java.time.Instant

/**
 * Unit tests of AcknowledgedBitrateEstimator.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/acknowledged_bitrate_estimator_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

class AcknowledgedBitrateEstimatorTestStates {
    val mockBitrateEstimator = spyk(BitrateEstimator())

    val acknowledgedBitrateEstimator = AcknowledgedBitrateEstimator(mockBitrateEstimator)
}

fun createFeedbackVector(): List<PacketResult> {
    val packetFeedbackVector = MutableList(2) { PacketResult() }
    packetFeedbackVector[0].receiveTime = Instant.ofEpochMilli(kFirstArrivalTimeMs)
    packetFeedbackVector[0].sentPacket.sendTime = Instant.ofEpochMilli(kFirstSendTimeMs)
    packetFeedbackVector[0].sentPacket.sequenceNumber = kSequenceNumber
    packetFeedbackVector[0].sentPacket.size = kPayloadSize.bytes
    packetFeedbackVector[1].receiveTime =
        Instant.ofEpochMilli(kFirstArrivalTimeMs + 10)
    packetFeedbackVector[1].sentPacket.sendTime =
        Instant.ofEpochMilli(kFirstSendTimeMs + 10)
    packetFeedbackVector[1].sentPacket.sequenceNumber =
        kSequenceNumber
    packetFeedbackVector[1].sentPacket.size =
        (kPayloadSize + 10).bytes
    return packetFeedbackVector
}

const val kFirstArrivalTimeMs = 10L
const val kFirstSendTimeMs = 10L
const val kSequenceNumber = 1L
const val kPayloadSize = 1L

class AcknowledgedBitrateEstimatorTest : FreeSpec() {
    init {
        "UpdateBandwidth" {
            val states = AcknowledgedBitrateEstimatorTestStates()
            val packetFeedbackVector = createFeedbackVector()

            states.acknowledgedBitrateEstimator.incomingPacketFeedbackVector(packetFeedbackVector)

            verifySequence {
                states.mockBitrateEstimator.update(
                    packetFeedbackVector[0].receiveTime,
                    packetFeedbackVector[0].sentPacket.size,
                    false
                )
                states.mockBitrateEstimator.update(
                    packetFeedbackVector[1].receiveTime,
                    packetFeedbackVector[1].sentPacket.size,
                    false
                )
            }

            confirmVerified(states.mockBitrateEstimator)
        }

        "ExpectFastRateChangeWhenLeftAlr" {
            val states = AcknowledgedBitrateEstimatorTestStates()
            val packetFeedbackVector = createFeedbackVector()

            states.acknowledgedBitrateEstimator.setAlrEndedTime(Instant.ofEpochMilli(kFirstArrivalTimeMs + 1))
            states.acknowledgedBitrateEstimator.incomingPacketFeedbackVector(packetFeedbackVector)

            verifySequence {
                states.mockBitrateEstimator.update(
                    packetFeedbackVector[0].receiveTime,
                    packetFeedbackVector[0].sentPacket.size,
                    false
                )
                states.mockBitrateEstimator.expectFastRateChange()
                states.mockBitrateEstimator.update(
                    packetFeedbackVector[1].receiveTime,
                    packetFeedbackVector[1].sentPacket.size,
                    false
                )
            }
        }

        "ReturnBitrate".config(enabled = false) {
            val states = AcknowledgedBitrateEstimatorTestStates()
            val returnValue = 42.kbps

            // Unfortunately this goes into an infinite loop in mockk.
            every { states.mockBitrateEstimator.bitrate() } returns returnValue

            states.acknowledgedBitrateEstimator.bitrate() shouldBe returnValue

            verifySequence {
                states.mockBitrateEstimator.bitrate()
            }
        }
    }
}
