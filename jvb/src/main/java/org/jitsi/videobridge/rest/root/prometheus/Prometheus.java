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

package org.jitsi.videobridge.rest.root.prometheus;

import io.prometheus.client.exporter.common.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jitsi.videobridge.metrics.*;

/**
 * A REST endpoint exposing JVB stats for Prometheus.
 * Any scraper supporting Prometheus' text-based formats ({@code text/plain; version=0.0.4} or OpenMetrics)
 * is compatible with this {@code /metrics} endpoint.<br>
 * JSON is provided when the client performs a request with the 'Accept' header set to {@code application/json}.<br>
 * The response defaults to {@code text/plain; version=0.0.4} formatted output.
 *
 * @see <a href="https://github.com/prometheus/docs/blob/main/content/docs/instrumenting/exposition_formats.md">
 * Prometheus' exposition formats</a>
 */
@Path("/metrics")
public class Prometheus
{
    protected VideobridgeMetricsContainer metricsContainer = VideobridgeMetricsContainer.getInstance();

    @GET
    @Produces(TextFormat.CONTENT_TYPE_004)
    public String getPrometheusPlainText()
    {
        return metricsContainer.getPrometheusMetrics(TextFormat.CONTENT_TYPE_004);
    }

    @GET
    @Produces(TextFormat.CONTENT_TYPE_OPENMETRICS_100)
    public String getPrometheusOpenMetrics()
    {
        return metricsContainer.getPrometheusMetrics(TextFormat.CONTENT_TYPE_OPENMETRICS_100);
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON, "*/*;q=0"})
    public String getJsonString()
    {
        return metricsContainer.getJsonString();
    }
}
