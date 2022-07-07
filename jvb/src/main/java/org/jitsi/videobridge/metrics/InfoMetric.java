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
 * {@code InfoMetric} wraps around a single key-value information pair. Useful for information about the
 * {@link org.jitsi.videobridge.Videobridge Videobridge} instance, such as version and region.
 * In the Prometheus exposition format, these are shown as labels of either a custom metric (OpenMetrics)
 * or a {@link Gauge} (0.0.4 plain text).
 */
public final class InfoMetric implements Metric
{
    private final Info info;
    private final String name;

    /**
     * Initializes a new {@code InfoMetric} instance with the given value,
     * registering the underlying {@code Info} with the default registry.
     *
     * @param name      the name of this metric
     * @param help      the description of this metric
     * @param namespace the namespace (prefix) of this metric
     * @param value     the value of this metric
     */
    public InfoMetric(String name, String help, String namespace, String value)
    {
        this.name = name;
        info = Info.build(name, help).namespace(namespace).create().register();
        info.info(name, value);
    }

    /**
     * Returns the value of this info metric.
     *
     * @return the value of this metric
     */
    public String get()
    {
        return info.get().get(name);
    }

    @Override
    public String getMetricValue()
    {
        return get();
    }
}
