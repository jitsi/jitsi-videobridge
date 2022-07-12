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

import io.prometheus.client.Info

/**
 * `InfoMetric` wraps around a single key-value information pair. Useful for information about the
 * [Videobridge][org.jitsi.videobridge.Videobridge] instance, such as version and region.
 * In the Prometheus exposition format, these are shown as labels of either a custom metric (OpenMetrics)
 * or a [Gauge][io.prometheus.client.Gauge] (0.0.4 plain text).
 */
class InfoMetric(
    /** the name of this metric */
    private val name: String,
    /** the description of this metric */
    help: String,
    /** the namespace (prefix) of this metric */
    namespace: String,
    /** the value of this info metric */
    value: String?
) : Metric<String> {
    private val info = Info.build(name, help).namespace(namespace).register().also { it.info(name, value) }

    /**
     * Returns the value of this info metric.
     */
    override fun get() = info.get()[name]!!
}
