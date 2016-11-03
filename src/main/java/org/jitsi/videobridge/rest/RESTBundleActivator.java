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
package org.jitsi.videobridge.rest;

import java.net.*;
import java.util.*;

import org.eclipse.jetty.rewrite.handler.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.resource.*;
import org.jitsi.rest.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.ssi.*;
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
 */
public class RESTBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables graceful shutdown through REST API.
     * It is disabled by default.
     */
    private static final String ENABLE_REST_SHUTDOWN_PNAME
        = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables <tt>/colibri/*</tt> REST API endpoints.
     */
    private static final String ENABLE_REST_COLIBRI_PNAME
      = "org.jitsi.videobridge.ENABLE_REST_COLIBRI";

    private static final String JETTY_PROXY_SERVLET_HOST_HEADER_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.hostHeader";

    private static final String JETTY_PROXY_SERVLET_PATH_SPEC_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.pathSpec";

    private static final String JETTY_PROXY_SERVLET_PROXY_TO_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.proxyTo";

    private static final String JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ResourceHandler.resourceBase";

    /**
     * Prefix that can configure multiple location aliases.
     * rest.api.jetty.ResourceHandler.alias./config.js=/etc/jitsi/my-config.js
     * rest.api.jetty.ResourceHandler.alias./settings.js=/etc/jitsi/my-sets.js
     */
    public static final String JETTY_RESOURCE_HANDLER_ALIAS_PREFIX
        = Videobridge.REST_API_PNAME + ".jetty.ResourceHandler.alias";

    private static final String JETTY_REWRITE_HANDLER_REGEX_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.RewriteHandler.regex";

    private static final String JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.RewriteHandler.replacement";

    /**
     * Initializes a new {@code RESTBundleActivator} instance.
     */
    public RESTBundleActivator()
    {
        super(Videobridge.REST_API_PNAME);
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
    protected Handler initializeHandler(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        // The main content served by Server. It may include, for example, the
        // /colibri target of the REST API, purely static content, and
        // ProxyServlet.
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

        // The /colibri target of the REST API.
        Handler colibriHandler
            = initializeColibriHandler(bundleContext, server);

        if (colibriHandler != null)
            handlers.add(colibriHandler);

        // Purely static content.
        Handler resourceHandler
            = initializeResourceHandler(bundleContext, server);

        if (resourceHandler != null)
            handlers.add(resourceHandler);

        Handler aliasHandler
            = initializeResourceHandlerAliases(bundleContext, server);

        if (aliasHandler != null)
            handlers.add(aliasHandler);

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
            = getCfgString(JETTY_PROXY_SERVLET_PATH_SPEC_PNAME, null);
        ServletHolder holder = null;

        if (pathSpec != null && pathSpec.length() != 0)
        {
            String proxyTo
                = getCfgString(JETTY_PROXY_SERVLET_PROXY_TO_PNAME, null);

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
                    = getCfgString(JETTY_PROXY_SERVLET_HOST_HEADER_PNAME, null);

                if (hostHeader != null && hostHeader.length() != 0)
                    holder.setInitParameter("hostHeader", hostHeader);

                servletContextHandler.addServlet(holder, pathSpec);
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
            = getCfgString(JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME, null);
        ContextHandler contextHandler;

        if (resourceBase == null || resourceBase.length() == 0)
        {
            contextHandler = null;
        }
        else
        {
            ResourceHandler resourceHandler = new SSIResourceHandler(cfg);

            resourceHandler.setResourceBase(resourceBase);

            // Enable alisases so we can handle symlinks.
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
                 * @throws MalformedURLException
                 */
                @Override
                public Resource getResource(String path)
                    throws MalformedURLException
                {
                    String value
                        = getCfgString(
                                JETTY_RESOURCE_HANDLER_ALIAS_PREFIX + "."
                                    + path,
                                null);

                    return (value == null) ? null : Resource.newResource(value);
                }
            };
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
        String regex = getCfgString(JETTY_REWRITE_HANDLER_REGEX_PNAME, null);
        RewriteHandler handler = null;

        if (regex != null && regex.length() != 0)
        {
            String replacement
                = getCfgString(JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME, null);

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
            b = true;

        // LongPollingServlet
        servletHolder = initializeLongPollingServlet(servletContextHandler);
        if (servletHolder != null)
            b = true;

        if (b)
            servletContextHandler.setContextPath("/");
        else
            servletContextHandler = null;

        return servletContextHandler;
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
