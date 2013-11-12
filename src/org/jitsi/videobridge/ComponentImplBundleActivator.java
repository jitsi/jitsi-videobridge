/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.util.*;

import org.osgi.framework.*;
import org.xmpp.component.*;

/**
 * Implements <tt>org.osgi.framework.BundleActivator</tt> for
 * <tt>ComponentImpl</tt> in order to enable it to choose the start level at
 * which <tt>org.osgi.framework.BundleContext</tt> is available for it to use.
 *
 * @author Lyubomir Marinov
 */
public class ComponentImplBundleActivator
    implements BundleActivator
{
    /**
     * Starts the bundle represented by this <tt>BundleActivator</tt> within a
     * specific execution <tt>BundleContext</tt>. Calls
     * {@link BundleActivator#start(BundleContext)} on all {@link Component}
     * services which implement the <tt>BundleActivator</tt> interface.
     *
     * @param bundleContext the execution <tt>BundleContext</tt> within which
     * the bundle represented by this <tt>BundleActivator</tt> is to start
     * @throws Exception if anything goes wrong while starting the bundle
     * represented by this <tt>BundleActivator</tt> within the specified
     * execution <tt>BundleContext</tt>
     */
    public void start(BundleContext bundleContext)
        throws Exception
    {
        Collection<ServiceReference<Component>> serviceReferences
            = bundleContext.getServiceReferences(Component.class, null);

        if (serviceReferences != null)
        {
            for (ServiceReference<Component> serviceReference
                    : serviceReferences)
            {
                Component service = bundleContext.getService(serviceReference);

                if (service instanceof BundleActivator)
                    ((BundleActivator) service).start(bundleContext);
            }
        }
    }

    /**
     * Stops the bundle represented by this <tt>BundleActivator</tt> within a
     * specific execution <tt>BundleContext</tt>. Calls
     * {@link BundleActivator#stop(BundleContext)} on all {@link Component}
     * services which implement the <tt>BundleActivator</tt> interface.
     *
     * @param bundleContext the execution <tt>BundleContext</tt> within which
     * the bundle represented by this <tt>BundleActivator</tt> is to stop
     * @throws Exception if anything goes wrong while stopping the bundle
     * represented by this <tt>BundleActivator</tt> within the specified
     * execution <tt>BundleContext</tt>
     */
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        Collection<ServiceReference<Component>> serviceReferences
            = bundleContext.getServiceReferences(Component.class, null);

        if (serviceReferences != null)
        {
            for (ServiceReference<Component> serviceReference
                    : serviceReferences)
            {
                Component service = bundleContext.getService(serviceReference);

                if (service instanceof BundleActivator)
                    ((BundleActivator) service).stop(bundleContext);
            }
        }
    }
}
