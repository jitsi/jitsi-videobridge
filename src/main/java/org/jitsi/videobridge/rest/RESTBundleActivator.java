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
package org.jitsi.videobridge.rest;

import java.util.*;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.glassfish.jersey.servlet.*;
import org.jitsi.rest.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.about.version.*;
import org.jitsi.videobridge.rest.conferences.*;
import org.jitsi.videobridge.rest.debug.*;
import org.jitsi.videobridge.rest.about.health.*;
import org.jitsi.videobridge.rest.mucclient.*;
import org.jitsi.videobridge.rest.shutdown.*;
import org.jitsi.videobridge.rest.stats.*;
import org.jitsi.videobridge.util.*;
import org.osgi.framework.*;

import javax.servlet.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle which implements a
 * REST API for Videobridge.
 * <p>
 * The REST API of Videobridge is currently served over HTTP on port
 * <tt>8080</tt> by default. The default port value may be overridden by the
 * <tt>System</tt> and <tt>ConfigurationService</tt> property with name
 * <tt>org.jitsi.videobridge.rest.jetty.port</tt>.
 * </p>
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class RESTBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables graceful shutdown through REST API.
     * It is disabled by default.
     */
    public static final String ENABLE_REST_SHUTDOWN_PNAME
        = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables <tt>/colibri/*</tt> REST API endpoints.
     */
    public static final String ENABLE_REST_COLIBRI_PNAME
      = "org.jitsi.videobridge.ENABLE_REST_COLIBRI";

    /**
     * The prefix of the property names for the Jetty instance managed by
     * this {@link AbstractJettyBundleActivator}.
     */
    public static final String JETTY_PROPERTY_PREFIX
        = "org.jitsi.videobridge.rest.private";

    /**
     * Initializes a new {@code RESTBundleActivator} instance.
     */
    public RESTBundleActivator()
    {
        super(JETTY_PROPERTY_PREFIX);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStop(BundleContext bundleContext)
        throws Exception
    {
        if (server != null)
        {
            // FIXME graceful Jetty shutdown
            // When shutdown request is accepted, empty response is sent back
            // instead of 200, because Jetty is not being shutdown gracefully.
            Thread.sleep(1000);
        }

        super.doStop(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Handler initializeHandlerList(
            BundleContext bundleContext,
            Server server)
    {
        List<Handler> handlers = new ArrayList<>();

        VideobridgeProvider videobridgeProvider = new VideobridgeProvider(bundleContext);
        if (getCfgBoolean(ENABLE_REST_COLIBRI_PNAME, true))
        {
            ServletContextHandler colibriContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            colibriContextHandler.setContextPath("/colibri");

            ConferencesApp conferencesHandler = new ConferencesApp(videobridgeProvider);
            ServletHolder conferencesServletHolder = new ServletHolder(new ServletContainer(conferencesHandler));
            colibriContextHandler.addServlet(conferencesServletHolder, "/conferences/*");

            DebugApp debugHandler = new DebugApp(videobridgeProvider);
            ServletHolder debugServletHolder = new ServletHolder(new ServletContainer(debugHandler));
            colibriContextHandler.addServlet(debugServletHolder, "/debug/*");

            ClientConnectionProvider clientConnectionProvider = new ClientConnectionProvider(bundleContext);
            MucClientApp mucClientHandler = new MucClientApp(clientConnectionProvider);
            ServletHolder mucClientServletHolder = new ServletHolder(new ServletContainer(mucClientHandler));
            colibriContextHandler.addServlet(mucClientServletHolder, "/muc-client/*");

            StatsManagerProvider statsManagerProvider = new StatsManagerProvider(bundleContext);
            StatsApp statHandler = new StatsApp(statsManagerProvider);
            ServletHolder statsServletHolder = new ServletHolder(new ServletContainer(statHandler));
            colibriContextHandler.addServlet(statsServletHolder, "/stats/*");

            if (getCfgBoolean(ENABLE_REST_SHUTDOWN_PNAME, false))
            {
                ShutdownApp shutdownHandler = new ShutdownApp(videobridgeProvider);
                ServletHolder shutdownServletHolder = new ServletHolder(new ServletContainer(shutdownHandler));
                colibriContextHandler.addServlet(shutdownServletHolder, "/shutdown/*");
            }

            handlers.add(colibriContextHandler);
        }

        ServletContextHandler aboutContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        aboutContextHandler.setContextPath("/about");

        HealthApp healthHandler = new HealthApp(videobridgeProvider);
        ServletHolder healthServletHolder = new ServletHolder(new ServletContainer(healthHandler));
        aboutContextHandler.addServlet(healthServletHolder, "/health/*");

        VersionServiceProvider versionServiceProvider = new VersionServiceProvider(bundleContext);
        VersionApp versionHandler = new VersionApp(versionServiceProvider);
        ServletHolder versionServletHolder = new ServletHolder(new ServletContainer(versionHandler));
        aboutContextHandler.addServlet(versionServletHolder, "/version/*");

        handlers.add(aboutContextHandler);

        return initializeHandlerList(handlers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean willStart(BundleContext bundleContext)
        throws Exception
    {
        boolean b = super.willStart(bundleContext);

        if (b)
        {
            // The REST API of Videobridge does not start by default.
            b = getCfgBoolean(Videobridge.REST_API_PNAME, false);
        }
        return b;
    }
}
