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
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.binders.*;
import org.jitsi.videobridge.rest.filters.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.xmpp.*;

import static org.jitsi.videobridge.rest.RestConfig.config;

public class Application extends ResourceConfig
{
    public Application(
            Videobridge videobridge,
            XmppConnection xmppConnection,
            StatsCollector statsCollector,
            @NotNull VersionService versionService)

    {
        register(
            new ServiceBinder(
                videobridge,
                xmppConnection,
                statsCollector,
                versionService
            )
        );
        // Filters
        register(ConfigFilter.class);
        // Register all resources in the package
        packages("org.jitsi.videobridge.rest.root");

        if (config.isEnabled(RestApis.HEALTH))
        {
            register(new org.jitsi.rest.Health());
        }
        if (config.isEnabled(RestApis.VERSION))
        {
            register(new org.jitsi.rest.Version());
        }
    }
}
