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
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

class JvbLoadManager<T : JvbLoadMeasurement> @JvmOverloads constructor(
    private val jvbLoadThreshold: T,
    private val loadReducer: JvbLoadReducer,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createLogger(minLogLevel = Level.ALL)

    private var lastReducerTime: Instant = NEVER

    fun loadUpdate(loadMeasurement: T) {
        logger.cdebug { "Got a load measurement of $loadMeasurement" }
        if (loadMeasurement.getLoad() >= jvbLoadThreshold.getLoad()) {
            if (Duration.between(lastReducerTime, clock.instant()) >= loadReducer.impactTime()) {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                        "running load reducer")
                loadReducer.reduceLoad()
                lastReducerTime = clock.instant()
            } else {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                        "but load reducer started running ${Duration.between(lastReducerTime, clock.instant())} " +
                        "ago, and we wait ${loadReducer.impactTime()} between runs")
            }
        }
    }
}

interface JvbLoadMeasurement {
    fun getLoad(): Double
}

class RtpPacketDelayMeasurement(private val rtpDelay: Double) : JvbLoadMeasurement {
    override fun getLoad(): Double = rtpDelay

    override fun toString(): String = "RTP packet delay of $rtpDelay ms"
}

val RtpPacketDelayThreshold = RtpPacketDelayMeasurement(30.0)

interface JvbLoadReducer {
    fun reduceLoad()
    fun impactTime(): Duration
}

class LastNReducer(
    private val videobridge: Videobridge,
    private val reductionScale: Double
) : JvbLoadReducer {
    private val logger = createLogger()
    override fun reduceLoad() {
        // Find the highest number of endpoints sending video in a single conference: we need
        // to set the last-n value to a size smaller than that
        val largestConfSize = videobridge.conferences
            .map { conf -> conf.endpoints.count(AbstractEndpoint::isSendingVideo) }
            .max() ?: run {
                    logger.info("No active conferences, can't reduce load by reducing last n")
                    return
                }

        val newLastN = (largestConfSize * reductionScale).toInt()
        logger.info("Largest conf size was $largestConfSize, A last-n value of $newLastN is being enforced to " +
                "reduce bridge load")

        videobridge.conferences.forEach { conf ->
            conf.endpoints.forEach { ep ->
                (ep as? Endpoint)?.addLastNRequest(LastNRequest(newLastN, LastNSource.JVB))
            }
        }
    }

    override fun impactTime(): Duration = Duration.ofMinutes(1)

    override fun toString(): String = "LastNReducer with scale $reductionScale"
}
