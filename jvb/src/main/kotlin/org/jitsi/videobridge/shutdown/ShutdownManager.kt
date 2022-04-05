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
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.shutdown.ShutdownState.GRACEFUL_SHUTDOWN
import org.jitsi.videobridge.shutdown.ShutdownState.RUNNING
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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
            } else {
                logger.info("Already in graceful shutdown mode.")
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

    fun doShutdown() {
        logger.info("Videobridge is shutting down NOW")
        shutdownService.beginShutdown()
    }
}
