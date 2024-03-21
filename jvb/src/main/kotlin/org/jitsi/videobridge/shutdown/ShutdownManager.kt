/*
 * Copyright @ 2022 - Present, 8x8 Inc
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
package org.jitsi.videobridge.shutdown

import org.jitsi.meet.ShutdownService
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.shutdown.ShutdownConfig.Companion.config
import org.jitsi.videobridge.shutdown.ShutdownState.GRACEFUL_SHUTDOWN
import org.jitsi.videobridge.shutdown.ShutdownState.RUNNING
import org.jitsi.videobridge.shutdown.ShutdownState.SHUTTING_DOWN
import org.jitsi.videobridge.util.TaskPools
import java.util.concurrent.TimeUnit

/**
 * Implements the graceful shutdown logic.
 *
 * When graceful shutdown is initiated the state changes to [GRACEFUL_SHUTDOWN], and this is advertised in presence.
 * In this mode new conferences are rejected, but existing conferences continue to run and accept new participants. We
 * stay in this state until either [config.gracefulShutdownMaxDuration] has passed, or the number of participants
 * drops below [config.gracefulShutdownMinParticipants].
 *
 * Then the state transitions to [SHUTTING_DOWN], and this state is advertised in presence. We expect jicofo to
 * actively move participants off this bridge. We stay in [SHUTTING_DOWN] for [config.shuttingDownDelay] before we
 * actually perform the shutdown.
 */
class ShutdownManager(
    private val shutdownService: ShutdownService,
    parentLogger: Logger
) {
    val logger = createChildLogger(parentLogger)

    var state: ShutdownState = RUNNING
        private set

    fun initiateShutdown(graceful: Boolean) {
        logger.info("Received shutdown request, graceful=$graceful")

        if (graceful) {
            if (state == RUNNING) {
                state = GRACEFUL_SHUTDOWN
                VideobridgeMetrics.gracefulShutdown.set(true)
                logger.info(
                    "Entered graceful shutdown mode, will stay in this mode for up to " +
                        config.gracefulShutdownMaxDuration
                )
                TaskPools.SCHEDULED_POOL.schedule(
                    {
                        if (state == GRACEFUL_SHUTDOWN) {
                            logger.info("Graceful shutdown period expired, changing to SHUTTING_DOWN.")
                            changeToShuttingDown()
                        }
                    },
                    config.gracefulShutdownMaxDuration.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            } else {
                logger.info("Graceful shutdown already initiated mode.")
            }
        } else {
            changeToShuttingDown()
        }
    }

    /**
     * Change to SHUTTING_DOWN if the number of endpoints has dropped below the threshold.
     */
    fun maybeShutdown(endpointCount: Long) {
        // If we're RUNNING we don't want to shutdown. If we're SHUTTING_DOWN we've already done this.
        if (state == GRACEFUL_SHUTDOWN) {
            if (endpointCount <= config.gracefulShutdownMinParticipants) {
                logger.info("Entering SHUTTING_DOWN with $endpointCount participants.")
                changeToShuttingDown()
            }
        }
    }

    private fun changeToShuttingDown() {
        if (state == SHUTTING_DOWN) {
            return
        }

        logger.info("Will shut down in ${config.shuttingDownDelay}")
        state = SHUTTING_DOWN
        VideobridgeMetrics.shuttingDown.set(true)
        TaskPools.SCHEDULED_POOL.schedule(
            {
                logger.info("Videobridge is shutting down NOW")
                shutdownService.beginShutdown()
            },
            config.shuttingDownDelay.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }
}
