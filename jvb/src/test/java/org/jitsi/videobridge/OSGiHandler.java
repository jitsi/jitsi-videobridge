/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import org.apache.commons.lang3.*;
import org.jitsi.meet.*;
import org.jitsi.osgi.*;
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
        System.setProperty(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            "true");
        OSGi.setBundleConfig(new BundleConfig() {
            @Override
            protected String[][] getBundlesImpl()
            {
                // Add an empty block at the end so our added BundleActivator, which this class
                // blocks on to make sure all services have started by the end of #start, runs
                // all by itself at the very end.  If this empty group isn't added, there can
                // be a race condition between this service and any other service started in
                // the last group
                return ArrayUtils.addAll(super.getBundlesImpl(), new String[1][0]);
            }
        });
        OSGi.setClassLoader(ClassLoader.getSystemClassLoader());

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

    public <T> T getService(Class<T> serviceClass)
    {
        return ServiceUtils2.getService(bc, serviceClass);
    }
}
