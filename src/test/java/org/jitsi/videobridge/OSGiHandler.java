package org.jitsi.videobridge;

import net.java.sip.communicator.util.*;
import org.jitsi.meet.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

/**
 *
 */
public class OSGiHandler
{
    private BundleContext bc;

    private BundleActivator activator;

    private final Object osgiStartupLock = new Object();

    public void start()
        throws InterruptedException
    {
        OSGi.setBundleConfig(new JvbBundleConfig());

        activator =
            new BundleActivator()
            {
                @Override
                public void start(BundleContext bundleContext)
                    throws Exception
                {
                    bc = bundleContext;
                    synchronized (osgiStartupLock)
                    {
                        osgiStartupLock.notify();
                    }
                }

                @Override
                public void stop(BundleContext bundleContext)
                    throws Exception
                {
                    bc = null;
                    synchronized (osgiStartupLock)
                    {
                        osgiStartupLock.notify();
                    }
                }
            };

        OSGi.start(activator);

        synchronized (osgiStartupLock)
        {
            if (bc == null)
                osgiStartupLock.wait(5000);
            if(bc == null)
                throw new RuntimeException("Failed to start OSGI");
        }

    }

    public void stop()
        throws InterruptedException
    {
        OSGi.stop(activator);

        synchronized (osgiStartupLock)
        {
            if (bc != null)
                osgiStartupLock.wait(10000);
            if(bc != null)
                throw new RuntimeException("Failed to stop OSGI");
        }
    }

    public BundleContext getBundleContext()
    {
        return bc;
    }

    public <T> T getService(Class<T> serviceClass)
    {
        return ServiceUtils.getService(bc, serviceClass);
    }
}
