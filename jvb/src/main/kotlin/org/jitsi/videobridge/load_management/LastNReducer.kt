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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.JvbLastN
import java.lang.Integer.max
import java.time.Duration
import java.util.function.Supplier

/**
 * A [JvbLoadReducer] which samples the amount of currently forwarded endpoints across
 * the entire bridge and sets a bridge-wide last-n value (via [JvbLastN]) to a number
 * less than that (based on [reductionScale]).
 */
class LastNReducer(
    private val conferencesSupplier: Supplier<Collection<Conference>>,
    private val jvbLastN: JvbLastN
) : JvbLoadReducer {
    private val logger = createLogger()

    private val reductionScale: Double by
        config("${JvbLoadReducer.CONFIG_BASE}.last-n.reduction-scale".from(JitsiConfig.newConfig))

    private val recoverScale: Double by
        config("${JvbLoadReducer.CONFIG_BASE}.last-n.recover-scale".from(JitsiConfig.newConfig))

    private val impactTime: Duration by
        config("${JvbLoadReducer.CONFIG_BASE}.last-n.impact-time".from(JitsiConfig.newConfig))

    private val minLastN: Int by
        config("${JvbLoadReducer.CONFIG_BASE}.last-n.minimum-last-n-value".from(JitsiConfig.newConfig))

    private val maxEnforcedLastN: Int by
        config("${JvbLoadReducer.CONFIG_BASE}.last-n.maximum-enforced-last-n-value".from(JitsiConfig.newConfig))

    init {
        logger.cinfo {
            "Created with configuration:\n" +
                "reductionScale: $reductionScale\n" +
                "recoverScale: $recoverScale\n" +
                "impactTime: $impactTime\n" +
                "minLastN: $minLastN\n" +
                "maxEnforcedLastN: $maxEnforcedLastN"
        }
    }

    private fun getMaxForwardedEps(): Int? {
        return conferencesSupplier.get()
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

        val newLastN = max(minLastN, (maxForwardedEps * reductionScale).toInt())
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
        if (newLastN >= maxEnforcedLastN) {
            logger.info("JVB last-n was $currLastN, increasing to $newLastN which is beyond the max enforced value" +
                "of $maxEnforcedLastN, removing limit completely")
                jvbLastN.jvbLastN = -1
        } else {
            logger.info("JVB last-n was $currLastN, increasing to $newLastN as part of load recovery")
            jvbLastN.jvbLastN = newLastN
        }
    }

    override fun impactTime(): Duration = impactTime

    override fun getStats() = OrderedJsonObject().apply {
        put("jvbLastN", jvbLastN.jvbLastN)
    }

    override fun toString(): String = "LastNReducer with scale $reductionScale"
}
