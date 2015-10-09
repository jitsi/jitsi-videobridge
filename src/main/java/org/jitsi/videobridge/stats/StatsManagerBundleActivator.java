/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

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
    private static final int DEFAULT_STAT_INTERVAL = 1000;

    /**
     * The default value for statistics transport.
     */
    private static final String DEFAULT_STAT_TRANSPORT = null;

    /**
     * The name of the property which enables generating and sending statistics
     * about the Videobridge.
     */
    private static final String ENABLE_STATISTICS_PNAME
        = "org.jitsi.videobridge.ENABLE_STATISTICS";

    /**
     * The <tt>Logger</tt> used by the <tt>StatsManagerBundleActivator</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(StatsManagerBundleActivator.class);

    /**
     * The name of the property which specifies the name of the PubSub node that
     * will receive the statistics about the Videobridge if PubSub transport is
     * used to send statistics.
     */
    private static final String PUBSUB_NODE_PNAME
        = "org.jitsi.videobridge.PUBSUB_NODE";

    /**
     * The name of the property which specifies the name of the service that
     * will receive the statistics about the Videobridge if PubSub transport is
     * used to send statistics.
     */
    private static final String PUBSUB_SERVICE_PNAME
        = "org.jitsi.videobridge.PUBSUB_SERVICE";

    /**
     * The value for callstats.io statistics transport.
     */
    private static final String STAT_TRANSPORT_CALLSTATS_IO = "callstats.io";

    /**
     * The value for COLIBRI statistics transport.
     */
    private static final String STAT_TRANSPORT_COLIBRI = "colibri";

    /**
     * The value for PubSub statistics transport.
     */
    private static final String STAT_TRANSPORT_PUBSUB = "pubsub";

    /**
     * The name of the property which specifies the interval in milliseconds for
     * sending statistics about the Videobridge.
     */
    private static final String STATISTICS_INTERVAL_PNAME
        = "org.jitsi.videobridge.STATISTICS_INTERVAL";

    /**
     * The name of the property which specifies the transport for sending
     * statistics about the Videobridge.
     */
    private static final String STATISTICS_TRANSPORT_PNAME
        = "org.jitsi.videobridge.STATISTICS_TRANSPORT";

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
     * Populates a specific {@code StatsManager} with newly-initialized
     * {@code StatTransport}s as selected through {@code ConfigurationService}
     * and/or {@code System} properties.
     *
     * @param statsMgr the {@code StatsManager} to populate with new
     * {@code StatsTransport}s
     * @param cfg the {@code ConfigurationService} to read property values from
     * or {@code null} to read the property values from {@code System}
     * @param interval the interval/period in milliseconds at which the
     * newly-initialized and added {@code StatsTransport}s to repeatedly send
     * {@code Statistics}
     */
    private void addTransports(
            StatsManager statsMgr,
            ConfigurationService cfg,
            long interval)
    {
        String transports
            = ConfigUtils.getString(
                    cfg,
                    STATISTICS_TRANSPORT_PNAME,
                    DEFAULT_STAT_TRANSPORT);

        if (transports == null || transports.length() == 0)
        {
            // It is OK to have the statistics enabled without explicitly
            // choosing transports because the statistics may be exposed through
            // the REST API as well.
            return;
        }

        // Allow multiple transports.
        for (String transport : transports.split(","))
        {
            if (STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(transport))
            {
                statsMgr.addTransport(new CallStatsIOTransport(), interval);
            }
            else if (STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(transport))
            {
                statsMgr.addTransport(new ColibriStatsTransport(), interval);
            }
            else if (STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(transport))
            {
                String service = cfg.getString(PUBSUB_SERVICE_PNAME);
                String node = cfg.getString(PUBSUB_NODE_PNAME);

                if(service != null && node != null)
                {
                    statsMgr.addTransport(
                            new PubSubStatsTransport(service, node),
                            interval);
                }
                else
                {
                    logger.error(
                            "No configuration properties for PubSub service"
                                + " and/or node found.");
                }
            }
            else if (transport != null && transport.length() != 0)
            {
                logger.error(
                        "Unknown/unsupported statistics transport: "
                            + transport);
            }
        }
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
        ConfigurationService cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);
        boolean enable = false;

        if (cfg != null)
            enable = cfg.getBoolean(ENABLE_STATISTICS_PNAME, enable);
        if (enable)
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
        int interval
            = ConfigUtils.getInt(
                    cfg,
                    STATISTICS_INTERVAL_PNAME,
                    DEFAULT_STAT_INTERVAL);

        // Add StatsTransports to StatsManager.
        addTransports(statsMgr, cfg, interval);

        // Add Statistics to StatsManager.
        statsMgr.addStatistics(new VideobridgeStatistics(), interval);

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
