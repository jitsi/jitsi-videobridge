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

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

// How long ECT(1) packets must be sent without any feedback arriving before
// assuming the path drops them. The timeout is short until an ECT(1) packet
// has been acknowledged, so that a path that drops them from the start is
// detected quickly. Once the path is known to deliver them, the timeout
// matches the ICE unwritable timeout, so that an outage affecting all packets
// has made ICE look for another route before the marking is blamed for it.
private val kEct1FeedbackTimeoutBeforeAck = 500.ms
private val kEct1FeedbackTimeoutAfterAck = 5.secs

// The most a single gap between two sent packets can contribute to the time
// spent sending, so that idle time does not count toward the timeout. A sender
// that sends rarely, such as a screencast of static content without audio,
// therefore needs longer to reach the timeout. That delays detection but can
// not cause a false one.
//
// Resuming after a pause can then never by itself trigger detection: this is
// less than kEct1FeedbackTimeoutBeforeAck.
private val kMaxCountedSendGap = 200.ms

// Sending ECT(1) is retried once per route this long after the path was judged
// to drop the marking. A round trip time longer than the timeout used before
// the first acknowledgement makes that judgement wrong, and the round trip
// time can also improve during a call.
private val kEct1RetryDelay = 10.secs

/** ECT(1) policy,
 * based on WebRTC modules/congestion_controller/ect1_policy.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Decides if RTP packets should be sent with an ECT(1) marking, for example to
 * support L4S as described in RFC 9331.
 *
 * ECT(1) is used if the negotiated feedback format reports the ECN marking of
 * received packets and the congestion controller adapts to ECN. It is disabled
 * until the network route changes if the network path bleaches the marking to
 * Not-ECT, or if feedback stops arriving while packets are sent as ECT(1). The
 * latter can happen when feedback is lost due to temporary network issues, so
 * it is retried once per route.
 *
 * A path that drops ECT(1) from the start is detected quickly, since no
 * feedback has been received on the route yet. Once ECT(1) is known to work,
 * a much longer time without feedback is needed, so that a temporary outage
 * is not mistaken for the same thing. A working path can start dropping the
 * marking mid call, since traffic can be rerouted over a different middlebox
 * without the ICE candidate pair changing.
 */
class Ect1Policy(parentLogger: Logger) {
    private val logger = createChildLogger(parentLogger)

    // Whether the remote peer reports the ECN marking of received packets back to
    // the sender, for example with the RTCP feedback message in RFC 8888.
    fun setFeedbackSupportsEcn(supportsEcn: Boolean) {
        feedbackSupportsEcn = supportsEcn
        logIfDecisionChanged()
    }

    // Whether the congestion controller reacts to reported CE markings.
    fun setCongestionControllerSupportsEcn(supportsEcn: Boolean) {
        congestionControllerSupportsEcn = supportsEcn
        logIfDecisionChanged()
    }

    fun onNetworkRouteChanged() {
        // The new path may handle the ECT(1) marking even if the old one did not.
        routeBleachesEct1 = false
        ect1DropDetectedTime = null
        ect1DropRetried = false
        ect1PreservedOnRoute = false
        timeSendingEct1WithoutFeedback = Duration.ZERO
        logIfDecisionChanged()
    }

    // `has_bleached_ect1` is true if a feedback report showed at least one
    // packet that was sent as ECT(1) arriving as Not-ECT. `has_preserved_ect1`
    // is true if it showed at least one arriving still ECN capable. Both are
    // false if the report said nothing about the marking, which is the case
    // until the first packet sent as ECT(1) is reported received.
    fun onPacketsFeedback(hasBleachedEct1: Boolean, hasPreservedEct1: Boolean) {
        // Feedback of any kind proves that packets reach the receiver, so the path
        // is not dropping them and the time spent sending starts over.
        timeSendingEct1WithoutFeedback = Duration.ZERO
        if (hasPreservedEct1) {
            ect1PreservedOnRoute = true
        }
        if (hasBleachedEct1 && !routeBleachesEct1) {
            routeBleachesEct1 = true
            logger.info("Transport does not preserve the ECT(1) marking. Stop sending ECT(1) on this route.")
        }
        logIfDecisionChanged()
    }

    // Must be called for every sent RTP packet. `sent_as_ect1` is whether the
    // packet actually carried the marking.
    fun onPacketSent(sendTime: Instant, sentAsEct1: Boolean) {
        val dropDetectedTime = ect1DropDetectedTime
        if (dropDetectedTime != null && !ect1DropRetried &&
            Duration.between(dropDetectedTime, sendTime) > kEct1RetryDelay
        ) {
            ect1DropRetried = true
            // Clearing the drop detection time makes ShouldSendEct1() true again.
            ect1DropDetectedTime = null
            timeSendingEct1WithoutFeedback = Duration.ZERO
            logger.info("Retrying ECT(1) on this route.")
        }

        if (sentAsEct1) {
            lastSendTime?.let {
                timeSendingEct1WithoutFeedback += min(Duration.between(it, sendTime), kMaxCountedSendGap)
            }
            val timeout = ect1FeedbackTimeout()
            if (ect1DropDetectedTime == null && timeSendingEct1WithoutFeedback > timeout) {
                ect1DropDetectedTime = sendTime
                logger.info(
                    "No feedback while sending ECT(1) packets during $timeout. Assuming the transport drops " +
                        "them. Stop sending ECT(1) on this route."
                )
            }
        }
        lastSendTime = sendTime

        logIfDecisionChanged()
    }

    fun shouldSendEct1(): Boolean = feedbackSupportsEcn && congestionControllerSupportsEcn &&
        !routeBleachesEct1 && ect1DropDetectedTime == null

    private fun logIfDecisionChanged() {
        val sendEct1 = shouldSendEct1()
        if (sendEct1 == loggedSendEct1) {
            return
        }
        loggedSendEct1 = sendEct1
        logger.info("Sending packets as ${if (sendEct1) "ECT(1)." else "Not-ECT."}")
    }

    // How long to wait for feedback before assuming ECT(1) packets are dropped.
    private fun ect1FeedbackTimeout(): Duration =
        if (ect1PreservedOnRoute) kEct1FeedbackTimeoutAfterAck else kEct1FeedbackTimeoutBeforeAck

    private var feedbackSupportsEcn = false
    private var congestionControllerSupportsEcn = false
    private var routeBleachesEct1 = false

    // When the route was judged to drop ECT(1) packets. Unset if it was not.
    private var ect1DropDetectedTime: Instant? = null

    // Whether the single retry allowed on this route has been used.
    private var ect1DropRetried = false
    private var ect1PreservedOnRoute = false

    // Does not count time when nothing was sent.
    private var timeSendingEct1WithoutFeedback: Duration = Duration.ZERO

    // Send time of the last RTP packet, whatever its marking.
    private var lastSendTime: Instant? = null

    private var loggedSendEct1 = false
}
