/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.metrics;

import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.eventadmin.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

import java.util.*;

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
        ConfigurationService config = ServiceUtils2.getService(
            bundleContext, ConfigurationService.class);

        Dictionary props = new Hashtable();
        props.put(EventConstants.EVENT_TOPIC, new String[] { "org/jitsi/*" });

        this.serviceRegistration = bundleContext.registerService(
            EventHandler.class, new MetricLoggingHandler(config), props);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (this.serviceRegistration != null)
        {
            this.serviceRegistration.unregister();
        }
    }

}
