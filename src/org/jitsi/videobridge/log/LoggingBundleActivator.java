/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.log;

import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>LoggingService</tt>.
 *
 * @author Boris Grozev
 */
public class LoggingBundleActivator
        implements BundleActivator
{
    /**
     * The <tt>LoggingService</tt> instance in use.
     */
    private InfluxDBLoggingService loggingService;

    private ServiceRegistration<LoggingService> serviceRegistration;

    /**
     * Initializes a <tt>LoggingService</tt>.
     * @param bundleContext the <tt>bundleContext</tt> to use.
     * @throws Exception
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        loggingService
            = new InfluxDBLoggingService(
                ServiceUtils2.getService(bundleContext,
                                         ConfigurationService.class));

        serviceRegistration
            = bundleContext
                .registerService(LoggingService.class, loggingService, null);
    }

    /**
     * Removes the previously initialized <tt>LoggingService</tt> instance
     * from <tt>bundleContext</tt>.
     * @param bundleContext the <tt>bundleContext</tt> to use.
     * @throws Exception
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
        }

    }
}
