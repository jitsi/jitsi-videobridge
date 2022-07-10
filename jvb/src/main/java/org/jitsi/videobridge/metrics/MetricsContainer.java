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
import io.prometheus.client.exporter.common.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

import java.io.*;
import java.util.*;

/**
 * {@code MetricsContainer} gathers and exports metrics from a {@link Videobridge} instance.
 */
public final class MetricsContainer
{
    /**
     * The singleton instance of {@code MetricsContainer}.
     */
    private static final MetricsContainer INSTANCE = new MetricsContainer();

    /**
     * Namespace prefix added to all metrics.
     */
    public static final String METRICS_NAMESPACE = "jitsi";

    /**
     * Map metric names to wrapped Prometheus metric types using the  {@link Metric} interface.
     */
    private final Map<String, Metric<?>> metrics;

    /**
     * Initializes a new {@code MetricsContainer} instance.
     */
    private MetricsContainer()
    {
        metrics = new HashMap<>();
    }

    /**
     * Returns the singleton instance of {@code MetricsContainer}.
     *
     * @return the singleton instance of {@code MetricsContainer}.
     */
    public static MetricsContainer getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the metrics in this instance as a JSON string.
     *
     * @return a JSON string of the metrics in this instance
     */
    public String getJsonString()
    {
        Map<String, Object> map = new HashMap<>(metrics.size());
        metrics.forEach((key, metric) -> map.put(key, metric.get()));
        return new JSONObject(map).toJSONString();
    }

    /**
     * Returns the metrics in this instance in the Prometheus text-based format.
     *
     * @param contentType the Content-Type header string
     * @return a JSON string of the metrics in this instance
     */
    public String getPrometheusMetrics(String contentType)
    {
        StringWriter writer = new StringWriter();
        try
        {
            TextFormat.writeFormat(contentType, writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        return writer.toString();
    }

    /**
     * Creates and registers a {@link BooleanMetric} with the given name and help string.
     *
     * @param name the name of this metric
     * @param help the descriptive help string of this metric
     * @return the registered metric
     */
    public BooleanMetric registerBooleanMetric(String name, String help)
    {
        BooleanMetric booleanGauge = null;
        if (!metrics.containsKey(name))
        {
            booleanGauge = new BooleanMetric(name, help, METRICS_NAMESPACE);
            metrics.put(name, booleanGauge);
        }
        return booleanGauge;
    }

    /**
     * Creates and registers a {@link CounterMetric} with the given name and help string.
     *
     * @param name the name of this metric
     * @param help the descriptive help string of this metric
     * @return the registered metric
     */
    public CounterMetric registerCounter(String name, String help)
    {
        CounterMetric counter = null;
        if (!metrics.containsKey(name))
        {
            counter = new CounterMetric(name, help, METRICS_NAMESPACE);
            metrics.put(name, counter);
        }
        return counter;
    }

    /**
     * Creates and registers a {@link CounterMetric} with the given name and help string.
     *
     * @param name the name of this metric
     * @param help the descriptive help string of this metric
     * @return the registered metric
     */
    public LongGaugeMetric registerLongGauge(String name, String help)
    {
        LongGaugeMetric gauge = null;
        if (!metrics.containsKey(name))
        {
            gauge = new LongGaugeMetric(name, help, METRICS_NAMESPACE);
            metrics.put(name, gauge);
        }
        return gauge;
    }

    /**
     * Creates and registers an {@link InfoMetric} with the given name and help string.
     * The given value must not be {@code null}.
     *
     * @param name  the name of this metric
     * @param help  the descriptive help string of this metric
     * @param value the value of this metric
     * @return the registered metric, or {@code null} if the value is {@code null}
     */
    public InfoMetric registerInfo(String name, String help, String value)
    {
        InfoMetric info = null;
        if (!metrics.containsKey(name) && value != null)
        {
            info = new InfoMetric(name, help, METRICS_NAMESPACE, value);
            metrics.put(name, info);
        }
        return info;
    }
}
