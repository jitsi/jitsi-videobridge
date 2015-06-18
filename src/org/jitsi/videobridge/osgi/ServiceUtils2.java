/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.osgi;

import java.util.*;

import org.osgi.framework.*;

/**
 * Gathers utility functions related to OSGi services such as getting a service
 * registered in a BundleContext.
 *
 * @author Lyubomir Marinov
 */
public class ServiceUtils2
{
    /**
     * Gets an OSGi service registered in a specific <tt>BundleContext</tt> by
     * its <tt>Class</tt>
     *
     * @param <T> the very type of the OSGi service to get
     * @param bundleContext the <tt>BundleContext</tt> in which the service to
     * get has been registered
     * @param serviceClass the <tt>Class</tt> with which the service to get has
     * been registered in the <tt>bundleContext</tt>
     * @return the OSGi service registered in <tt>bundleContext</tt> with the
     * specified <tt>serviceClass</tt> if such a service exists there;
     * otherwise, <tt>null</tt>
     */
    public static <T> T getService(
            BundleContext bundleContext,
            Class<T> serviceClass)
    {
        return
            net.java.sip.communicator.util.ServiceUtils.getService(
                    bundleContext,
                    serviceClass);
    }

    public static <T> Collection<T> getServices(
            BundleContext bundleContext,
            Class<T> serviceClass)
    {
        List<T> services = new LinkedList<T>();

        if (bundleContext != null)
        {
            Collection<ServiceReference<T>> serviceReferences = null;

            try
            {
                serviceReferences
                    = bundleContext.getServiceReferences(serviceClass, null);
            }
            catch (IllegalStateException e)
            {
            }
            catch (InvalidSyntaxException e)
            {
            }
            if (serviceReferences != null)
            {
                for (ServiceReference<T> serviceReference : serviceReferences)
                {
                    T service = null;

                    try
                    {
                        service = bundleContext.getService(serviceReference);
                    }
                    catch (IllegalArgumentException e)
                    {
                    }
                    catch (IllegalStateException e)
                    {
                        // The bundleContext is no longer valid.
                        break;
                    }
                    catch (SecurityException e)
                    {
                    }
                    if ((service != null) && !services.contains(service))
                        services.add(service);
                }
            }
        }
        return services;
    }

    /** Prevents the creation of <tt>ServiceUtils2</tt> instances. */
    private ServiceUtils2()
    {
    }
}
