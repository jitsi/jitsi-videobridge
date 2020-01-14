/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats;

import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;
import org.osgi.framework.*;

import static org.jitsi.videobridge.stats.config.StatsManagerBundleActivatorConfig.Config;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>StatsManager</tt> which starts
 * and stops it in a <tt>BundleContext</tt>.
 * <p>
 * <b>Warning</b>: The class <tt>StatsManagerBundleActivator</tt> is to be
 * considered internal, its access modifier is public in order to allow the OSGi
 * framework to find it by name and instantiate it.
 * </p>
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class StatsManagerBundleActivator
    implements BundleActivator
{
    /**
     * The <tt>BundleContext</tt> in which a
     * <tt>StatsManagerBundleActivator</tt> has been started.
     */
    private static BundleContext bundleContext;

    /**
     * The <tt>Logger</tt> used by the <tt>StatsManagerBundleActivator</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(StatsManagerBundleActivator.class.getName());

    /**
     * Gets the <tt>BundleContext</tt> in which a
     * <tt>StatsManagerBundleActivator</tt> has been started.
     *
     * @return the <tt>BundleContext</tt> in which a
     * <tt>StatsManagerBundleActivator</tt> has been started
     */
    public static BundleContext getBundleContext()
    {
        return bundleContext;
    }

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
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>StatsManager</tt> OSGi bundle is to start
     * @throws Exception
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

        if (Config.enabled())
        {
            StatsManagerBundleActivator.bundleContext = bundleContext;

            boolean started = false;

            try
            {
                start(cfg);
                started = true;
            }
            finally
            {
                if (!started
                        && (StatsManagerBundleActivator.bundleContext
                                == bundleContext))
                {
                    StatsManagerBundleActivator.bundleContext = null;
                }
            }
        }
    }

    /**
     * Starts the <tt>StatsManager</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>StatsManager</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param cfg the <tt>ConfigurationService</tt> in the
     * <tt>BundleContext</tt> in which the <tt>StatsManager</tt> OSGi is to
     * start
     */
    private void start(ConfigurationService cfg)
        throws Exception
    {
        StatsManager statsMgr = new StatsManager();

        // Add Statistics to StatsManager.
        //
        // This is the default Statistics instance which (1) uses the default
        // interval and (2) may be transported by pseudo-transports such as the
        // REST API. StatsTransport instances may utilize the default Statistics
        // instance or may choose to add other Statistics instances (e.g. with
        // intervals other than the default) to StatsManager.
        //
        // XXX Consequently, the default Statistics instance is to be added to
        // StatsManager before adding any StatsTransport instances.
        statsMgr.addStatistics(new VideobridgeStatistics(), Config.statsInterval().toMillis());

        // Add StatsTransports to StatsManager.
        Config.transportConfigs().forEach(transportConfig -> {
            statsMgr.addTransport(transportConfig.toStatsTransport(), transportConfig.getInterval().toMillis());

        });

        statsMgr.start(bundleContext);

        // Register StatsManager as an OSGi service.
        ServiceRegistration<StatsManager> serviceRegistration = null;

        try
        {
            serviceRegistration
                = bundleContext.registerService(
                        StatsManager.class,
                        statsMgr,
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
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>StatsManager</tt> OSGi bundle is to stop
     * @throws Exception
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        // Unregister StatsManager as an OSGi service.
        ServiceRegistration<StatsManager> serviceRegistration
            = this.serviceRegistration;

        this.serviceRegistration = null;

        StatsManager statsMgr = null;

        // It means that either stats are not enabled or we have failed to start
        if (serviceRegistration == null)
            return;

        try
        {
            statsMgr
                = bundleContext.getService(serviceRegistration.getReference());
        }
        finally
        {
            serviceRegistration.unregister();
            if (statsMgr != null)
                statsMgr.stop(bundleContext);
        }
    }
}
