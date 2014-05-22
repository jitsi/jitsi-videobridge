/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import org.osgi.framework.*;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>Videobridge</tt> which starts
 * and stops it in a <tt>BundleContext</tt>.
 * <p>
 * <b>Warning</b>: The class <tt>BundleActivatorImpl</tt> is to be considered
 * internal, its access modifier is public in order to allow the OSGi framework
 * to find it by name and instantiate it.
 * </p>
 *
 * @author Lyubomir Marinov
 */
public class VideobridgeBundleActivator
    implements BundleActivator
{
    /**
     * The <tt>ServiceRegistration</tt> of a <tt>Videobridge</tt> instance
     * within the <tt>BundleContext</tt> in which this instance has been
     * started.
     */
    private ServiceRegistration<Videobridge> serviceRegistration;

    /**
     * Starts the <tt>Videobridge</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>Videobridge</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param the <tt>BundleContext</tt> in which the <tt>Videobridge</tt> OSGi
     * bundle is to start
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        Videobridge videobridge = new Videobridge();

        videobridge.start(bundleContext);

        ServiceRegistration<Videobridge> serviceRegistration = null;

        try
        {
            serviceRegistration
                = bundleContext.registerService(
                        Videobridge.class,
                        videobridge,
                        null);
        }
        finally
        {
            if (serviceRegistration == null)
                videobridge.stop(bundleContext);
            else
                this.serviceRegistration = serviceRegistration;
        }
    }

    /**
     * Stops the <tt>Videobridge</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Unregisters and stops a <tt>Videobridge</tt> instance in the specified
     * <tt>BundleContext</tt> if such an instance has been registered and
     * started.
     *
     * @param the <tt>BundleContext</tt> in which the <tt>Videobridge</tt> OSGi
     * bundle is to stop
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        ServiceRegistration<Videobridge> serviceRegistration
            = this.serviceRegistration;

        this.serviceRegistration = null;

        Videobridge videobridge = null;

        try
        {
            videobridge
                = bundleContext.getService(serviceRegistration.getReference());
        }
        finally
        {
            serviceRegistration.unregister();
            if (videobridge != null)
                videobridge.stop(bundleContext);
        }
    }
}
