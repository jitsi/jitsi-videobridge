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

import com.typesafe.config.*;
import org.jitsi.videobridge.stats.config.*;
import org.jitsi.videobridge.util.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

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
     * The default value for statistics interval.
     */
    public static final int DEFAULT_STAT_INTERVAL = 1000;

    /**
     * The value for callstats.io statistics transport.
     */
    public static final String STAT_TRANSPORT_CALLSTATS_IO = "callstats.io";

    /**
     * The value for COLIBRI statistics transport.
     */
    public static final String STAT_TRANSPORT_COLIBRI = "colibri";

    /**
     * The value for PubSub statistics transport.
     */
    public static final String STAT_TRANSPORT_PUBSUB = "pubsub";

    /**
     * The value used to enable the MUC statistics transport.
     */
    public static final String STAT_TRANSPORT_MUC = "muc";

    /**
     * The name of the property which specifies the interval in milliseconds for
     * sending statistics about the Videobridge.
     */
    public static final String STATISTICS_INTERVAL_PNAME
        = "org.jitsi.videobridge.STATISTICS_INTERVAL";

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
     * Adds a new {@code StatsTransport} to a specific {@code StatsManager}. The
     * newly-added {@code StatsTransport} in to repeatedly send
     * {@code Statistics} at a specific {@code interval}.
     *
     * @param statsMgr the {@code StatsManager} to add the new
     * {@code StatsTransport} to
     * @param statsTransportConfig the {@link StatsTransportConfig} to read property values from
     */
    private void addTransport(StatsManager statsMgr, StatsTransportConfig statsTransportConfig)
    {
        StatsTransport t = StatsTransportFactory.create(statsTransportConfig);
        int interval = (int)statsTransportConfig.getInterval().toMillis();

        // The interval/period of the Statistics better be the same as the
        // interval/period of the StatsTransport.
        if (statsMgr.findStatistics(VideobridgeStatistics.class, interval)
                == null)
        {
            statsMgr.addStatistics(new VideobridgeStatistics(), interval);
        }

        statsMgr.addTransport(t, interval);
    }

    /**
     * Populates a specific {@code StatsManager} with newly-initialized
     * {@code StatTransport}s as selected through {@code ConfigurationService}
     * and/or {@code System} properties.
     *
     * @param statsMgr the {@code StatsManager} to populate with new
     * {@code StatsTransport}s
     * @param statsConfig the {@code Config} to read property values from
     */
    private void addTransports(StatsManager statsMgr, Config statsConfig)
    {
        List<StatsTransportConfig> configs = statsConfig.getConfigList("transports").stream()
                .map(StatsTransportConfigBeanFactory::create)
                .collect(Collectors.toList());

        configs.forEach(config -> addTransport(statsMgr, config));
    }

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
        Config statsConfig = JvbConfig.getConfig().getConfig("videobridge.stats");
        if (!statsConfig.getBoolean("enabled"))
        {
            return;
        }

        StatsManagerBundleActivator.bundleContext = bundleContext;

        boolean started = false;
        try
        {
            start(statsConfig);
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

    /**
     * Starts the <tt>StatsManager</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>StatsManager</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param statsConfig the <tt>Config</tt> for this service
     */
    private void start(Config statsConfig)
        throws Exception
    {
        StatsManager statsMgr = new StatsManager();
        //TODO: use Duration for interval
        int interval = (int)statsConfig.getDuration("interval", TimeUnit.MILLISECONDS);

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
        statsMgr.addStatistics(new VideobridgeStatistics(), interval);

        // Add StatsTransports to StatsManager.
        addTransports(statsMgr, statsConfig);

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
