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
 *
 * @author Lyubomir Marinov
 */
public class ServiceUtils2
{
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
