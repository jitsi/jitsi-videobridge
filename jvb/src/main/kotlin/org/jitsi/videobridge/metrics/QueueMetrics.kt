/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.videobridge.metrics

import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer.Companion.instance as metricsContainer

object QueueMetrics {
    private val rtpReceiverDroppedPackets = metricsContainer.registerCounter(
        "rtp_receiver_dropped_packets",
        "Number of packets dropped out of the RTP receiver queue."
    )
    private val rtpReceiverExceptions = metricsContainer.registerCounter(
        "rtp_receiver_exceptions",
        "Number of exceptions from the RTP receiver queue."
    )
    private val rtpSenderDroppedPackets = metricsContainer.registerCounter(
        "rtp_sender_dropped_packets",
        "Number of packets dropped out of the RTP sender queue."
    )
    private val rtpSenderExceptions = metricsContainer.registerCounter(
        "rtp_sender_exceptions",
        "Number of exceptions from the RTP sender queue."
    )

    val droppedPackets = metricsContainer.registerCounter(
        "queue_dropped_packets",
        "Number of packets dropped from any of the queues."
    )
    val exceptions = metricsContainer.registerCounter(
        "queue_exceptions",
        "Number of exceptions from any of the queues."
    )

    fun init() {
        RtpReceiverImpl.queueErrorCounter = object : CountingErrorHandler() {
            override fun packetDropped() = super.packetDropped().also {
                rtpReceiverDroppedPackets.inc()
                droppedPackets.inc()
            }
            override fun packetHandlingFailed(t: Throwable?) = super.packetHandlingFailed(t).also {
                rtpReceiverExceptions.inc()
                exceptions.inc()
            }
        }

        RtpSenderImpl.queueErrorCounter = object : CountingErrorHandler() {
            override fun packetDropped() = super.packetDropped().also {
                rtpSenderDroppedPackets.inc()
                droppedPackets.inc()
            }
            override fun packetHandlingFailed(t: Throwable?) = super.packetHandlingFailed(t).also {
                rtpSenderExceptions.inc()
                exceptions.inc()
            }
        }
    }
}
