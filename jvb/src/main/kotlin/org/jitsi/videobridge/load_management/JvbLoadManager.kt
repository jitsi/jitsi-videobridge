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

package org.jitsi.videobridge.load_management

import org.jitsi.nlj.util.NEVER
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

class JvbLoadManager<T : JvbLoadMeasurement> @JvmOverloads constructor(
    private val jvbLoadThreshold: T,
    private val jvbRecoveryThreshold: T,
    private val loadReducer: JvbLoadReducer,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createLogger(minLogLevel = Level.ALL)

    private var lastReducerTime: Instant = NEVER

    fun loadUpdate(loadMeasurement: T) {
        logger.cdebug { "Got a load measurement of $loadMeasurement" }
        if (loadMeasurement.getLoad() >= jvbLoadThreshold.getLoad()) {
            logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                    "maybe running load reducer")
            maybeRun("load reducer") { reduceLoad() }
        } else if (loadMeasurement.getLoad() < jvbRecoveryThreshold.getLoad()) {
            logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                    "maybe running recovery")
            maybeRun("recovery") { recover() }
        }
    }

    private fun maybeRun(taskDescription: String, task: JvbLoadReducer.() -> Unit) {
        if (Duration.between(lastReducerTime, clock.instant()) >= loadReducer.impactTime()) {
            logger.info("Running $taskDescription")
            loadReducer.apply(task)
            lastReducerTime = clock.instant()
        } else {
            logger.info("Load reducer started running ${Duration.between(lastReducerTime, clock.instant())} " +
                    "ago, and we wait ${loadReducer.impactTime()} between runs, so will skip running $taskDescription")
        }
    }
}
