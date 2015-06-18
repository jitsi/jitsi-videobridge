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

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import org.eclipse.jetty.rewrite.handler.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.resource.*;
import org.eclipse.jetty.util.ssl.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

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
    implements BundleActivator
{
    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables graceful shutdown through REST API.
     * It is disabled by default.
     */
    private static final String ENABLE_REST_SHUTDOWN_PNAME
        = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    private static final String JETTY_HOST_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.host";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the port on which the REST API of Videobridge is
     * to be served over HTTP. The default value is <tt>8080</tt>.
     */
    private static final String JETTY_PORT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.port";

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
    private static final String JETTY_RESOURCE_HANDLER_ALIAS_PREFIX
        = Videobridge.REST_API_PNAME + ".jetty.ResourceHandler.alias";

    private static final String JETTY_REWRITE_HANDLER_REGEX_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.RewriteHandler.regex";

    private static final String JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.RewriteHandler.replacement";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore password to be utilized by
     * <tt>SslContextFactory</tt> when the REST API of Videobridge is served
     * over HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD
        = Videobridge.REST_API_PNAME
            + ".jetty.sslContextFactory.keyStorePassword";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore path to be utilized by
     * <tt>SslContextFactory</tt> when the REST API of Videobridge is served
     * over HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH
        = Videobridge.REST_API_PNAME + ".jetty.sslContextFactory.keyStorePath";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies whether client certificate authentication is to
     * be required by <tt>SslContextFactory</tt> when the REST API of
     * Videobridge is served over HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH
        = Videobridge.REST_API_PNAME
            + ".jetty.sslContextFactory.needClientAuth";

    /**
     * The name of the <tt>System</tt> and/or <tt>ConfigurationService</tt>
     * property which specifies the port on which the REST API of Videobridge is
     * to be served over HTTPS. The default value is <tt>8443</tt>.
     */
    private static final String JETTY_TLS_PORT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.tls.port";

    /**
     * The <tt>Logger</tt> used by the <tt>RESTBundleActivator</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RESTBundleActivator.class);

    /**
     * The {@code ConfigurationService} which looks up values of configuration
     * properties.
     */
    private ConfigurationService cfg;

    /**
     * The Jetty <tt>Server</tt> which provides the HTTP(S) interface to the
     * REST API of Videobridge.
     */
    private Server server;

    /**
     * Returns the value of a specific {@code boolean}
     * {@code ConfigurationService} or {@code System} property.
     *
     * @param property the name of the property
     * @param defaultValue the value to be returned if {@code property} does not
     * have any value assigned in either {@code ConfigurationService} or
     * {@code System}
     * @return the value of {@code property} in {@code ConfigurationService} or
     * {@code System}
     */
    private boolean getCfgBoolean(String property, boolean defaultValue)
    {
        ConfigurationService cfg = this.cfg;
        boolean b;

        if (cfg == null)
        {
            String s = System.getProperty(property);

            b
                = (s == null || s.length() == 0)
                    ? defaultValue
                    : Boolean.parseBoolean(s);
        }
        else
        {
            b = cfg.getBoolean(property, defaultValue);
        }
        return b;
    }

    /**
     * Returns the value of a specific {@code int} {@code ConfigurationService}
     * or {@code System} property.
     *
     * @param property the name of the property
     * @param defaultValue the value to be returned if {@code property} does not
     * have any value assigned in either {@code ConfigurationService} or
     * {@code System}
     * @return the value of {@code property} in {@code ConfigurationService} or
     * {@code System}
     */
    private int getCfgInt(String property, int defaultValue)
    {
        ConfigurationService cfg = this.cfg;
        int i;

        if (cfg == null)
        {
            String s = System.getProperty(property);

            if (s == null || s.length() == 0)
            {
                i = defaultValue;
            }
            else
            {
                try
                {
                    i = Integer.parseInt(s);
                }
                catch (NumberFormatException nfe)
                {
                    i = defaultValue;
                }
            }
        }
        else
        {
            i = cfg.getInt(property, defaultValue);
        }
        return i;
    }

    /**
     * Returns the value of a specific {@code String}
     * {@code ConfigurationService} or {@code System} property.
     *
     * @param property the name of the property
     * @param defaultValue the value to be returned if {@code property} does not
     * have any value assigned in either {@code ConfigurationService} or
     * {@code System}
     * @return the value of {@code property} in {@code ConfigurationService} or
     * {@code System}
     */
    private String getCfgString(String property, String defaultValue)
    {
        ConfigurationService cfg = this.cfg;
        String s;

        if (cfg == null)
            s = System.getProperty(property, defaultValue);
        else
            s = cfg.getString(property, defaultValue);
        return s;
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
                    getCfgBoolean(ENABLE_REST_SHUTDOWN_PNAME, false));
    }

    /**
     * Initializes a new {@link Handler} instance to be set on a specific
     * {@code Server} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} on which the new instance will be set
     * @return the new {code Handler} instance to be set on {@code server}
     */
    private Handler initializeHandler(
            BundleContext bundleContext,
            Server server)
    {
        // The main content served by Server. It may include, for example, the
        // /colibri target of the REST API, purely static content, and
        // ProxyServlet.
        Handler handler = initializeHandlerList(bundleContext, server);

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
     * Initializes a new {@link HandlerList} instance to be set on a specific
     * {@code Server} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} on which the new instance will be set
     * @return the new {code HandlerList} instance to be set on {@code server}
     */
    private Handler initializeHandlerList(
            BundleContext bundleContext,
            Server server)
    {
        List<Handler> handlers = new ArrayList<Handler>();

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
        Handler servletHandler
            = initializeServletHandler(bundleContext, server);

        if (servletHandler != null)
            handlers.add(servletHandler);

        int handlerCount = handlers.size();

        if (handlerCount == 1)
        {
            return handlers.get(0);
        }
        else
        {
            HandlerList handlerList = new HandlerList();

            handlerList.setHandlers(
                    handlers.toArray(new Handler[handlerCount]));
            return handlerList;
        }
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
        ResourceHandler resourceHandler;

        if (resourceBase == null || resourceBase.length() == 0)
        {
            resourceHandler = null;
        }
        else
        {
            resourceHandler = new ResourceHandler();
            resourceHandler.setResourceBase(resourceBase);
        }

        // Create context handler and enable alisases, so we can handle
        // symlinks
        ContextHandler contextHandler=new ContextHandler();
        contextHandler.setHandler(resourceHandler);
        contextHandler.addAliasCheck(new ContextHandler.ApproveAliases());

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
        return new ResourceHandler()
        {
            /**
             * Checks whether there is configured alias/link for the path
             * and if there is use the configured value as resource to return.
             * @param path the path to check
             * @return the resource to server.
             * @throws MalformedURLException
             */
            public Resource getResource(String path)
                throws MalformedURLException
            {
                String value = getCfgString(
                    JETTY_RESOURCE_HANDLER_ALIAS_PREFIX + "." + path, null);

                if(value == null)
                    return null;

                return Resource.newResource(value);
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
        String pathSpec
            = getCfgString(JETTY_PROXY_SERVLET_PATH_SPEC_PNAME, null);
        Handler handler = null;

        if (pathSpec != null && pathSpec.length() != 0)
        {
            String proxyTo
                = getCfgString(JETTY_PROXY_SERVLET_PROXY_TO_PNAME, null);

            if (proxyTo != null && proxyTo.length() != 0)
            {
                ServletHolder holder = new ServletHolder();

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

                ServletContextHandler servletContextHandler
                    = new ServletContextHandler();

                servletContextHandler.addServlet(holder, pathSpec);
                servletContextHandler.setContextPath("/");

                handler = servletContextHandler;
            }
        }
        return handler;
    }

    /**
     * Starts the OSGi bundle which implements a REST API for Videobridge in a
     * specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the OSGi bundle
     * which implements a REST API for Videobridge is to start
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);

        // The REST API of Videobridge does not start by default.
        if (!getCfgBoolean(Videobridge.REST_API_PNAME, false))
        {
            cfg = null;
            return;
        }

        try
        {
            Server server = new Server();
            HttpConfiguration httpCfg = new HttpConfiguration();
            int tlsPort = getCfgInt(JETTY_TLS_PORT_PNAME, 8443);

            httpCfg.setSecurePort(tlsPort);
            httpCfg.setSecureScheme("https");

            String sslContextFactoryKeyStorePath
                = getCfgString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH, null);
            ServerConnector connector;

            // If HTTPS is not enabled, serve the REST API of Jitsi Videobridge
            // over HTTP.
            if (sslContextFactoryKeyStorePath == null)
            {
                // HTTP
                connector
                    = new MuxServerConnector(
                            server,
                            new HttpConnectionFactory(httpCfg));
                connector.setPort(getCfgInt(JETTY_PORT_PNAME, 8080));
            }
            else
            {
                // HTTPS
                File sslContextFactoryKeyStoreFile
                    = ConfigUtils.getAbsoluteFile(
                            sslContextFactoryKeyStorePath,
                            cfg);
                SslContextFactory sslContextFactory = new SslContextFactory();
                String sslContextFactoryKeyStorePassword
                    = getCfgString(
                            JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD,
                            null);
                boolean sslContextFactoryNeedClientAuth
                    = getCfgBoolean(
                        JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH,
                        false);

                sslContextFactory.setExcludeCipherSuites(
                    "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
                    ".*NULL.*",
                    ".*RC4.*",
                    ".*MD5.*",
                    ".*DES.*",
                    ".*DSS.*");
                sslContextFactory.setIncludeCipherSuites(
                    "TLS_DHE_RSA.*",
                    "TLS_ECDHE.*");
                sslContextFactory.setExcludeProtocols("SSLv3");
                sslContextFactory.setRenegotiationAllowed(false);
                if (sslContextFactoryKeyStorePassword != null)
                {
                    sslContextFactory.setKeyStorePassword(
                            sslContextFactoryKeyStorePassword);
                }
                sslContextFactory.setKeyStorePath(
                        sslContextFactoryKeyStoreFile.getPath());
                sslContextFactory.setNeedClientAuth(
                        sslContextFactoryNeedClientAuth);

                HttpConfiguration httpsCfg = new HttpConfiguration(httpCfg);

                httpsCfg.addCustomizer(new SecureRequestCustomizer());

                connector
                    = new MuxServerConnector(
                            server,
                            new SslConnectionFactory(
                                    sslContextFactory,
                                    "http/1.1"),
                            new HttpConnectionFactory(httpsCfg));
                connector.setPort(tlsPort);
            }

            String host = getCfgString(JETTY_HOST_PNAME, null);

            if (host != null)
                connector.setHost(host);
            server.addConnector(connector);

            Handler handler = initializeHandler(bundleContext, server);

            if (handler != null)
                server.setHandler(handler);

            // The server will start a non-daemon background Thread which will
            // keep the application running on success.
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
     * Stops the OSGi bundle which implements a REST API for Videobridge in a
     * specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the OSGi bundle
     * which implements a REST API for Videobridge is to stop
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (server != null)
        {
            // FIXME graceful Jetty shutdown
            // When shutdown request is accepted, empty response is sent back
            // instead of 200, because Jetty is not being shutdown gracefully.
            Thread.sleep(1000);

            server.stop();
            server = null;
        }

        cfg = null;
    }
}
