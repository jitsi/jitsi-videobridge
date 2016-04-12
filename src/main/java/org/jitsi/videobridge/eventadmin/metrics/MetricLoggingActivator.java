/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.eventadmin.metrics;

import java.util.*;

import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.eventadmin.*;
import org.osgi.framework.*;

/**
 * OSGi activator for the <tt>MetricService</tt>
 *
 * @author zbettenbuk
 * @author George Politis
 */
public class MetricLoggingActivator
    implements BundleActivator
{
    private ServiceRegistration<EventHandler> serviceRegistration;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);
        Dictionary props = new Hashtable();

        props.put(EventConstants.EVENT_TOPIC, new String[] { "org/jitsi/*" });

        serviceRegistration
            = bundleContext.registerService(
                    EventHandler.class,
                    new MetricLoggingHandler(cfg),
                    props);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (serviceRegistration != null)
            serviceRegistration.unregister();
    }
}
