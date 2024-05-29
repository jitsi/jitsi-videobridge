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

import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.videobridge.metrics.QueueMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.xmpp.XmppConnection
import java.time.Clock
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

    companion object {
        val QUEUE_NAME = "colibri-queue"

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
