/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.nlj.util.NEVER
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.message.EndpointConnectionStatusMessage
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EndpointConnectionStatusMonitor @JvmOverloads constructor(
    private val conference: Conference,
    private val executor: ScheduledExecutorService,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createChildLogger(parentLogger)

    private val config = EndpointConnectionStatusConfig()

    /**
     * Note that we intentionally do not prune this set when an endpoint expires, because if an endpoint expires and
     * is recreated, we need to send an "active" message (the other endpoints in the conference are not aware that the
     * object on the bridge was expired and recreated).
     * Also note that when an endpoint is moved to another bridge, it will be expired and an OctoEndpoint with the same
     * ID will be created.
     */
    private val inactiveEndpointIds = mutableSetOf<String>()

    private val taskHandle = AtomicReference<ScheduledFuture<*>>(null)

    fun start() {
        if (taskHandle.compareAndSet(
                null,
                executor.scheduleWithFixedDelay(::run, config.intervalMs, config.intervalMs, TimeUnit.MILLISECONDS)
            )
        ) {
            logger.info("Starting connection status monitor")
        } else {
            logger.warn("Task already started, not starting again")
        }
    }

    fun stop() {
        taskHandle.getAndSet(null)?.cancel(false)
        logger.info("Stopped")
    }

    private fun run() {
        conference.localEndpoints.forEach(::monitorEndpointActivity)
    }

    private fun monitorEndpointActivity(endpoint: Endpoint) {
        val now = clock.instant()
        val mostRecentChannelCreatedTime = endpoint.getMostRecentChannelCreatedTime()
        val lastActivity = endpoint.lastIncomingActivity

        val active: Boolean
        var changed = false
        if (lastActivity == NEVER) {
            // Here we check if it's taking too long for the endpoint to connect
            // We're doing that by checking how much time has elapsed since
            // the first endpoint's channel has been created.
            val timeSinceCreation = Duration.between(mostRecentChannelCreatedTime, now)
            if (timeSinceCreation > config.firstTransferTimeout) {
                active = false
                synchronized(inactiveEndpointIds) {
                    val alreadyInactive = inactiveEndpointIds.contains(endpoint.id)
                    if (!alreadyInactive) {
                        logger.cdebug {
                            "${endpoint.id} is having trouble establishing the connection " +
                                "and will be marked as inactive"
                        }
                        inactiveEndpointIds += endpoint.id
                        changed = true
                    }
                }
            } else {
                logger.cdebug { "${endpoint.id} not ready for activity checks yet" }
                return
            }
        } else {
            val noActivityTime = Duration.between(lastActivity, now)
            active = noActivityTime <= config.maxInactivityLimit
            synchronized(inactiveEndpointIds) {
                val wasActive = !inactiveEndpointIds.contains(endpoint.id)
                if (wasActive && !active) {
                    logger.cdebug { "${endpoint.id} is considered disconnected.  No activity for $noActivityTime" }
                    inactiveEndpointIds += endpoint.id
                    changed = true
                } else if (!wasActive && active) {
                    logger.cdebug { "${endpoint.id} has reconnected" }
                    inactiveEndpointIds -= endpoint.id
                    changed = true
                }
            }
        }

        if (changed) {
            notifyStatusChange(endpoint.id, active, null)
        }
    }

    private fun notifyStatusChange(subjectEpId: String, isConnected: Boolean, receiverEpId: String?) {
        val msg = EndpointConnectionStatusMessage(subjectEpId, isConnected)

        if (receiverEpId == null) {
            // We broadcast the message also to the endpoint itself for
            // debugging purposes, and we also broadcast it through Octo.
            conference.broadcastMessage(msg, true)
        } else {
            val ep = conference.getLocalEndpoint(receiverEpId)
            if (ep != null) {
                conference.sendMessage(msg, listOf(ep), false)
            } else {
                /* TODO: send only to the relevant relays */
                conference.broadcastMessage(msg, true)
            }
        }
    }

    /**
     * Notify this [EndpointConnectionStatusMonitor] that an endpoint in the conference has
     * connected
     */
    fun endpointConnected(endpointId: String) {
        synchronized(inactiveEndpointIds) {
            val localEndpointIds = conference.localEndpoints.map { it.id }
            inactiveEndpointIds.forEach { inactiveEpId ->
                // inactiveEndpointIds may contain endpoints that have already expired and/or moved to another bridge.
                if (localEndpointIds.contains(inactiveEpId)) {
                    notifyStatusChange(inactiveEpId, isConnected = false, receiverEpId = endpointId)
                }
            }
        }
    }
}
