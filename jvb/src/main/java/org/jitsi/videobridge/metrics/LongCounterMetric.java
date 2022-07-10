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

package org.jitsi.videobridge.metrics;

import io.prometheus.client.*;

/**
 * A long metric wrapper for Prometheus {@link Counter Counters}, which are monotonically increasing.
 * Provides atomic operations such as {@link #incrementAndGetLong()}.
 *
 * @see <a href="https://prometheus.io/docs/concepts/metric_types/#counter">Prometheus Counter</a>
 * @see <a href="https://prometheus.io/docs/concepts/metric_types/#gauge">Prometheus Gauge</a>
 */
public class LongCounterMetric implements Metric<Long>
{
    private final Counter counter;

    /**
     * Initializes a new {@code LongCounterMetric} instance,
     * registering the underlying {@code Counter} with the default registry.
     *
     * @param name      the name of this counter
     * @param help      the description of this counter
     * @param namespace the namespace (prefix) of this counter
     */
    public LongCounterMetric(String name, String help, String namespace)
    {
        this.counter = Counter.build(name, help).namespace(namespace).register();
    }

    /**
     * Returns the value of this counter.
     *
     * @return the value of this counter
     */
    @Override
    public Long get()
    {
        return (long) counter.get();
    }

    /**
     * Atomically adds the given value to this counter, returning the updated value.
     *
     * @param delta the value to add
     * @return the updated value
     */
    public final Long addAndGetLong(double delta)
    {
        synchronized (counter)
        {
            counter.inc(delta);
            return (long) counter.get();
        }
    }

    /**
     * Atomically increments the value of this counter by one, returning the updated value.
     *
     * @return the updated value
     */
    public final Long incrementAndGetLong()
    {
        return addAndGetLong(1);
    }
}
