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
 */
public class MetricLoggingActivator
    implements BundleActivator
{

    private EventHandler eventHandler;

    private ServiceRegistration<MetricLoggingHandler> serviceRegistration;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService config
            = ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        this.eventHandler = new MetricLoggingHandler(config);

        String[] topics = new String[] {
            "org/jitsi/*"
        };

        Dictionary props = new Hashtable();
        props.put(EventConstants.EVENT_TOPIC, topics);

        this.serviceRegistration
            = bundleContext.registerService(EventHandler.class,
                                            this.eventHandler,
                                            props);
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
