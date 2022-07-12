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

import io.prometheus.client.Counter

/**
 * A long metric wrapper for Prometheus [Counters][Counter], which are monotonically increasing.
 * Provides atomic operations such as [incAndGet].
 *
 * @see [Prometheus Counter](https://prometheus.io/docs/concepts/metric_types/.counter)
 *
 * @see [Prometheus Gauge](https://prometheus.io/docs/concepts/metric_types/.gauge)
 */
class CounterMetric @JvmOverloads constructor(
    /** the name of this metric */
    name: String,
    /** the description of this metric */
    help: String,
    /** the namespace (prefix) of this metric */
    namespace: String,
    /** an optional initial value for this metric */
    initialValue: Long = 0L
) : Metric<Long> {
    private val counter =
        Counter.build(name, help).namespace(namespace).register().also { it.inc(initialValue.toDouble()) }

    /**
     * Returns the value of this metric.
     */
    override fun get() = counter.get().toLong()

    /**
     * Atomically adds the given value to this counter, returning the updated value.
     *
     * @return the updated value
     */
    fun addAndGet(delta: Long): Long = synchronized(counter) {
        counter.inc(delta.toDouble())
        return counter.get().toLong()
    }

    /**
     * Atomically increments the value of this counter by one, returning the updated value.
     *
     * @return the updated value
     */
    fun incAndGet() = addAndGet(1)

    /**
     * Atomically increments the value of this counter by one.
     */
    fun inc() = synchronized(counter) { counter.inc() }
}
