/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.rest.root;

import org.glassfish.jersey.server.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.binders.*;
import org.jitsi.videobridge.rest.filters.*;
import org.jitsi.videobridge.rest.prometheus.*;
import org.jitsi.videobridge.xmpp.*;

import static org.jitsi.videobridge.rest.RestConfig.config;

import org.glassfish.jersey.jackson.JacksonFeature;

public class Application extends ResourceConfig {
    public Application(
            Videobridge videobridge,
            XmppConnection xmppConnection,
            @NotNull Version version,
            @NotNull JvbHealthChecker healthChecker)

    {
        register(
                new ServiceBinder(
                        videobridge,
                        xmppConnection,
                        healthChecker));
        // Filters
        register(ConfigFilter.class);
        register(JacksonFeature.class);
        // Register all resources explicitly for native image compatibility
        register(org.jitsi.videobridge.rest.root.colibri.debug.Debug.class);
        register(org.jitsi.videobridge.rest.root.colibri.drain.Drain.class);
        register(org.jitsi.videobridge.rest.root.colibri.mucclient.MucClient.class);
        register(org.jitsi.videobridge.rest.root.colibri.shutdown.Shutdown.class);
        register(org.jitsi.videobridge.rest.root.colibri.stats.Stats.class);
        register(org.jitsi.videobridge.rest.root.colibri.v2.conferences.Conferences.class);
        register(org.jitsi.videobridge.rest.root.debug.Debug.class);
        register(org.jitsi.videobridge.rest.root.debug.DebugFeatures.class);
        register(org.jitsi.videobridge.rest.root.debug.EndpointDebugFeatures.class);
        register(org.jitsi.videobridge.rest.root.stats.Stats.class);

        if (config.isEnabled(RestApis.HEALTH)) {
            register(new org.jitsi.rest.Health(healthChecker));
        }
        if (config.isEnabled(RestApis.VERSION)) {
            register(new org.jitsi.rest.Version(version));
        }
        if (config.isEnabled(RestApis.PROMETHEUS)) {
            register(new Prometheus(VideobridgeMetricsContainer.getInstance()));
        }
    }
}
