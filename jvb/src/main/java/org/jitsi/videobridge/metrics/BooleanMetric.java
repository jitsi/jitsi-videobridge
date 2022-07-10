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
 * A metric that represents booleans using Prometheus {@link Gauge Gauges}.
 * A non-zero value corresponds to {@code true}, zero corresponds to {@code false}.
 */
public final class BooleanMetric implements Metric<Boolean>
{
    private final Gauge gauge;

    /**
     * Initializes a new {@code BooleanMetric} instance,
     * registering the underlying {@code Gauge} with the default registry.
     *
     * @param name      the name of this gauge
     * @param help      the description of this gauge
     * @param namespace the namespace (prefix) of this gauge
     */
    public BooleanMetric(String name, String help, String namespace)
    {
        this.gauge = Gauge.build(name, help).namespace(namespace).register();
    }

    /**
     * Returns the value of this gauge.
     *
     * @return the value of this gauge
     */
    @Override
    public Boolean get()
    {
        return gauge.get() != 0;
    }

    /**
     * Atomically sets the gauge to the given value, returning the updated value.
     *
     * @param newValue the value to set this gauge to
     * @return the updated value
     */
    public boolean setAndGetBoolean(boolean newValue)
    {
        synchronized (gauge)
        {
            gauge.set(newValue ? 1.0 : 0.0);
            return gauge.get() != 0;
        }
    }
}
