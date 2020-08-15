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

package org.jitsi.videobridge.rest.binders;

import org.glassfish.hk2.utilities.binding.*;
import org.jitsi.health.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.osgi.framework.*;

import static org.jitsi.videobridge.version.JvbVersionServiceSupplierKt.jvbVersionServiceSingleton;

/**
 * OsgiServiceBinder creates various providers for OSGI services
 * needed by Jersey REST resources.  This binding enables the
 * REST resource classes to have the needed OSGI service providers
 * injected rather than requiring they be passed in (which simplifies
 * registering them with Jersey).
 */
public class OsgiServiceBinder extends AbstractBinder
{
    protected final BundleContext bundleContext;

    public OsgiServiceBinder(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;
    }

    @Override
    protected void configure()
    {
        bind(new VideobridgeProvider(bundleContext)).to(VideobridgeProvider.class);
        bind(new StatsManagerProvider(null) {
            @Override
            public StatsManager get()
            {
                return StatsManagerSupplierKt.singleton.get();
            }
        }).to(StatsManagerProvider.class);
        bind(new VideobridgeProvider((bundleContext))).to(VideobridgeProvider.class);
        // Instead of refactoring the service provider hierarchy in order to pull this from a non-osgi location,
        // just pass a null BundleContext and override get()
        bind(new VersionServiceProvider(null) {
            @Override
            public VersionService get()
            {
                return jvbVersionServiceSingleton.get();
            }
        }).to(VersionServiceProvider.class);
        bind(new HealthCheckServiceProvider(bundleContext)).to(HealthCheckServiceProvider.class);
        bind(new ClientConnectionProvider(null) {
            @Override
            public ClientConnectionImpl get()
            {
                return ClientConnectionSupplierKt.singleton.get();
            }
        }).to(ClientConnectionProvider.class);
        bind(new ClientConnectionProvider((bundleContext))).to(ClientConnectionProvider.class);
        bind(new ConfigProvider(bundleContext)).to(ConfigProvider.class);
    }
}

