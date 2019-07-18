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

import org.eclipse.jetty.rewrite.handler.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.servlets.*;
import org.eclipse.jetty.util.resource.*;
import org.jitsi.rest.*;
import org.jitsi.util.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.rest.ssi.*;
import org.osgi.framework.*;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle which implements a
 * publicly accessible HTTP API.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class PublicRESTBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The logger instance used by this
     * {@link PublicClearPortRedirectBundleActivator}.
     */
    private static final Logger logger
        = Logger.getLogger(PublicRESTBundleActivator.class);

    /**
     * The prefix of the property names for the Jetty instance managed by
     * this {@link AbstractJettyBundleActivator}.
     */
    public static final String JETTY_PROPERTY_PREFIX
        = "org.jitsi.videobridge.rest";

    public static final String JETTY_PROXY_SERVLET_HOST_HEADER_PNAME
        = ".jetty.ProxyServlet.hostHeader";

    public static final String JETTY_PROXY_SERVLET_PATH_SPEC_PNAME
        = ".jetty.ProxyServlet.pathSpec";

    public static final String JETTY_PROXY_SERVLET_PROXY_TO_PNAME
        = ".jetty.ProxyServlet.proxyTo";

    public static final String JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME
        = ".jetty.ResourceHandler.resourceBase";

    public static final String JETTY_CORS_ALLOWED_ORIGINS
        = ".jetty.cors.allowedOrigins";

    /**
     * Prefix that can configure multiple location aliases.
     * rest.api.jetty.ResourceHandler.alias./config.js=/etc/jitsi/my-config.js
     * rest.api.jetty.ResourceHandler.alias./settings.js=/etc/jitsi/my-sets.js
     */
    public static final String JETTY_RESOURCE_HANDLER_ALIAS_PREFIX
        = ".jetty.ResourceHandler.alias";

    public static final String JETTY_REWRITE_HANDLER_REGEX_PNAME
        = ".jetty.RewriteHandler.regex";

    public static final String JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME
        = ".jetty.RewriteHandler.replacement";

    /**
     * The {@link ColibriWebSocketService}, which handles the WebSockets used
     * for COLIBRI.
     */
    private ColibriWebSocketService colibriWebSocketService;

    /**
     * Initializes a new {@link PublicRESTBundleActivator}.
     */
    public PublicRESTBundleActivator()
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
    protected Handler initializeHandler(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        // The main content served by Server. It may include, for example,
        // purely static content, and ProxyServlet.
        Handler handler = super.initializeHandler(bundleContext, server);

        // When handling requests, the main content may be superseded by
        // RewriteHandler.
        HandlerWrapper rewriteHandler
            = initializeRewriteHandler(bundleContext, server);

        if (rewriteHandler != null)
        {
            rewriteHandler.setHandler(handler);
            handler = rewriteHandler;
        }

        return handler;
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

        // Purely static content.
        Handler resourceHandler
            = initializeResourceHandler(bundleContext, server);

        if (resourceHandler != null)
            handlers.add(resourceHandler);

        Handler aliasHandler
            = initializeResourceHandlerAliases(bundleContext, server);

        if (aliasHandler != null)
            handlers.add(aliasHandler);

        Handler redirectHandler
            = initializeRedirectHandler(bundleContext, server);

        if (redirectHandler != null)
        {
            handlers.add(redirectHandler);
        }

        // ServletHandler to serve, for example, ProxyServlet.

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
     * Initializes a new {@code ServletHolder} instance which is to support
     * long polling with asynchronous HTTP request handling and adds it to a
     * specific {@code ServletContextHandler}.
     *
     * @param servletContextHandler the {@code ServletContextHandler} to add the
     * new instance to
     * @return a new {@code ServletHolder} instance which implements support for
     * long polling with asynchronous HTTP request handling and has been added
     * to {@code servletContextHandler}
     */
    private ServletHolder initializeLongPollingServlet(
            ServletContextHandler servletContextHandler)
    {
        ServletHolder holder = new ServletHolder();

        holder.setServlet(new LongPollingServlet());

        // The rules for mappings of the Servlet specification do not allow path
        // matching in the middle of the path.
        servletContextHandler.addServlet(
                holder,
                HandlerImpl.COLIBRI_TARGET + "*");

        return holder;
    }

    /**
     * Initializes a new {@code ServletHolder} instance which is implement
     * {@code /http-bind} and adds it to a specific
     * {@code ServletContextHandler}.
     *
     * @param servletContextHandler the {@code ServletContextHandler} to add the
     * new instance to
     * @return a new {@code ServletHolder} instance which implements
     * {@code /http-bind} and has been added to {@code servletContextHandler}
     */
    private ServletHolder initializeProxyServlet(
            ServletContextHandler servletContextHandler)
    {
        String pathSpec
            = ConfigUtils.getString(
                cfg,
                JETTY_PROPERTY_PREFIX + JETTY_PROXY_SERVLET_PATH_SPEC_PNAME,
                null);
        ServletHolder holder = null;

        if (pathSpec != null && pathSpec.length() != 0)
        {
            String proxyTo
                = ConfigUtils.getString(
                    cfg,
                    JETTY_PROPERTY_PREFIX + JETTY_PROXY_SERVLET_PROXY_TO_PNAME,
                    null);

            if (proxyTo != null && proxyTo.length() != 0)
            {
                holder = new ServletHolder();
                holder.setHeldClass(ProxyServletImpl.class);
                // XXX ProxyServlet will throw an IllegalStateException without
                // maxThreads. The documentation on ProxyServlet says the
                // default value is 256.
                holder.setInitParameter("maxThreads", Integer.toString(256));
                holder.setInitParameter("prefix", pathSpec);
                holder.setInitParameter("proxyTo", proxyTo);

                // hostHeader
                String hostHeader
                    = ConfigUtils.getString(
                        cfg,
                        JETTY_PROPERTY_PREFIX
                            + JETTY_PROXY_SERVLET_HOST_HEADER_PNAME,
                        null);

                if (hostHeader != null && hostHeader.length() != 0)
                    holder.setInitParameter("hostHeader", hostHeader);

                servletContextHandler.addServlet(holder, pathSpec);

                // CORS
                String allowedOrigins
                    = ConfigUtils.getString(
                        cfg,
                        JETTY_PROPERTY_PREFIX
                            + JETTY_CORS_ALLOWED_ORIGINS,
                        null);
                if (allowedOrigins != null && allowedOrigins.length() != 0)
                {
                    FilterHolder filterHolder = servletContextHandler.addFilter(
                        CrossOriginFilter.class,
                        "/*",
                        EnumSet.of(DispatcherType.REQUEST)
                    );
                    filterHolder.setInitParameter(
                        CrossOriginFilter.ALLOWED_ORIGINS_PARAM,
                        allowedOrigins
                    );
                }

                servletContextHandler.addFilter(
                    TraceFilter.class,
                    "/*",
                    EnumSet.of(DispatcherType.REQUEST)
                );
            }
        }
        return holder;
    }

    /**
     * Initializes a new {@link Handler} instance which is to serve purely
     * static content for a specific {@code Server} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to serve
     * purely static content
     * @return a new {@code Handler} instance which is to serve purely static
     * content for {@code server}
     */
    private Handler initializeResourceHandler(
            BundleContext bundleContext,
            Server server)
    {
        String resourceBase
            = ConfigUtils.getString(
                cfg,
                JETTY_PROPERTY_PREFIX
                    + JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME,
                null);
        ContextHandler contextHandler;

        if (resourceBase == null || resourceBase.length() == 0)
        {
            contextHandler = null;
        }
        else
        {
            ResourceHandler resourceHandler = new SSIResourceHandler(cfg);

            resourceHandler.setResourceBase(resourceBase);

            // Enable aliases so we can handle symlinks.
            contextHandler = new ContextHandler();
            contextHandler.setHandler(resourceHandler);
            contextHandler.addAliasCheck(new ContextHandler.ApproveAliases());
        }

        return contextHandler;
    }

    /**
     * Initializes a new {@link Handler} instance which is to serve purely
     * static content for a specific {@code Server} instance and only the
     * aliases configured.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to serve
     * purely static content
     * @return a new {@code Handler} instance which is to serve purely static
     * content for {@code server}
     */
    private Handler initializeResourceHandlerAliases(
            BundleContext bundleContext,
            Server server)
    {
        return
            new ResourceHandler()
            {
                /**
                 * Checks whether there is configured alias/link for the path
                 * and, if there is, uses the configured value as resource to
                 * return.
                 *
                 * @param path the path to check
                 * @return the resource to server.
                 */
                @Override
                public Resource getResource(String path)
                {
                    String property
                        = JETTY_RESOURCE_HANDLER_ALIAS_PREFIX + "." + path;
                    String value
                        = ConfigUtils.getString(
                            cfg,
                            JETTY_PROPERTY_PREFIX + property,
                            null);

                    try
                    {
                        return (value == null)
                            ? null : Resource.newResource(value);
                    }
                    catch(IOException e)
                    {
                        logger.info("Error constructing resource.", e);
                        return null;
                    }
                }
            };
    }

    /**
     * Initializes a new {@link Handler} instance which is to redirect requests
     * for certain targets which were previously accessible through the public
     * HTTP interface to their new location (via the private interface).
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to serve
     * purely static content
     */
    private Handler initializeRedirectHandler(
        BundleContext bundleContext,
        Server server)
    {
        // We only need this redirect for backward compatibility, so the code
        // is ad-hoc.

        String privateSslContextFactoryKeyStorePath
            = getCfgString(
                RESTBundleActivator.JETTY_PROPERTY_PREFIX
                    + ".jetty.sslContextFactory.keyStorePath",
                null);

        int privatePort;
        if (privateSslContextFactoryKeyStorePath == null)
        {
            privatePort
                = cfg.getInt(
                    RESTBundleActivator.JETTY_PROPERTY_PREFIX
                        + JETTY_PORT_PNAME,
                    8080);
        }
        else
        {
            privatePort
                = cfg.getInt(
                    RESTBundleActivator.JETTY_PROPERTY_PREFIX
                        + JETTY_TLS_PORT_PNAME,
                    8443);
        }

        if (privatePort > 0)
        {
            return new RedirectHandler(
                privateSslContextFactoryKeyStorePath == null ? "http" : "https",
                privatePort);
        }

        return null;
    }

    /**
     * Initializes a new {@link HandlerWrapper} instance which is to match
     * requests against a set of rules and modify them accordingly for any rules
     * that match.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} for which the new instance is to match
     * requests against a set of rules and modify them accordingly for any rules
     * that match
     * @return a new {@code HandlerWrapper} instance which is to match requests
     * against a set of rules and modify them accordingly for any rules that
     * match
     */
    private HandlerWrapper initializeRewriteHandler(
            BundleContext bundleContext,
            Server server)
    {
        String regex = ConfigUtils.getString(
            cfg,
            JETTY_PROPERTY_PREFIX + JETTY_REWRITE_HANDLER_REGEX_PNAME,
            null);
        RewriteHandler handler = null;

        if (regex != null && regex.length() != 0)
        {
            String replacement
                = ConfigUtils.getString(
                    cfg,
                    JETTY_PROPERTY_PREFIX +
                        JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME,
                    null);

            if (replacement != null)
            {
                RewriteRegexRule rule = new RewriteRegexRule();

                rule.setRegex(regex);
                rule.setReplacement(replacement);

                handler = new RewriteHandler();
                handler.addRule(rule);
            }
        }
        return handler;
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
        boolean b = false;

        // ProxyServletImpl i.e. http-bind.
        servletHolder = initializeProxyServlet(servletContextHandler);
        if (servletHolder != null)
        {
            b = true;
        }

        // LongPollingServlet
        servletHolder = initializeLongPollingServlet(servletContextHandler);
        if (servletHolder != null)
        {
            b = true;
        }

        // Colibri WebSockets
        ColibriWebSocketService colibriWebSocketService
            = new ColibriWebSocketService(bundleContext, isTls());
        servletHolder
            = colibriWebSocketService.initializeColibriWebSocketServlet(
                    bundleContext,
                    servletContextHandler);
        if (servletHolder != null)
        {
            this.colibriWebSocketService = colibriWebSocketService;
            b = true;
        }

        if (b)
        {
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

    /**
     * Redirects requests for certain targets which were previously accessible
     * through the public HTTP interface to their new location (via the
     * private interface).
     */
    private class RedirectHandler extends AbstractHandler
    {
        /**
         * The protocol ("http" or "https") of the target location.
         */
        private final String targetProtocol;

        /**
         * The port of the target location.
         */
        private final int targetPort;

        /**
         * Initializes a new {@link RedirectHandler}.
         */
        RedirectHandler(String targetProtocol, int targetPort)
        {
            this.targetProtocol = targetProtocol;
            this.targetPort = targetPort;
        }

        /**
         * Handles requests for "/colibri/*" and "/about/*" by redirecting them
         * (with a 301) to the private interface.
         */
        @Override
        public void handle(String target, Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response)
            throws IOException, ServletException
        {
            if (target.startsWith("/colibri/") || target.startsWith("/about/"))
            {
                String host = request.getServerName();

                String location
                    = targetProtocol + "://" + host + ":" + targetPort + target;
                response.setHeader("Location", location);

                response.setStatus(301);
                response.setContentLength(0);
                baseRequest.setHandled(true);
            }
        }
    }
}
