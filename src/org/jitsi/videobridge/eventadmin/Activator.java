/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.eventadmin;

import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Registers an <tt>EventAdmin</tt> to a <tt>BundleContext</tt>.
 *
 * @author George Politis
 */
public class Activator implements BundleActivator
{
    private ServiceRegistration<EventAdmin> serviceRegistration;

    @Override
    public void start(final BundleContext bundleContext) throws Exception
    {
        serviceRegistration = bundleContext.registerService(
            EventAdmin.class, new EventAdmin()
            {
                @Override
                public void sendEvent(Event event)
                {
                    Collection<EventHandler> eventHandlers
                        = ServiceUtils2.getServices(bundleContext,
                        EventHandler.class);

                    for (EventHandler eventHandler : eventHandlers)
                    {
                        eventHandler.handleEvent(event);
                    }
                }
            }, null);
    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception
    {
        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
        }
    }
}
