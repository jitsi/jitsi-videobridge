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
 * Wraps around a Prometheus {@code Gauge} and provides atomic operations
 * such as {@link #incrementAndGet()}.
 *
 * @see <a href="https://prometheus.io/docs/concepts/metric_types/#gauge">Prometheus Gauge</a>
 */
public class GaugeMetric implements Metric
{
    protected final Gauge gauge;

    /**
     * Initializes a new {@code GaugeMetric} instance,
     * registering the underlying {@code Gauge} with the default registry.
     *
     * @param name      the name of this gauge
     * @param help      the description of this gauge
     * @param namespace the namespace (prefix) of this gauge
     */
    public GaugeMetric(String name, String help, String namespace)
    {
        this.gauge = Gauge.build(name, help).namespace(namespace).register();
    }

    /**
     * Returns the value of this gauge.
     *
     * @return the value of this gauge
     */
    protected double get()
    {
        return gauge.get();
    }

    /**
     * Atomically sets the gauge to the given value, returning the updated value.
     *
     * @param newValue the value to set this gauge to
     * @return the updated value
     */
    protected double setAndGet(double newValue)
    {
        synchronized (gauge)
        {
            gauge.set(newValue);
            return gauge.get();
        }
    }

    /**
     * Atomically adds the given value to this gauge, returning the updated value.
     *
     * @param delta the value to add
     * @return the updated value
     */
    protected double addAndGet(double delta)
    {
        synchronized (gauge)
        {
            gauge.inc(delta);
            return gauge.get();
        }
    }

    /**
     * Atomically increments the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    protected double incrementAndGet()
    {
        return addAndGet(1);
    }

    /**
     * Atomically decrements the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    protected double decrementAndGet()
    {
        return addAndGet(-1);
    }

    public Object getMetricValue()
    {
        return gauge.get();
    }
}
