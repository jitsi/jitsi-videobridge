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
    public static final String ENABLE_REST_SHUTDOWN_PNAME
        = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables <tt>/colibri/*</tt> REST API endpoints.
     */
    public static final String ENABLE_REST_COLIBRI_PNAME
      = "org.jitsi.videobridge.ENABLE_REST_COLIBRI";

    public static final String JETTY_PROXY_SERVLET_HOST_HEADER_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.hostHeader";

    public static final String JETTY_PROXY_SERVLET_PATH_SPEC_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.pathSpec";

    public static final String JETTY_PROXY_SERVLET_PROXY_TO_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ProxyServlet.proxyTo";

    public static final String JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.ResourceHandler.resourceBase";

    /**
     * Prefix that can configure multiple location aliases.
     * rest.api.jetty.ResourceHandler.alias./config.js=/etc/jitsi/my-config.js
     * rest.api.jetty.ResourceHandler.alias./settings.js=/etc/jitsi/my-sets.js
     */
    public static final String JETTY_RESOURCE_HANDLER_ALIAS_PREFIX
        = Videobridge.REST_API_PNAME + ".jetty.ResourceHandler.alias";

    public static final String JETTY_REWRITE_HANDLER_REGEX_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.RewriteHandler.regex";

    public static final String JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME
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
        if (privateServer != null)
        {
            // FIXME graceful Jetty shutdown
            // When a shutdown request is accepted, empty response is sent back
            // instead of 200, because Jetty is not being shutdown gracefully.
            Thread.sleep(1000);
        }

        super.doStop(bundleContext);
    }

    /**
     * Initializes a new {@link Handler} instance which is to handle the
     * &quot;/colibri&quot; target and adds it to the appropriate lists of
     * handlers (specified in {@code privateHandlers} and
     * {@code publicHandlers}) according to the desired public or private
     * exposure.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param privateHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the private
     * interface/port, or {@code null} if the private interface is disabled and
     * no handlers are to be initialized for it.
     * @param publicHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the public
     * interface/port, or {@code null} if the public interface is disabled and
     * no handlers are to be initialized for it.
     */
    private void initializeColibriHandlers(
            BundleContext bundleContext,
            List<Handler> privateHandlers,
            List<Handler> publicHandlers)
    {
        // The colibri control interface is private only.
        if (privateHandlers != null)
        {
            privateHandlers.add(new HandlerImpl(
                bundleContext,
                getCfgBoolean(ENABLE_REST_SHUTDOWN_PNAME, false),
                getCfgBoolean(ENABLE_REST_COLIBRI_PNAME, true)));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Handler[] initializeHandlers(
            BundleContext bundleContext,
            Server privateServer,
            Server publicServer)
        throws Exception
    {
        // The main content served by Server. It may include, for example, the
        // /colibri target of the REST API, purely static content, and
        // ProxyServlet.
        Handler[] handlers
            = super.initializeHandlers(
                bundleContext, privateServer, publicServer);

        for (int i = 0; i < handlers.length; i++)
        {
            if (handlers[i] != null)
            {
                // When handling requests, the main content may be superseded by
                // RewriteHandler. Note that currently we use the same set of
                // rules for both the private and public servers, but we
                // create separate Handler instances, because it is not clear
                // whether it is safe to share a Handler between two Servers.
                HandlerWrapper wrapper = initializeRewriteHandler(bundleContext);
                if (wrapper != null)
                {
                    wrapper.setHandler(handlers[i]);
                    handlers[i] = wrapper;
                }
            }
        }

        return handlers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Handler[] initializeHandlerLists(
            BundleContext bundleContext,
            Server privateServer,
            Server publicServer)
        throws Exception
    {
        List<Handler> privateHandlers
            = privateServer == null ? null : new ArrayList<Handler>();
        List<Handler> publicHandlers
            = publicServer == null ? null : new ArrayList<Handler>();

        // The /colibri target of the REST API.
        initializeColibriHandlers(
                bundleContext, privateHandlers, publicHandlers);

        // Purely static content.
        initializeResourceHandler(
                bundleContext, privateHandlers, publicHandlers);

        initializeResourceHandlerAliases(
                bundleContext, privateHandlers, publicHandlers);

        // ServletHandler to serve, for example, ProxyServlet.

        // XXX ServletContextHandler and/or ServletHandler are not cool because
        // they always mark HTTP Request as handled if it reaches a Servlet
        // regardless of whether the Servlet actually did anything.
        // Consequently, it is advisable to keep Servlets as the last Handler.
        initializeServletHandler(bundleContext, privateHandlers, publicHandlers);

        return new Handler[]
            {
                initializeHandlerList(privateHandlers),
                initializeHandlerList(publicHandlers)
            };
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
     * static content and adds it to the appropriate lists of handlers
     * (specified in {@code privateHandlers} and {@code publicHandlers})
     * according to the desired public or private exposure.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized.
     * @param privateHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the private
     * interface/port, or {@code null} if the private interface is disabled and
     * no handlers are to be initialized for it.
     * @param publicHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the public
     * interface/port, or {@code null} if the public interface is disabled and
     * no handlers are to be initialized for it.
     */
    private void initializeResourceHandler(
            BundleContext bundleContext,
            List<Handler> privateHandlers,
            List<Handler> publicHandlers)
    {
        if (publicHandlers != null)
        {
            String resourceBase
                = getCfgString(JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME,
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

                // Enable alisases so we can handle symlinks.
                contextHandler = new ContextHandler();
                contextHandler.setHandler(resourceHandler);
                contextHandler
                    .addAliasCheck(new ContextHandler.ApproveAliases());
            }

            if (contextHandler != null)
            {
                publicHandlers.add(contextHandler);
            }
        }
    }

    /**
     * Initializes a new {@link Handler} instance which is to serve purely
     * static content and adds it to the appropriate lists of handlers
     * (specified in {@code privateHandlers} and {@code publicHandlers})
     * according to the desired public or private exposure.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized.
     * @param privateHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the private
     * interface/port, or {@code null} if the private interface is disabled and
     * no handlers are to be initialized for it.
     * @param publicHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the public
     * interface/port, or {@code null} if the public interface is disabled and
     * no handlers are to be initialized for it.
     */
    private void initializeResourceHandlerAliases(
            BundleContext bundleContext,
            List<Handler> privateHandlers,
            List<Handler> publicHandlers)
    {
        if (publicHandlers != null)
        {
            publicHandlers.add(
                new ResourceHandler()
                {
                    /**
                     * Checks whether there is configured alias/link for the
                     * path and, if there is, uses the configured value as
                     * resource to return.
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

                        return (value == null) ? null : Resource
                            .newResource(value);
                    }
                }
            );
        }
    }

    /**
     * Initializes a new {@link HandlerWrapper} instance which is to match
     * requests against a set of rules and modify them accordingly for any rules
     * that match.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @return a new {@code HandlerWrapper} instance which is to match requests
     * against a set of rules and modify them accordingly for any rules that
     * match
     */
    private HandlerWrapper initializeRewriteHandler(BundleContext bundleContext)
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
     * requests to servlets, and adds it to the appropriate lists of
     * handlers (specified in {@code privateHandlers} and
     * {@code publicHandlers}) according to the desired public or private
     * exposure.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param privateHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the private
     * interface/port, or {@code null} if the private interface is disabled and
     * no handlers are to be initialized for it.
     * @param publicHandlers the list to which to add any newly initialized
     * {@link Handler}s if they are to be accessible on the public
     * interface/port, or {@code null} if the public interface is disabled and
     * no handlers are to be initialized for it.
     */
    private void initializeServletHandler(
            BundleContext bundleContext,
            List<Handler> privateHandlers,
            List<Handler> publicHandlers)
    {
        // All of the current servlets need to be (only) publicly accessible
        if (publicHandlers != null)
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
            {
                servletContextHandler.setContextPath("/");
                publicHandlers.add(servletContextHandler);
            }
        }
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
