/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import org.osgi.framework.*;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>StatsManager</tt> which starts
 * and stops it in a <tt>BundleContext</tt>.
 * <p>
 * <b>Warning</b>: The class <tt>BundleActivatorImpl</tt> is to be considered
 * internal, its access modifier is public in order to allow the OSGi framework
 * to find it by name and instantiate it.
 * </p>
 *
 * @author Hristo Terezov
 */
public class StatsManagerBundleActivator
    implements BundleActivator
{
    /**
     * The <tt>ServiceRegistration</tt> of a <tt>StatsManager</tt> instance
     * within the <tt>BundleContext</tt> in which this instance has been
     * started.
     */
    private ServiceRegistration<StatsManager> serviceRegistration;

    /**
     * Starts the <tt>StatsManager</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>StatsManager</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param the <tt>BundleContext</tt> in which the <tt>StatsManager</tt> OSGi
     * bundle is to start
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        StatsManager statsManager
            = new StatsManager();

        ServiceRegistration<StatsManager> serviceRegistration = null;

        try
        {
            serviceRegistration
                = bundleContext.registerService(
                    StatsManager.class,
                    statsManager,
                    null);
        }
        finally
        {
            if (serviceRegistration != null)
                this.serviceRegistration = serviceRegistration;
        }
    }

    /**
     * Stops the <tt>StatsManager</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Unregisters and stops a <tt>StatsManager</tt> instance in the specified
     * <tt>BundleContext</tt> if such an instance has been registered and
     * started.
     *
     * @param the <tt>BundleContext</tt> in which the <tt>StatsManager</tt> OSGi
     * bundle is to stop
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        ServiceRegistration<StatsManager> serviceRegistration
            = this.serviceRegistration;

        this.serviceRegistration = null;

        StatsManager statsMgr = null;
        try
        {
            statsMgr
                = bundleContext.getService(serviceRegistration.getReference());
        }
        finally
        {
            serviceRegistration.unregister();
            if (statsMgr != null)
                statsMgr.removeAllStats();
        }

    }
}
