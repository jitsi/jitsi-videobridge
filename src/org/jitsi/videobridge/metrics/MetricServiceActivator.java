/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.metrics;

import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

/**
 * OSGi activator for the <tt>MetricService</tt>
 *
 * @author zbettenbuk
 */
public class MetricServiceActivator
    implements BundleActivator
{

    private MetricService metricService;

    private ServiceRegistration<MetricService> serviceRegistration;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService config
            = ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        this.metricService = new MetricService(config);
        this.serviceRegistration
            = bundleContext.registerService(MetricService.class,
                                            this.metricService,
                                            null);
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
