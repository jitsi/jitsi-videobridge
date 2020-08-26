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

import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.JvbLastN
import org.jitsi.videobridge.Videobridge
import java.time.Duration

class LastNReducer @JvmOverloads constructor(
    private val videobridge: Videobridge,
    private val jvbLastN: JvbLastN,
    private val reductionScale: Double,
    private val recoverScale: Double = 1 / reductionScale
) : JvbLoadReducer {
    private val logger = createLogger()

    private fun getMaxForwardedEps(): Int? {
        return videobridge.conferences
            .flatMap { it.endpoints }
            .asSequence()
            .filterIsInstance<Endpoint>()
            .map {
                it.numForwardedEndpoints()
            }
            .max()
    }

    override fun reduceLoad() {
        // Find the highest amount of endpoints any endpoint on this bridge is forwarding video for
        // so we can set a new last-n number to something lower
        val maxForwardedEps = getMaxForwardedEps() ?: run {
            logger.info("No endpoints with video being forwarded, can't reduce load by reducing last n")
            return
        }

        val newLastN = (maxForwardedEps * reductionScale).toInt()
        logger.info("Largest number of forwarded videos was $maxForwardedEps, A last-n value of $newLastN is " +
                "being enforced to reduce bridge load")

        jvbLastN.jvbLastN = newLastN
    }

    override fun recover() {
        val currLastN = jvbLastN.jvbLastN
        if (currLastN == -1) {
            logger.cdebug { "No recovery necessary, no JVB last-n is set" }
            return
        }
        val newLastN = (currLastN * recoverScale).toInt()
        logger.info("JVB last-n was $currLastN, increasing to $newLastN as part of load recovery")
        jvbLastN.jvbLastN = newLastN
    }

    override fun impactTime(): Duration = Duration.ofMinutes(1)

    override fun toString(): String = "LastNReducer with scale $reductionScale"
}
