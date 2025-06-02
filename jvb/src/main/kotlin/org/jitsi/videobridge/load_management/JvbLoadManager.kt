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
import org.jitsi.utils.NEVER
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.jvbLastNSingleton
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.util.TaskPools
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

open class JvbLoadManager<T : JvbLoadMeasurement> @JvmOverloads constructor(
    private val jvbLoadThreshold: T,
    private val jvbRecoveryThreshold: T,
    private val loadReducer: JvbLoadReducer?,
    private val clock: Clock = Clock.systemUTC()
) {
    protected val logger = createLogger(minLogLevel = Level.ALL)

    private var lastReducerTime: Instant = NEVER

    private var state: State = State.NOT_OVERLOADED

    private var mostRecentLoadMeasurement: T? = null

    private var loadSamplerTask: ScheduledFuture<*>? = null

    protected fun startSampler(sampler: Runnable) {
        loadSamplerTask = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(sampler, 0, 10, TimeUnit.SECONDS)
    }

    fun stop() {
        loadSamplerTask?.cancel(true)
        loadSamplerTask = null
    }

    fun loadUpdate(loadMeasurement: T) {
        logger.cdebug { "Got a load measurement of $loadMeasurement" }
        mostRecentLoadMeasurement = loadMeasurement
        val now = clock.instant()
        if (loadMeasurement.getLoad() >= jvbLoadThreshold.getLoad()) {
            state = State.OVERLOADED
            loadReducer?.let {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold")
                if (canRunReducer(now)) {
                    logger.info("Running load reducer")
                    it.reduceLoad()
                    lastReducerTime = now
                } else {
                    logger.info(
                        "Load reducer ran at $lastReducerTime, which is within " +
                            "${it.impactTime()} of now, not running reduce"
                    )
                }
            }
        } else {
            state = State.NOT_OVERLOADED
            loadReducer?.let {
                if (loadMeasurement.getLoad() < jvbRecoveryThreshold.getLoad()) {
                    if (canRunReducer(now)) {
                        if (it.recover()) {
                            logger.info(
                                "Recovery ran after a load measurement of $loadMeasurement (which was " +
                                    "below threshold of $jvbRecoveryThreshold) was received"
                            )
                            lastReducerTime = now
                        } else {
                            logger.cdebug { "Recovery had no work to do" }
                        }
                    } else {
                        logger.cdebug {
                            "Load measurement $loadMeasurement is below recovery threshold, but load reducer " +
                                "ran at $lastReducerTime, which is within ${it.impactTime()} of now, " +
                                "not running recover"
                        }
                    }
                }
            }
        }
    }

    fun getCurrentStressLevel(): Double = mostRecentLoadMeasurement?.div(jvbLoadThreshold) ?: 0.0

    fun getStats() = OrderedJsonObject().apply {
        put("state", state.toString())
        put("stress", getCurrentStressLevel().toString())
        put("reducer_enabled", reducerEnabled.toString())
        loadReducer?.let {
            put("reducer", it.getStats())
        }
    }

    private fun canRunReducer(now: Instant): Boolean {
        loadReducer?.let {
            return Duration.between(lastReducerTime, now) >= it.impactTime()
        }
        return false
    }

    enum class State {
        OVERLOADED,
        NOT_OVERLOADED
    }

    companion object {
        val averageParticipantStress: Double by config {
            "videobridge.load-management.average-participant-stress".from(JitsiConfig.newConfig)
        }

        val loadMeasurement: String by config {
            "videobridge.load-management.load-measurements.load-measurement".from(JitsiConfig.newConfig)
        }

        val reducerEnabled: Boolean by config("videobridge.load-management.reducer-enabled".from(JitsiConfig.newConfig))

        const val PACKET_RATE_MEASUREMENT = "packet-rate"
        const val CPU_USAGE_MEASUREMENT = "cpu-usage"

        @JvmStatic
        fun create(videobridge: Videobridge): JvbLoadManager<*> {
            val reducer = if (reducerEnabled) LastNReducer({ videobridge.conferences }, jvbLastNSingleton) else null

            return when (loadMeasurement) {
                PACKET_RATE_MEASUREMENT -> PacketRateLoadManager(
                    PacketRateMeasurement.loadedThreshold,
                    PacketRateMeasurement.recoveryThreshold,
                    reducer,
                    videobridge
                )
                CPU_USAGE_MEASUREMENT -> CpuUsageLoadManager(
                    CpuMeasurement.loadThreshold,
                    CpuMeasurement.recoverThreshold,
                    reducer
                )
                else -> throw IllegalArgumentException(
                    "Invalid configuration for load measurement type: $loadMeasurement"
                )
            }
        }
    }
}

class PacketRateLoadManager(
    loadThreshold: PacketRateMeasurement,
    recoveryThreshold: PacketRateMeasurement,
    loadReducer: JvbLoadReducer?,
    videobridge: Videobridge
) : JvbLoadManager<PacketRateMeasurement>(loadThreshold, recoveryThreshold, loadReducer) {

    init {
        val sampler = PacketRateLoadSampler(videobridge) { loadMeasurement ->
            loadUpdate(loadMeasurement)
            VideobridgeMetrics.stressLevel.set(getCurrentStressLevel())
        }

        startSampler(sampler)
    }
}

class CpuUsageLoadManager(
    loadThreshold: CpuMeasurement,
    recoveryThreshold: CpuMeasurement,
    loadReducer: JvbLoadReducer?
) : JvbLoadManager<CpuMeasurement>(loadThreshold, recoveryThreshold, loadReducer) {
    init {
        val sampler = CpuLoadSampler { loadMeasurement ->
            val stealMeasurement = (if (detectCpuSteal) StealDetection.instance?.update() else null)
                ?: CpuMeasurement(0.0)

            loadUpdate(CpuMeasurement(loadMeasurement.getLoad() + stealMeasurement.getLoad()))
            VideobridgeMetrics.stressLevel.set(getCurrentStressLevel())
        }

        startSampler(sampler)
    }

    companion object {
        val detectCpuSteal: Boolean by config {
            "${JvbLoadMeasurement.CONFIG_BASE}.cpu-usage.detect-cpu-steal"
                .from(JitsiConfig.newConfig)
        }
    }
}
