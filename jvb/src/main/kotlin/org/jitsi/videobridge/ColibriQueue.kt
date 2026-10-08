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
package org.jitsi.videobridge

import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.videobridge.colibri2.createConferenceNotFoundError
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.xmpp.XmppConnection
import java.time.Clock
import java.util.concurrent.RejectedExecutionException
import kotlin.Int.Companion.MAX_VALUE

abstract class ColibriQueue(packetHandler: PacketHandler<XmppConnection.ColibriRequest>) :
    PacketQueue<XmppConnection.ColibriRequest>(
        MAX_VALUE,
        true,
        QUEUE_NAME,
        packetHandler,
        TaskPools.IO_POOL,
        // TODO: using the Videobridge clock breaks tests somehow
        Clock.systemUTC(),
        // Allow running tasks to complete (so we can close the queue from within the task).
        false,
    ) {
    init {
        setErrorHandler(queueErrorCounter)
    }

    /** Whether [close] has been called. [PacketQueue]'s own flag is private. */
    @Volatile
    private var closed = false

    override fun close() {
        closed = true
        super.close()
    }

    /**
     * Called by [PacketQueue] for a request it discards. When the queue is being closed the conference is expiring,
     * so instead of silently discarding the request we answer it with conference_not_found. Jicofo treats that error
     * as a stale session for this one conference and re-invites its participants, whereas an unanswered request
     * times out and marks the whole bridge non-operational (see #2462).
     *
     * [PacketQueue] also calls this when it drops from the head of a full queue. The capacity is unbounded so this
     * does not happen in practice, but if it did the conference would still be alive and conference_not_found
     * would be the wrong answer.
     */
    override fun releasePacket(pkt: XmppConnection.ColibriRequest) {
        if (closed) {
            failConferenceNotFound(pkt)
        }
    }

    companion object {
        val QUEUE_NAME = "colibri-queue"

        /**
         * Respond to [request] with a conference_not_found error because its conference expired before the request
         * could be handled. The callback is invoked on the IO pool so that sending the response does not block the
         * caller (which may be holding a lock while closing the queue).
         */
        @JvmStatic
        fun failConferenceNotFound(request: XmppConnection.ColibriRequest) {
            expiredConferenceRequestsMetric.inc()
            request.totalDelayStats.addDelay(System.currentTimeMillis() - request.receiveTime)
            val iq = request.request
            val response = createConferenceNotFoundError(iq, iq.meetingId)
            try {
                TaskPools.IO_POOL.execute {
                    try {
                        request.callback(response)
                    } catch (t: Throwable) {
                        logger.warn("Failed to send conference_not_found response to colibri request ${iq.stanzaId}", t)
                    }
                }
            } catch (e: RejectedExecutionException) {
                logger.warn("Failed to schedule conference_not_found response to colibri request ${iq.stanzaId}", e)
            }
        }

        private val logger = createLogger()

        val expiredConferenceRequestsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "colibri_queue_expired_conference_requests",
            "Number of Colibri requests answered with conference_not_found because the conference expired " +
                "before they could be handled."
        )

        val droppedPacketsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "colibri_queue_dropped_packets",
            "Number of packets dropped out of the Colibri queue."
        )

        val exceptionsMetric = VideobridgeMetricsContainer.instance.registerCounter(
            "colibri_queue_exceptions",
            "Number of exceptions from the Colibri queue."
        )

        /** Count the number of dropped packets and exceptions. */
        val queueErrorCounter = object : CountingErrorHandler() {
            override fun packetDropped() = super.packetDropped().also {
                droppedPacketsMetric.inc()
                QueueMetrics.droppedPackets.inc()
            }
            override fun packetHandlingFailed(t: Throwable?) = super.packetHandlingFailed(t).also {
                exceptionsMetric.inc()
                QueueMetrics.exceptions.inc()
            }
        }
    }
}
