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
import org.jitsi.rest.*;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

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
     * Initializes a new {@link Handler} instance which is to handle the
     * &quot;/colibri&quot; target for a specific {@code Server} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to handle
     * the &quot;/colibri&quot; target
     * @return a new {@code Handler} instance which is to handle the
     * &quot;/colibri&quot; target for {@code server}
     */
    private Handler initializeColibriHandler(
            BundleContext bundleContext,
            Server server)
    {
        return
            new HandlerImpl(
                    bundleContext,
                    getCfgBoolean(ENABLE_REST_SHUTDOWN_PNAME, false),
                    getCfgBoolean(ENABLE_REST_COLIBRI_PNAME, true));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Handler initializeHandlerList(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        List<Handler> handlers = new ArrayList<>();

        // The /colibri target of the REST API.
        Handler colibriHandler
            = initializeColibriHandler(bundleContext, server);

        if (colibriHandler != null)
            handlers.add(colibriHandler);

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
