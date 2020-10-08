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
 * [StatsCollector] periodically collects statistics by calling [Statistics.generate] on [statistics], and periodically
 * pushes the latest collected statistics to the [StatsTransport]s that have been added to it.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
class StatsCollector(
    /**
     * The instance which can collect statistics via [Statistics.generate]. The [StatsCollector] invokes this
     * periodically.
     */
    val statistics: Statistics
) {
    /**
     * The [RecurringRunnableExecutor] which periodically invokes [statisticsRunnable].
     */
    private val statisticsExecutor = RecurringRunnableExecutor(
        StatsCollector::class.java.simpleName + "-statisticsExecutor"
    )

    /**
     * The [RecurringRunnableExecutor] which periodically invokes [transportRunnables].
     */
    private val transportExecutor = RecurringRunnableExecutor(
        StatsCollector::class.java.simpleName + "-transportExecutor"
    )

    /**
     * The periodic runnable which collects statistics by invoking `statistics.generate()`.
     */
    private val statisticsRunnable: StatisticsPeriodicRunnable

    /**
     * The runnables which periodically push statistics to the [StatsTransport]s that have been added.
     */
    private val transportRunnables: MutableList<TransportPeriodicRunnable> = CopyOnWriteArrayList()

    /**
     * Gets the [StatsTransport]s through which this [StatsCollector] periodically sends the statistics that it
     * collects.
     */
    val transports: Collection<StatsTransport>
        get() = transportRunnables.map { it.o }

    private val running = AtomicBoolean()

    init {
        val period = config.interval.toMillis()
        require(period >= 1) { "period $period" }
        statisticsRunnable = StatisticsPeriodicRunnable(statistics, period)
    }

    /**
     * Adds a specific <tt>StatsTransport</tt> through which this [StatsCollector] is to periodically send statistics.
     *
     * @param transport the [StatsTransport] to add
     * @param updatePeriodMs the period in milliseconds at which this [StatsCollector] is to repeatedly send statistics
     * to the specified [transport].
     */
    fun addTransport(transport: StatsTransport, updatePeriodMs: Long) {
        require(updatePeriodMs >= 1) { "period $updatePeriodMs" }

        TransportPeriodicRunnable(transport, updatePeriodMs).also {
            transportRunnables.add(it)
            if (running.get()) {
                transportExecutor.registerRecurringRunnable(it)
            }
        }
    }

    fun removeTransport(transport: StatsTransport) {
        val runnable = transportRunnables.find { it.o == transport }
        runnable?.let {
            transportExecutor.deRegisterRecurringRunnable(it)
            transportRunnables.remove(it)
        }
    }

    /**
     * {@inheritDoc}
     *
     * Starts the [StatsTransport]s added to this [StatsCollector]. Commences the collection of statistics.
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
     * Stops the [StatsTransport]s added to this [StatsCollector] and the [StatisticsPeriodicRunnable].
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
     * Implements a [RecurringRunnable] which periodically collects statistics from a specific [Statistics].
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
}
