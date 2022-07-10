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
 * A long metric wrapper for Prometheus {@link Gauge Gauges}.
 * Provides atomic operations such as {@link #incrementAndGetLong()}.
 *
 * @see <a href="https://prometheus.io/docs/concepts/metric_types/#gauge">Prometheus Gauge</a>
 */
public final class LongGaugeMetric implements Metric<Long>
{
    private final Gauge gauge;

    /**
     * Initializes a new {@code LongGaugeMetric} instance,
     * registering the underlying {@code Gauge} with the default registry.
     *
     * @param name      the name of this gauge
     * @param help      the description of this gauge
     * @param namespace the namespace (prefix) of this gauge
     */
    public LongGaugeMetric(String name, String help, String namespace)
    {
        this.gauge = Gauge.build(name, help).namespace(namespace).register();
    }

    /**
     * Returns the value of this gauge.
     *
     * @return the value of this gauge
     */
    @Override
    public Long get()
    {
        return (long) gauge.get();
    }

    /**
     * Atomically increments the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    public long incrementAndGetLong()
    {
        synchronized (gauge)
        {
            gauge.inc(1);
            return (long) gauge.get();
        }
    }

    /**
     * Atomically decrements the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    public long decrementAndGetLong()
    {
        synchronized (gauge)
        {
            gauge.dec();
            return (long) gauge.get();
        }
    }
}
