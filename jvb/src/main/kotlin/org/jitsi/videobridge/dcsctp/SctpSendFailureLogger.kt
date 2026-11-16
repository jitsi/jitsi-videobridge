/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.videobridge.dcsctp

import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.utils.logging2.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

/**
 * Logs failed SCTP sends for one association (of an endpoint or a relay), at most once per [interval] for a status
 * that was already logged.
 *
 * When the remote side stops acknowledging (for example, its network is gone) the send queue fills and stays full,
 * and every message the bridge sends to it fails until the endpoint or relay expires. That can be many failures per
 * second. So a status is logged right away only the first time it occurs. After that, at most one line is logged per
 * [interval], with the number of failures by status since the previous line.
 */
class SctpSendFailureLogger(
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = DEFAULT_INTERVAL
) {
    private val lock = Any()

    /** When the last line was logged, or null if nothing was logged yet. */
    private var lastLogged: Instant? = null

    /** The statuses that were logged at least once. */
    private val loggedStatuses = mutableSetOf<String>()

    /** The number of failures by status since the last line was logged. */
    private val notLogged = TreeMap<String, Long>()

    /**
     * Records a failed send.
     * @param status the status returned by the transport, or null if there was no transport to send through.
     */
    fun sendFailed(status: SendStatus?) {
        val key = status?.name ?: "no transport"
        synchronized(lock) {
            val now = clock.instant()
            val last = lastLogged
            if (last == null || key !in loggedStatuses || Duration.between(last, now) >= interval) {
                val message = StringBuilder("Error sending to SCTP: $key.")
                if (notLogged.isNotEmpty()) {
                    message.append(" Errors not logged since $last: $notLogged.")
                }
                if (last == null) {
                    message.append(" Further errors are logged at most once every ${interval.seconds} s.")
                }
                logger.warn(message.toString())
                loggedStatuses.add(key)
                lastLogged = now
                notLogged.clear()
            } else {
                notLogged.merge(key, 1L, Long::plus)
            }
        }
    }

    /** Logs the failures that were not logged yet, if any. Call it when the association closes. */
    fun flush() {
        synchronized(lock) {
            if (notLogged.isNotEmpty()) {
                logger.warn("SCTP send errors not logged since $lastLogged: $notLogged.")
                notLogged.clear()
            }
        }
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
