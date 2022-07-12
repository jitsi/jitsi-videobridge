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

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.json.simple.JSONObject
import java.io.IOException
import java.io.StringWriter

/**
 * `MetricsContainer` gathers and exports metrics from a [Videobridge][org.jitsi.videobridge.Videobridge] instance.
 */
class MetricsContainer private constructor() {
    /**
     * Map metric names to wrapped Prometheus metric types using the [Metric] interface.
     */
    private val metrics: MutableMap<String, Metric<*>> = HashMap()

    /**
     * Returns the metrics in this instance as a JSON string.
     *
     * @return a JSON string of the metrics in this instance
     */
    val jsonString: String
        get() {
            val map: MutableMap<String, Any?> = HashMap(metrics.size)
            metrics.forEach { (key: String?, metric: Metric<*>) -> map[key] = metric.get() }
            return JSONObject(map).toJSONString()
        }

    /**
     * Returns the metrics in this instance in the Prometheus text-based format.
     * See [Formats](https://github.com/prometheus/docs/blob/main/content/docs/instrumenting/exposition_formats.md).
     *
     * @param contentType the Content-Type header string
     * @return the metrics in this instance in the Prometheus text-based format
     */
    fun getPrometheusMetrics(contentType: String): String {
        val writer = StringWriter()
        try {
            TextFormat.writeFormat(contentType, writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        return writer.toString()
    }

    /**
     * Creates and registers a [BooleanMetric] with the given [name], [help] string and optional [initialValue].
     */
    @JvmOverloads
    fun registerBooleanMetric(
        /** the name of the metric */
        name: String,
        /** the description of the metric */
        help: String,
        /** the optional initial value of the metric */
        initialValue: Boolean = false
    ): BooleanMetric? {
        val metric = if (!metrics.containsKey(name)) {
            BooleanMetric(name, help, METRICS_NAMESPACE, initialValue).also { metrics[name] = it }
        } else {
            null
        }
        return metric
    }

    /**
     * Creates and registers a [CounterMetric] with the given [name], [help] string and optional [initialValue].
     */
    @JvmOverloads
    fun registerCounter(
        /** the name of the metric */
        name: String,
        /** the description of the metric */
        help: String,
        /** the optional initial value of the metric */
        initialValue: Long = 0
    ): CounterMetric? {
        val metric = if (!metrics.containsKey(name)) {
            CounterMetric(name, help, METRICS_NAMESPACE, initialValue).also { metrics[name] = it }
        } else {
            null
        }
        return metric
    }

    /**
     * Creates and registers a [CounterMetric] with the given [name], [help] string and optional [initialValue].
     */
    @JvmOverloads
    fun registerLongGauge(
        /** the name of the metric */
        name: String,
        /** the description of the metric */
        help: String,
        /** the optional initial value of the metric */
        initialValue: Long = 0
    ): LongGaugeMetric? {
        val metric = if (!metrics.containsKey(name)) {
            LongGaugeMetric(name, help, METRICS_NAMESPACE, initialValue).also { metrics[name] = it }
        } else {
            null
        }
        return metric
    }

    /**
     * Creates and registers an [InfoMetric] with the given [name], [help] string and [value].
     */
    fun registerInfo(
        /** the name of the metric */
        name: String,
        /** the description of the metric */
        help: String,
        /** the value of the metric */
        value: String
    ): InfoMetric? {
        val metric = if (!metrics.containsKey(name)) {
            InfoMetric(name, help, METRICS_NAMESPACE, value).also { metrics[name] = it }
        } else {
            null
        }
        return metric
    }

    companion object {
        /**
         * The singleton instance of `MetricsContainer`.
         */
        @JvmStatic
        val instance = MetricsContainer()

        /**
         * Namespace prefix added to all metrics.
         */
        const val METRICS_NAMESPACE = "jitsi"
    }
}
