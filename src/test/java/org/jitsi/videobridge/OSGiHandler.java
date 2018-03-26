package org.jitsi.videobridge;

import net.java.sip.communicator.util.*;
import org.jitsi.meet.*;
import org.jitsi.videobridge.osgi.*;
import org.osgi.framework.*;

import java.lang.reflect.*;

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
        System.setProperty(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            "true");
        OSGi.setBundleConfig(new JvbBundleConfig());
        OSGi.setClassLoader(getPlatformClassLoader());

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

    private ClassLoader getPlatformClassLoader() {
        ClassLoader cl;
        //JDK 9
        try
        {
            Method getPlatformClassLoader =
                    ClassLoader.class.getMethod("getPlatformClassLoader");
            cl = (ClassLoader) getPlatformClassLoader.invoke(null);
        }
        catch (NoSuchMethodException | IllegalAccessException |
                InvocationTargetException t)
        {
        // pre-JDK9
            cl = ClassLoader.getSystemClassLoader();
        }
        return cl;
    }

    public <T> T getService(Class<T> serviceClass)
    {
        return ServiceUtils.getService(bc, serviceClass);
    }
}
