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

package org.jitsi.videobridge.rest;

import org.glassfish.jersey.server.*;
import org.jitsi.videobridge.rest.about.health.*;
import org.jitsi.videobridge.rest.about.version.Version;
import org.jitsi.videobridge.rest.binders.*;
import org.jitsi.videobridge.rest.conferences.*;
import org.jitsi.videobridge.rest.stats.*;
import org.osgi.framework.*;

public class Application extends ResourceConfig
{
    public Application(BundleContext bundleContext)
    {
//        packages("org.jitsi.videobridge.rest");
        register(new OsgiServiceBinder(bundleContext));
        register(Stats.class);
        register(Version.class);
        register(Health.class);
        register(Conferences.class);
    }
}
