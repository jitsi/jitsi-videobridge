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
package org.jitsi.videobridge.websocket;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.jitsi.rest.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle which implements a
 * publicly accessible HTTP API.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
@SuppressWarnings("unused")
public class WebSocketBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The prefix of the property names for the Jetty instance managed by
     * this {@link AbstractJettyBundleActivator}.
     */
    public static final String JETTY_PROPERTY_PREFIX
        = "org.jitsi.videobridge.rest";

    /**
     * The {@link ColibriWebSocketService}, which handles the WebSockets used
     * for COLIBRI.
     */
    private ColibriWebSocketService colibriWebSocketService;

    /**
     * Initializes a new {@link WebSocketBundleActivator}.
     */
    public WebSocketBundleActivator()
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
        throws Exception
    {
        List<Handler> handlers = new ArrayList<>();

        // XXX ServletContextHandler and/or ServletHandler are not cool because
        // they always mark HTTP Request as handled if it reaches a Servlet
        // regardless of whether the Servlet actually did anything.
        // Consequently, it is advisable to keep Servlets as the last Handler.
        Handler servletHandler
            = initializeServletHandler(bundleContext, server);

        if (servletHandler != null)
            handlers.add(servletHandler);

        return initializeHandlerList(handlers);
    }

    /**
     * Initializes a new {@link ServletHandler} instance which is to map
     * requests to servlets.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to map
     * requests to servlets
     * @return a new {@code ServletHandler} instance which is to map requests to
     * servlets for {@code server}
     */
    private Handler initializeServletHandler(
            BundleContext bundleContext,
            Server server)
    {
        ServletHolder servletHolder;
        ServletContextHandler servletContextHandler
            = new ServletContextHandler();

        // Colibri WebSockets
        ColibriWebSocketService colibriWebSocketService
            = new ColibriWebSocketService(isTls());
        servletHolder
            = colibriWebSocketService.initializeColibriWebSocketServlet(
                    bundleContext,
                    servletContextHandler);
        if (servletHolder != null)
        {
            this.colibriWebSocketService = colibriWebSocketService;
            servletContextHandler.setContextPath("/");
        }
        else
        {
            servletContextHandler = null;
        }

        return servletContextHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getDefaultPort()
    {
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getDefaultTlsPort()
    {
        return -1;
    }

    /**
     * {@inheritDoc}
     * </p>
     * Registers the colibri web socket service, if it was has been initialized,
     * as a service in the specified {@link BundleContext}.
     */
    @Override
    protected void didStart(BundleContext bundleContext)
        throws Exception
    {
        super.didStart(bundleContext);

        if (colibriWebSocketService != null)
        {
            bundleContext.registerService(
                ColibriWebSocketService.class.getName(),
                colibriWebSocketService,
                null);
        }
    }
}
