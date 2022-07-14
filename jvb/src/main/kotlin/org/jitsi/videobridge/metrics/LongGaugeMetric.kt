/*
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.videobridge.metrics

import io.prometheus.client.Gauge

/**
 * A long metric wrapper for Prometheus [Gauges][Gauge].
 * Provides atomic operations such as [incAndGet].
 *
 * @see [Prometheus Gauge](https://prometheus.io/docs/concepts/metric_types/.gauge)
 */
class LongGaugeMetric @JvmOverloads constructor(
    /** the name of this metric */
    name: String,
    /** the description of this metric */
    help: String,
    /** the namespace (prefix) of this metric */
    namespace: String,
    /** an optional initial value for this metric */
    initialValue: Long = 0L
) : Metric<Long> {
    private val gauge = Gauge.build(name, help).namespace(namespace).register().apply { set(initialValue.toDouble()) }

    /**
     * Returns the value of this gauge.
     */
    override fun get() = gauge.get().toLong()

    /**
     * Atomically sets the gauge to the given value.
     */
    fun set(newValue: Long): Unit = synchronized(gauge) { gauge.set(newValue.toDouble()) }

    /**
     * Atomically increments the value of this gauge by one.
     */
    fun inc() = synchronized(gauge) { gauge.inc() }

    /**
     * Atomically decrements the value of this gauge by one.
     */
    fun dec() = synchronized(gauge) { gauge.dec() }

    /**
     * Atomically adds the given value to this gauge, returning the updated value.
     *
     * @return the updated value
     */
    fun addAndGet(delta: Long): Long = synchronized(gauge) {
        gauge.inc(delta.toDouble())
        return gauge.get().toLong()
    }

    /**
     * Atomically increments the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    fun incAndGet() = addAndGet(1)

    /**
     * Atomically decrements the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    fun decAndGet() = addAndGet(-1)
}
