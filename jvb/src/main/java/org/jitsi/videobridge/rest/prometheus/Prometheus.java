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

package org.jitsi.videobridge.rest.prometheus;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import kotlin.*;
import org.jetbrains.annotations.NotNull;
import org.jitsi.metrics.*;

import java.util.*;
import java.util.stream.*;

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
    static private final Comparator<MediaType> comparator =  Comparator.comparing(Prometheus::getQValue).reversed();

    private static double getQValue(MediaType m)
    {
        return m.getParameters().get("q") == null ? 1.0 : Double.parseDouble(m.getParameters().get("q"));
    }

    @NotNull
    private final MetricsContainer metricsContainer;

    public Prometheus(@NotNull MetricsContainer metricsContainer)
    {
        this.metricsContainer = metricsContainer;
    }

    @GET
    public Response x(@HeaderParam("Accept") String accept)
    {
        List<String> acceptMediaTypes
            = Arrays.stream(accept.split(","))
                .map(MediaType::valueOf)
                .sorted(comparator)
                .map(m -> m.getType() + "/" + m.getSubtype())
                .collect(Collectors.toList());
        Pair<String, String> m = metricsContainer.getMetrics(acceptMediaTypes);

        return Response.ok(m.getFirst(), m.getSecond()).build();
    }
}
