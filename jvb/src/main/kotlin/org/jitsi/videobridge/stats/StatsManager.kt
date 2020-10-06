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

    /**
     * Adds a specific <tt>StatsTransport</tt> through which this [StatsManager] is to periodically send statistics.
     *
     * Warning: `StatsTransport`s added to this `StatsManager` after [start] has been invoked will not be called.
     *
     * @param transport the [StatsTransport] to add
     * @param period the internal/period in milliseconds at which this [StatsManager] is to repeatedly send statistics
     * to the specified [transport].
     */
    fun addTransport(transport: StatsTransport, period: Long) {
        require(period >= 1) { "period $period" }

        // XXX The field transport is a CopyOnWriteArrayList in order to avoid synchronization here.
        transportRunnables.add(TransportPeriodicRunnable(transport, period))
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
     *
     * Warning: `StatsTransport`s added by way of [addTransport] after the current method invocation will not be started.
     *
     */
    fun start() {
        // Register statistics and transports with their respective RecurringRunnableExecutor in order to have them
        // periodically executed.
        statisticsExecutor.registerRecurringRunnable(statisticsRunnable)
        for (tpp in transportRunnables) {
            transportExecutor.registerRecurringRunnable(tpp)
        }
    }

    /**
     * {@inheritDoc}
     *
     * Stops the [StatsTransport]s added to this [StatsManager] and the [StatisticsPeriodicRunnable].
     */
    fun stop() {
        // De-register statistics and transports from their respective
        // RecurringRunnableExecutor in order to have them no longer
        // periodically executed.
        statisticsExecutor.deRegisterRecurringRunnable(statisticsRunnable)
        // Stop the StatTransports added to this StatsManager
        for (tpp in transportRunnables) {
            transportExecutor.deRegisterRecurringRunnable(tpp)
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