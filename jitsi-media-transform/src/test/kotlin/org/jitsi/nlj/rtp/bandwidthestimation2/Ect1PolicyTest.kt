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
// This file uses WebRTC's naming style for constants
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.times
import java.time.Instant

private val kTimeoutBeforeAck = 500.ms
private val kTimeoutAfterAck = 5.secs
private val kRetryDelay = 10.secs

// Shorter than the largest gap that counts as time spent sending, so that
// every interval counts in full.
private val kSendInterval = 100.ms
private val kStartTime = Instant.ofEpochSecond(1000)

// When sending ECT(1) from kStartTime without feedback stops the marking.
private val kDropDetectedTime = kStartTime + kTimeoutBeforeAck + kSendInterval

/** Unit tests for Ect1Policy,
 * based on WebRTC modules/congestion_controller/ect1_policy_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 */
class Ect1PolicyTest : FreeSpec() {
    private val logger = createLogger()

    private fun createEnabledPolicy(): Ect1Policy {
        val policy = Ect1Policy(logger)
        policy.setFeedbackSupportsEcn(true)
        policy.setCongestionControllerSupportsEcn(true)
        return policy
    }

    // Sends an ECT(1) packet every kSendInterval in [start, end].
    private fun sendEct1Packets(start: Instant, end: Instant, policy: Ect1Policy) {
        var now = start
        while (now <= end) {
            policy.onPacketSent(now, sentAsEct1 = true)
            now += kSendInterval
        }
    }

    private fun createPolicyThatDetectedDroppedEct1(): Ect1Policy {
        val policy = createEnabledPolicy()
        sendEct1Packets(kStartTime, kDropDetectedTime, policy)
        return policy
    }

    init {
        "DoesNotSendEct1ByDefault" {
            val policy = Ect1Policy(logger)
            policy.shouldSendEct1() shouldBe false
        }

        "SendsEct1IfFeedbackAndCongestionControllerSupportEcn" {
            createEnabledPolicy().shouldSendEct1() shouldBe true
        }

        "DoesNotSendEct1IfFeedbackDoesNotSupportEcn" {
            val policy = Ect1Policy(logger)
            policy.setFeedbackSupportsEcn(false)
            policy.setCongestionControllerSupportsEcn(true)
            policy.shouldSendEct1() shouldBe false
        }

        "DoesNotSendEct1IfCongestionControllerDoesNotSupportEcn" {
            val policy = Ect1Policy(logger)
            policy.setFeedbackSupportsEcn(true)
            policy.setCongestionControllerSupportsEcn(false)
            policy.shouldSendEct1() shouldBe false
        }

        "StopsSendingEct1IfFeedbackReportsBleaching" {
            val policy = createEnabledPolicy()
            policy.onPacketsFeedback(hasBleachedEct1 = true, hasPreservedEct1 = false)
            policy.shouldSendEct1() shouldBe false
        }

        "KeepsSendingEct1IfFeedbackReportsNoBleaching" {
            val policy = createEnabledPolicy()
            policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = false)
            policy.shouldSendEct1() shouldBe true
        }

        "RouteChangeReEnablesEct1AfterBleaching" {
            val policy = createEnabledPolicy()
            policy.onPacketsFeedback(hasBleachedEct1 = true, hasPreservedEct1 = true)
            policy.shouldSendEct1() shouldBe false

            policy.onNetworkRouteChanged()
            policy.shouldSendEct1() shouldBe true
        }

        "RenegotiationDoesNotReEnableEct1AfterBleaching" {
            val policy = createEnabledPolicy()
            policy.onPacketsFeedback(hasBleachedEct1 = true, hasPreservedEct1 = true)
            policy.shouldSendEct1() shouldBe false

            policy.setFeedbackSupportsEcn(true)
            policy.shouldSendEct1() shouldBe false
        }

        "StopsSendingEct1IfNoFeedbackWhileSendingEct1" {
            val policy = createEnabledPolicy()
            val lastSend = kStartTime + kTimeoutBeforeAck
            sendEct1Packets(kStartTime, lastSend, policy)
            policy.shouldSendEct1() shouldBe true

            // One more packet takes the time spent sending past the timeout.
            policy.onPacketSent(lastSend + kSendInterval, sentAsEct1 = true)
            policy.shouldSendEct1() shouldBe false
        }

        "PacketsSentAsNotEctDoNotStopSendingEct1" {
            val policy = createEnabledPolicy()
            var now = kStartTime
            while (now <= kStartTime + 30.secs) {
                policy.onPacketSent(now, sentAsEct1 = false)
                now += kSendInterval
            }
            policy.shouldSendEct1() shouldBe true
        }

        "KeepsSendingEct1WhileTheMarkingIsPreserved" {
            val policy = createEnabledPolicy()
            var now = kStartTime
            for (i in 0 until 100) {
                policy.onPacketSent(now, sentAsEct1 = true)
                policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = true)
                now += kSendInterval
            }
            policy.shouldSendEct1() shouldBe true
        }

        "FeedbackForPacketsSentBeforeEct1KeepsEct1Enabled" {
            // Just after ECT(1) is enabled, feedback still reports packets that were
            // sent as Not-ECT. It proves that packets reach the receiver, so it must
            // stop the timeout just like feedback that acknowledges an ECT(1) packet.
            val policy = createEnabledPolicy()
            val lastSend = kStartTime + kTimeoutBeforeAck
            sendEct1Packets(kStartTime, lastSend, policy)
            policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = false)

            // Without the feedback this packet would stop the marking.
            policy.onPacketSent(lastSend + kSendInterval, sentAsEct1 = true)
            policy.shouldSendEct1() shouldBe true
        }

        "UsesLongerTimeoutAfterTheMarkingIsPreserved" {
            val policy = createEnabledPolicy()
            policy.onPacketSent(kStartTime, sentAsEct1 = true)
            policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = true)

            // Sending past the short timeout is no longer enough.
            val start = kStartTime + kSendInterval
            sendEct1Packets(start, start + kTimeoutBeforeAck + kSendInterval, policy)
            policy.shouldSendEct1() shouldBe true

            // Sending past the long timeout is.
            sendEct1Packets(
                start + kTimeoutBeforeAck + 2 * kSendInterval,
                start + kTimeoutAfterAck + kSendInterval,
                policy
            )
            policy.shouldSendEct1() shouldBe false
        }

        "DoesNotStopSendingEct1AfterAPauseInSending" {
            // Nothing can be concluded from a period where nothing was sent, however
            // long it is.
            val policy = createEnabledPolicy()
            policy.onPacketSent(kStartTime, sentAsEct1 = true)
            policy.onPacketSent(kStartTime + 30.secs, sentAsEct1 = true)
            policy.shouldSendEct1() shouldBe true
        }

        "DoesNotStopSendingEct1AfterAPauseFollowingALostPacket" {
            val policy = createEnabledPolicy()
            // ECT(1) is known to work on this route.
            policy.onPacketSent(kStartTime, sentAsEct1 = true)
            policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = true)

            // A packet is sent and lost to ordinary loss, then sending pauses for 5s.
            // That is longer than the timeout but says nothing about the path.
            val lastSend = kStartTime + kSendInterval
            policy.onPacketSent(lastSend, sentAsEct1 = true)
            policy.onPacketSent(lastSend + 5.secs, sentAsEct1 = true)
            policy.shouldSendEct1() shouldBe true
        }

        "StopsSendingEct1WhenCongestedAndNoFeedbackArrives" {
            // Without feedback the congestion window fills up and the pacer only sends
            // a packet every kCongestedPacketInterval. Detection must still happen.
            val policy = createEnabledPolicy()
            var now = kStartTime
            var i = 0
            while (i < 10 && policy.shouldSendEct1()) {
                policy.onPacketSent(now, sentAsEct1 = true)
                now += 500.ms
                ++i
            }
            policy.shouldSendEct1() shouldBe false
        }

        "RouteChangeReEnablesEct1AfterEct1PacketsWereDropped" {
            val policy = createPolicyThatDetectedDroppedEct1()
            policy.shouldSendEct1() shouldBe false

            policy.onNetworkRouteChanged()
            policy.shouldSendEct1() shouldBe true
        }

        "RetriesEct1AfterTheRetryDelay" {
            val policy = createPolicyThatDetectedDroppedEct1()
            policy.shouldSendEct1() shouldBe false

            // Sending continues as Not-ECT while ECT(1) is disabled.
            policy.onPacketSent(kDropDetectedTime + kRetryDelay + kSendInterval, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe true
        }

        "DoesNotRetryEct1BeforeTheRetryDelay" {
            val policy = createPolicyThatDetectedDroppedEct1()
            policy.shouldSendEct1() shouldBe false

            policy.onPacketSent(kDropDetectedTime + kRetryDelay - kSendInterval, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe false
        }

        "RetriesEct1OnlyOncePerRoute" {
            val policy = createPolicyThatDetectedDroppedEct1()
            policy.shouldSendEct1() shouldBe false

            val retryTime = kDropDetectedTime + kRetryDelay + kSendInterval
            policy.onPacketSent(retryTime, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe true

            // The retry finds that the packets are still dropped.
            val detectedAgain = retryTime + kTimeoutBeforeAck + kSendInterval
            sendEct1Packets(retryTime, detectedAgain, policy)
            policy.shouldSendEct1() shouldBe false

            policy.onPacketSent(detectedAgain + kRetryDelay + kSendInterval, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe false
        }

        "RouteChangeAllowsANewRetry" {
            val policy = createPolicyThatDetectedDroppedEct1()
            policy.shouldSendEct1() shouldBe false

            policy.onNetworkRouteChanged()
            val start = kDropDetectedTime + kSendInterval
            val detectedAgain = start + kTimeoutBeforeAck + kSendInterval
            sendEct1Packets(start, detectedAgain, policy)
            policy.shouldSendEct1() shouldBe false

            policy.onPacketSent(detectedAgain + kRetryDelay + kSendInterval, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe true
        }

        "DoesNotRetryEct1AfterBleaching" {
            val policy = createEnabledPolicy()
            policy.onPacketsFeedback(hasBleachedEct1 = true, hasPreservedEct1 = true)
            policy.shouldSendEct1() shouldBe false

            policy.onPacketSent(kStartTime + kRetryDelay + kSendInterval, sentAsEct1 = false)
            policy.shouldSendEct1() shouldBe false
        }

        "RouteChangeRestoresTheShortTimeout" {
            val policy = createEnabledPolicy()
            policy.onPacketSent(kStartTime, sentAsEct1 = true)
            policy.onPacketsFeedback(hasBleachedEct1 = false, hasPreservedEct1 = true)

            policy.onNetworkRouteChanged()
            sendEct1Packets(kStartTime, kDropDetectedTime, policy)
            policy.shouldSendEct1() shouldBe false
        }
    }
}
