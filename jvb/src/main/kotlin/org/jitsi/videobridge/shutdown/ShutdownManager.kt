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
import org.jitsi.videobridge.shutdown.ShutdownConfig.Companion.config
import org.jitsi.videobridge.shutdown.ShutdownState.GRACEFUL_SHUTDOWN
import org.jitsi.videobridge.shutdown.ShutdownState.RUNNING
import org.jitsi.videobridge.shutdown.ShutdownState.SHUTTING_DOWN
import org.jitsi.videobridge.util.TaskPools
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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
    private val clock: Clock,
    parentLogger: Logger
) {
    val logger = createChildLogger(parentLogger)

    /**
     * The [clock] time at which graceful shutdown was requested, or null if it has never been requested.
     */
    var shutdownRequestedTime: Instant? = null
        private set

    var state: ShutdownState = RUNNING
        private set

    fun shutdown(graceful: Boolean) {
        logger.info("Received shutdown request, graceful=$graceful")

        if (graceful) {
            if (state == RUNNING) {
                state = GRACEFUL_SHUTDOWN
                shutdownRequestedTime = clock.instant()
                logger.info("Entered graceful shutdown mode")
                TaskPools.SCHEDULED_POOL.schedule(
                    { changeToShuttingDown() },
                    config.gracefulShutdownMaxDuration.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            } else {
                logger.info("Graceful shutdown already initiated mode.")
            }
        } else {
            // Give time for a response to be sent back if this was requested via HTTP/XMPP?
            state = GRACEFUL_SHUTDOWN
            logger.warn("Will shutdown in 1 second.")
            TaskPools.SCHEDULED_POOL.schedule(
                {
                    logger.warn("JVB force shutdown - now")
                    exitProcess(0)
                },
                1, TimeUnit.SECONDS
            )
        }
    }

    private fun changeToShuttingDown() {
        if (state == SHUTTING_DOWN) {
            return
        }

        logger.info("Changing to SHUTTING_DOWN, will shut down in ${config.shuttingDownDelay}")
        state = SHUTTING_DOWN
        TaskPools.SCHEDULED_POOL.schedule(
            { doShutdown() },
            config.shuttingDownDelay.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    fun doShutdown() {
        logger.info("Videobridge is shutting down NOW")
        shutdownService.beginShutdown()
    }
}
