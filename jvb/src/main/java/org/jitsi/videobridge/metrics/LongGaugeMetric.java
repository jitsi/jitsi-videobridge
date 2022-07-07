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

/**
 * Extends an {@link GaugeMetric} with methods that return {@code long} values.
 */
public final class LongGaugeMetric extends GaugeMetric
{
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
        super(name, help, namespace);
    }

    /**
     * Returns the value of this gauge.
     *
     * @return the value of this gauge
     */
    public long getLong()
    {
        return (long) get();
    }

    /**
     * Atomically increments the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    public long incrementAndGetLong()
    {
        return (long) incrementAndGet();
    }

    /**
     * Atomically decrements the value of this gauge by one, returning the updated value.
     *
     * @return the updated value
     */
    public long decrementAndGetLong()
    {
        return (long) decrementAndGet();
    }

    @Override
    public Long getMetricValue()
    {
        return getLong();
    }
}
