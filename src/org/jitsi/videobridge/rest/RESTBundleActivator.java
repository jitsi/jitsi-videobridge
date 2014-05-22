/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rest;

import java.lang.reflect.*;

import net.java.sip.communicator.util.*;

import org.eclipse.jetty.server.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle which implements an
 * HTTP/JSON API for Jitsi Videobridge.
 * <p>
 * The REST API of Jitsi Videobridge is currently served over HTTP on port
 * <tt>8080</tt> by default. The default port value may be overriden by the
 * <tt>System</tt> and/or <tt>ConfigurationService</tt> property with name
 * <tt>org.jitsi.videobridge.rest.jetty.port</tt>.
 * </p>
 *
 * @author Lyubomir Marinov
 */
public class RESTBundleActivator
    implements BundleActivator
{
    /**
     * The name of the <tt>System</tt> and/or <tt>ConfigurationService</tt>
     * property which specifies the port on which the HTTP/JSON API of Jitsi
     * Videobridge is to be served. The default value is <tt>8080</tt>.
     */
    private static final String JETTY_PORT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.port";

    /**
     * The <tt>Logger</tt> used by the <tt>RESTBundleActivator</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RESTBundleActivator.class);

    /**
     * The Jetty <tt>Server</tt> which provides the HTTP interface to the JSON
     * API of Jitsi Videobridge.
     */
    private Server server;

    /**
     * Starts the OSGi bundle which implements an HTTP/JSON API for Jitsi
     * Videobridge in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the OSG bundle
     * which implements an HTTP/JSON API for Jitsi Videobridge is to start
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        // The HTTP/JSON API starts if explicitly instructed to do so.
        ConfigurationService cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);
        boolean start;
        int jettyPort = 8080;

        if (cfg == null)
        {
            start = Boolean.getBoolean(Videobridge.REST_API_PNAME);
            jettyPort = Integer.getInteger(JETTY_PORT_PNAME, jettyPort);
        }
        else
        {
            start = cfg.getBoolean(Videobridge.REST_API_PNAME, false);
            jettyPort = cfg.getInt(JETTY_PORT_PNAME, jettyPort);
        }
        if (!start)
            return;

        try
        {
            Server server = new Server(jettyPort);

            server.setHandler(new HandlerImpl(bundleContext));
            /*
             * The server will start a non-daemon background Thread which will
             * keep the application running on success. 
             */
            server.start();

            this.server = server;
        }
        catch (Throwable t)
        {
            // Log any Throwable for debugging purposes and rethrow.
            logger.error(
                    "Failed to start the REST API of Jitsi Videobridge.",
                    t);
            if (t instanceof Error)
                throw (Error) t;
            else if (t instanceof Exception)
                throw (Exception) t;
            else
                throw new UndeclaredThrowableException(t);
        }
    }

    /**
     * Stops the OSGi bundle which implements an HTTP/JSON API for Jitsi
     * Videobridge in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the OSG bundle
     * which implements an HTTP/JSON API for Jitsi Videobridge is to stop
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }
}
