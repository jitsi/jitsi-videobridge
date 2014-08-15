/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;
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
     * Starts the <tt>StatsManager</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>StatsManager</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>StatsManager</tt> OSGi bundle is to start
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

        // Add StatsTransports to StatsManager.
        String transport = DEFAULT_STAT_TRANSPORT;
        int interval = DEFAULT_STAT_INTERVAL;

        if (cfg != null)
        {
            transport = cfg.getString(STATISTICS_TRANSPORT_PNAME, transport);
            interval = cfg.getInt(STATISTICS_INTERVAL_PNAME, interval);
        }
        if (STAT_TRANSPORT_COLIBRI.equals(transport))
        {
            statsMgr.addTransport(new ColibriStatsTransport(), interval);
        }
        else if (STAT_TRANSPORT_PUBSUB.equals(transport))
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
                        "No configuration options for PubSub service and/or"
                            + " node found.");
            }
        }
        else if (transport != null)
        {
            logger.error("Unknown statistics transport: " + transport);
        }

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
     * @param the <tt>BundleContext</tt> in which the <tt>StatsManager</tt> OSGi
     * bundle is to stop
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
