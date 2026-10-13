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

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.videobridge.colibri2.createConferenceNotFoundError
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.xmpp.util.createError
import org.jivesoftware.smack.packet.StanzaError
import java.time.Clock
import java.util.concurrent.RejectedExecutionException
import kotlin.Int.Companion.MAX_VALUE

abstract class ColibriQueue(
    parentLogger: Logger,
    packetHandler: PacketHandler<XmppConnection.ColibriRequest>
) : PacketQueue<XmppConnection.ColibriRequest>(
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
    private val logger = createChildLogger(parentLogger)

    init {
        setErrorHandler(queueErrorCounter)
    }

    /**
     * Whether [close] has been called. [PacketQueue]'s own flag is private; this can be replaced with
     * `isClosed()` once jitsi-utils#167 is in.
     */
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
        val respond: Runnable = if (closed) {
            Runnable { failConferenceNotFound(pkt, logger) }
        } else {
            // Dropped from the head of a full queue. The capacity is unbounded so this should not happen, but every
            // accepted request must be answered.
            logger.error("Colibri request ${pkt.request.stanzaId} dropped from a full queue.")
            Runnable {
                pkt.callback(
                    createError(pkt.request, StanzaError.Condition.internal_server_error, "Colibri queue full")
                )
            }
        }
        // We are called from close() or add(), which run under locks (Conference's queue lock, and PacketQueue's),
        // so send the response from another thread.
        try {
            TaskPools.IO_POOL.execute(respond)
        } catch (e: RejectedExecutionException) {
            logger.warn("Failed to schedule error response to colibri request ${pkt.request.stanzaId}", e)
        }
    }

    companion object {
        val QUEUE_NAME = "colibri-queue"

        /**
         * Respond to [request] with a conference_not_found error because its conference expired before the request
         * could be handled. The response is sent on the calling thread.
         */
        @JvmStatic
        fun failConferenceNotFound(request: XmppConnection.ColibriRequest, logger: Logger) {
            val iq = request.request
            logger.warn(
                "Conference expired before colibri request ${iq.stanzaId} could be handled, responding with " +
                    "conference_not_found."
            )
            expiredConferenceRequestsMetric.inc()
            request.totalDelayStats.addDelay(System.currentTimeMillis() - request.receiveTime)
            try {
                request.callback(createConferenceNotFoundError(iq, iq.meetingId))
            } catch (t: Throwable) {
                logger.warn("Failed to send conference_not_found response to colibri request ${iq.stanzaId}", t)
            }
        }

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
