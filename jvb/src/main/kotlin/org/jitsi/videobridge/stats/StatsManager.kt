/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats

import org.jitsi.utils.concurrent.PeriodicRunnableWithObject
import org.jitsi.utils.concurrent.RecurringRunnableExecutor
import org.jitsi.videobridge.stats.config.StatsManagerConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A class that manages the statistics. Periodically calls the
 * passes them to <tt>StatsTransport</tt> instances that sends them.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
class StatsManager(statistics: Statistics) {
    /**
     * The periodic runnable which will gather statistics.
     */
    private val statisticsRunnable: StatisticsPeriodicRunnable

    /**
     * The [RecurringRunnableExecutor] which periodically invokes [statisticsRunnable].
     */
    private val statisticsExecutor = RecurringRunnableExecutor(
        StatsManager::class.java.simpleName + "-statisticsExecutor"
    )

    /**
     * The [RecurringRunnableExecutor] which periodically invokes [transportRunnables].
     */
    private val transportExecutor = RecurringRunnableExecutor(
        StatsManager::class.java.simpleName + "-transportExecutor"
    )

    /**
     * The [StatsTransport]s added to this [StatsManager].
     */
    private val transportRunnables: MutableList<TransportPeriodicRunnable> = CopyOnWriteArrayList()

    /**
     * Gets the [StatsTransport]s through which this [StatsManager] periodically sends the statistics that it
     * generates/updates.
     */
    val transports: Collection<StatsTransport>
        get() = transportRunnables.map { it.o }

    private val running = AtomicBoolean()

    /**
     * Adds a specific <tt>StatsTransport</tt> through which this [StatsManager] is to periodically send statistics.
     *
     * @param transport the [StatsTransport] to add
     * @param period the internal/period in milliseconds at which this [StatsManager] is to repeatedly send statistics
     * to the specified [transport].
     */
    fun addTransport(transport: StatsTransport, period: Long) {
        require(period >= 1) { "period $period" }

        TransportPeriodicRunnable(transport, period).also {
            transportRunnables.add(it)
            if (running.get()) {
                transportExecutor.registerRecurringRunnable(it)
            }
        }
    }

    /**
     * The [Statistics] which this [StatsManager] periodically generates/updates.
     */
    val statistics: Statistics
        get() = statisticsRunnable.o

    /**
     * {@inheritDoc}
     *
     * Starts the [StatsTransport]s added to this [StatsManager]. Commences the generation of [Statistics].
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            // Register statistics and transports with their respective RecurringRunnableExecutor in order to have them
            // periodically executed.
            statisticsExecutor.registerRecurringRunnable(statisticsRunnable)
            transportRunnables.forEach { transportExecutor.registerRecurringRunnable(it) }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Stops the [StatsTransport]s added to this [StatsManager] and the [StatisticsPeriodicRunnable].
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            // De-register statistics and transports from their respective
            // RecurringRunnableExecutor in order to have them no longer
            // periodically executed.
            statisticsExecutor.deRegisterRecurringRunnable(statisticsRunnable)
            // Stop the StatTransports added to this StatsManager
            transportRunnables.forEach { transportExecutor.deRegisterRecurringRunnable(it) }
        }
    }

    /**
     * Implements a [RecurringRunnable] which periodically generates a specific [Statistics].
     */
    private class StatisticsPeriodicRunnable(statistics: Statistics, period: Long) :
        PeriodicRunnableWithObject<Statistics>(statistics, period) {

        override fun doRun() {
            o.generate()
        }
    }

    /**
     * Implements a [RecurringRunnable] which periodically publishes statistics through a specific [StatsTransport].
     */
    private inner class TransportPeriodicRunnable(transport: StatsTransport, period: Long) :
        PeriodicRunnableWithObject<StatsTransport>(transport, period) {

        override fun doRun() {
            // FIXME measurementInterval was meant to be the actual interval of time that the information of the
            //  Statistics covers. However, it became difficult after a refactoring to calculate measurementInterval.
            val measurementInterval = period
            o.publishStatistics(statisticsRunnable.o, measurementInterval)
        }
    }

    companion object {
        @JvmField
        val config = StatsManagerConfig()
    }

    init {
        val period = config.interval.toMillis()
        require(period >= 1) { "period $period" }
        statisticsRunnable = StatisticsPeriodicRunnable(statistics, period)
    }
}