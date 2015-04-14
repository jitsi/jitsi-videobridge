/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.metrics;

import java.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.eventadmin.*;
import org.jitsi.videobridge.osgi.*;
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
