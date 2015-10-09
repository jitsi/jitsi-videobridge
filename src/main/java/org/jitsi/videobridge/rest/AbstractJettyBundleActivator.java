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
import java.util.*;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.util.ssl.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

/**
 * Implements an abstract {@code BundleActivator} which starts and stops a Jetty
 * HTTP(S) server instance within OSGi.
 *
 * @author Lyubomir Marinov
 */
public abstract class AbstractJettyBundleActivator
    implements BundleActivator
{
    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies the Jetty HTTP server host.
     */
    private static final String JETTY_HOST_PNAME = ".jetty.host";

    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies the Jetty HTTP server port. The default value is
     * {@code 8080}.
     */
    static final String JETTY_PORT_PNAME = ".jetty.port";

    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies the keystore password to be utilized by
     * {@code SslContextFactory} when Jetty serves over HTTPS.
     */
    static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD
        = ".jetty.sslContextFactory.keyStorePassword";

    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies the keystore path to be utilized by
     * {@code SslContextFactory} when Jetty serves over HTTPS.
     */
    static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH
        = ".jetty.sslContextFactory.keyStorePath";

    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies whether client certificate authentication is to
     * be required by {@code SslContextFactory} when Jetty serves over HTTPS.
     */
    static final String JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH
        = ".jetty.sslContextFactory.needClientAuth";

    /**
     * The name of the {@code ConfigurationService} and/or {@code System}
     * property which specifies the Jetty HTTPS server port. The default value
     * is {@code 8443}.
     */
    static final String JETTY_TLS_PORT_PNAME = ".jetty.tls.port";

    /**
     * The {@code Logger} used by the {@code AbstractJettyBundleActivator} class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(AbstractJettyBundleActivator.class);

    /**
     * Initializes a new {@code Handler} which handles HTTP requests by
     * delegating to a specific (consecutive) list of {@code Handler}s.
     *
     * @param handlers the (consecutive) list of {@code Handler}s to which the
     * new instance is to delegate
     * @return a new {@code Handler} which will handle HTTP requests by
     * delegating to the specified {@code handlers}
     */
    protected static Handler initializeHandlerList(List<Handler> handlers)
    {
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
     * The {@code ConfigurationService} which looks up values of configuration
     * properties.
     */
    protected ConfigurationService cfg;

    /**
     * The prefix of the names of {@code ConfigurationService} and/or
     * {@code System} properties to be utilized by this instance.
     */
    protected final String propertyPrefix;

    /**
     * The Jetty {@code Server} which provides an HTTP(S) interface.
     */
    protected Server server;

    /**
     * Initializes a new {@code AbstractJettyBundleActivator} instance.
     *
     * @param propertyPrefix the prefix of the names of
     * {@code ConfigurationService} and/or {@code System} properties to be
     * utilized by the new instance
     */
    protected AbstractJettyBundleActivator(String propertyPrefix)
    {
        this.propertyPrefix = propertyPrefix;
    }

    /**
     * Notifies this {@code AbstractJettyBundleActivator} that a new Jetty
     * {@code Server} instance was initialized and started in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} was started and initialized and started a new
     * Jetty {@code Server} instance
     * @throws Exception
     */
    protected void didStart(BundleContext bundleContext)
        throws Exception
    {
    }

    /**
     * Notifies this {@code AbstractJettyBundleActivator} that the Jetty
     * {@code Server} instance associated with this instance was stopped and
     * released for garbage collection in a specific {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} was stopped
     * @throws Exception 
     */
    protected void didStop(BundleContext bundleContext)
        throws Exception
    {
    }

    /**
     * Initializes and starts a new Jetty {@code Server} instance in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} is started and to initialize and start a new
     * Jetty {@code Server} instance
     * @throws Exception
     */
    protected void doStart(BundleContext bundleContext)
        throws Exception
    {
        try
        {
            Server server = initializeServer(bundleContext);

            // The server will start a non-daemon background Thread which will
            // keep the application running on success.
            server.start();

            this.server = server;
        }
        catch (Throwable t)
        {
            // Log any Throwable for debugging purposes and rethrow.
            logger.error(
                    "Failed to initialize and/or start a new Jetty HTTP(S)"
                        + " server instance.",
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
     * Stops and releases for garbage collection the Jetty {@code Server}
     * instance associated with this instance in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} is stopped
     * @throws Exception 
     */
    protected void doStop(BundleContext bundleContext)
        throws Exception
    {
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }

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
    protected boolean getCfgBoolean(String property, boolean defaultValue)
    {
        return
            ConfigUtils.getBoolean(cfg, prefixProperty(property), defaultValue);
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
    protected int getCfgInt(String property, int defaultValue)
    {
        return ConfigUtils.getInt(cfg, prefixProperty(property), defaultValue);
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
    protected String getCfgString(String property, String defaultValue)
    {
        return
            ConfigUtils.getString(cfg, prefixProperty(property), defaultValue);
    }

    /**
     * Gets the port on which the Jetty server is to listen for HTTP requests by
     * default in the absence of a user specification through
     * {@link #JETTY_PORT_PNAME}.
     *
     * @return the port on which the Jetty server is to listen for HTTP requests
     * by default
     */
    protected int getDefaultPort()
    {
        return 8080;
    }

    /**
     * Gets the port on which the Jetty server is to listen for HTTPS requests
     * by default in the absence of a user specification through
     * {@link #JETTY_TLS_PORT_PNAME}.
     *
     * @return the port on which the Jetty server is to listen for HTTPS
     * requests by default
     */
    protected int getDefaultTlsPort()
    {
        return 8443;
    }

    /**
     * Initializes a new {@code Connector} instance to be added to a specific
     * {@code Server} which is to be started in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which {@code server} is
     * to be started
     * @param server the {@code Server} to which the new {@code Connector}
     * instance is to be added
     * @return a new {@code Connector} instance which is to be added to
     * {@code server}
     * @throws Exception 
     */
    protected Connector initializeConnector(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        // Detect whether we are running on Jetty 9. If not, fall back to Jetty
        // 8.
        String className;

        try
        {
            // The detection of Jetty 9 could be as simple/complex as necessary.
            Class.forName("org.eclipse.jetty.server.ConnectionFactory");
            className = "9";
        }
        catch (ClassNotFoundException cnfex)
        {
            // It appears that we are not running on Jetty 9. Fall back to Jetty
            // 8 then.
            className = "8";
        }

        Class<?> outerClass = AbstractJettyBundleActivator.class;
        Class<?> innerClass
            = Class.forName(
                    outerClass.getName() + "$Jetty" + className
                        + "ConnectorFactory");
        Constructor<?> constructor
            = innerClass.getDeclaredConstructor(outerClass);

        constructor.setAccessible(true);

        ConnectorFactory factory
            = (ConnectorFactory) constructor.newInstance(this);
        Connector connector
            = factory.initializeConnector(bundleContext, server);

        // host        
        String host = getCfgString(JETTY_HOST_PNAME, null);

        if (host != null)
            setHost(connector, host);

        return connector;
    }

    /**
     * Initializes a new {@link Handler} instance to be set on a specific
     * {@code Server} instance. The default implementation delegates to
     * {@link #initializeHandlerList(BundleContext, Server)}.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} on which the new instance will be set
     * @return the new {code Handler} instance to be set on {@code server}
     * @throws Exception
     */
    protected Handler initializeHandler(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        return initializeHandlerList(bundleContext, server);
    }

    /**
     * Initializes a new {@link HandlerList} instance to be set on a specific
     * {@code Server} instance.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} on which the new instance will be set
     * @return the new {code HandlerList} instance to be set on {@code server}
     * @throws Exception
     */
    protected abstract Handler initializeHandlerList(
            BundleContext bundleContext,
            Server server)
        throws Exception;

    /**
     * Initializes a new {@code Server} instance to be started in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which the new
     * {@code Server} instance is to be started
     * @return a new {@code Server} instance to be started in
     * {@code bundleContext}
     * @throws Exception 
     */
    protected Server initializeServer(BundleContext bundleContext)
        throws Exception
    {
        Server server = new Server();
        Connector connector = initializeConnector(bundleContext, server);

        server.addConnector(connector);

        Handler handler = initializeHandler(bundleContext, server);

        if (handler != null)
            server.setHandler(handler);

        return server;
    }

    /**
     * Prefixes a specific {@code ConfigurationService} and/or {@code System}
     * property name with {@link #propertyPrefix} if the property name in
     * question is incomplete (i.e. starts with a dot).
     *
     * @param property the {@code ConfigurationService} and/or {@code System}
     * property name to prefix
     * @return a complete {@code ConfigurationService} and/or {@code System}
     * property name equal to {@code property} if {@code property} is complete
     * or derived from {@code property} by prefixing if {@code property} is
     * incomplete
     */
    protected String prefixProperty(String property)
    {
        if (propertyPrefix != null
                && property != null
                && property.startsWith("."))
        {
            property = propertyPrefix + property;
        }
        return property;
    }

    /**
     * Sets the host on which a specific {@code Connector} is to listen for
     * incoming network connections.
     *
     * @param connector the {@code Connector} to set {@code host} on
     * @param host the host on which {@code connector} is to listen for incoming
     * network connections
     * @throws Exception
     */
    protected void setHost(Connector connector, String host)
        throws Exception
    {
        // Provide compatibility with Jetty 8 and invoke the method
        // setHost(String) using reflection because it is in different
        // interfaces/classes in Jetty 8 and 9.
        connector
            .getClass()
                .getMethod("setHost", String.class)
                    .invoke(connector, host);
    }

    /**
     * Sets the port on which a specific {@code Connector} is to listen for
     * incoming network connections.
     *
     * @param connector the {@code Connector} to set {@code port} on
     * @param port the port on which {@code connector} is to listen for incoming
     * network connections
     * @throws Exception
     */
    protected void setPort(Connector connector, int port)
        throws Exception
    {
        // Provide compatibility with Jetty 8 and invoke the method setPort(int)
        // using reflection because it is in different interfaces/classes in
        // Jetty 8 and 9.
        connector
            .getClass()
                .getMethod("setPort", int.class)
                    .invoke(connector, port);
    }

    /**
     * Starts this OSGi bundle in a specific {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this OSGi bundle
     * is starting
     * @throws Exception if an error occurs while starting this OSGi bundle in
     * {@code bundleContext}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);

        boolean started = false;

        try
        {
            if (willStart(bundleContext))
            {
                doStart(bundleContext);
                didStart(bundleContext);
                started = true;
            }
        }
        finally
        {
            if (!started)
                cfg = null;
        }
    }

    /**
     * Stops this OSGi bundle in a specific {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this OSGi bundle
     * is stopping
     * @throws Exception if an error occurs while stopping this OSGi bundle in
     * {@code bundleContext}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        try
        {
            if (willStop(bundleContext))
            {
                doStop(bundleContext);
                didStop(bundleContext);
            }
        }
        finally
        {
            cfg = null;
        }
    }

    /**
     * Notifies this {@code AbstractJettyBundleActivator} that a new Jetty
     * {@code Server} instance is to be initialized and started in a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} is started and to initialize and start a new
     * Jetty {@code Server} instance
     * @return {@code true} if this {@code AbstractJettyBundleActivator} is to
     * continue and initialize and start a new Jetty {@code Server} instance;
     * otherwise, {@code false}
     * @throws Exception
     */
    protected boolean willStart(BundleContext bundleContext)
        throws Exception
    {
        return true;
    }

    /**
     * Notifies this {@code AbstractJettyBundleActivator} that the Jetty
     * {@code Server} instance associated with this instance is to be stopped
     * and released for garbage collection in a specific {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code BundleActivator} is stopped
     * @return {@code true} if this {@code AbstractJettyBundleActivator} is to
     * continue and stop the Jetty {@code Server} instance associated with this
     * instance; otherwise, {@code false}
     * @throws Exception 
     */
    protected boolean willStop(BundleContext bundleContext)
        throws Exception
    {
        return true;
    }

    /**
     * Defines the application programming interface (API) of factories of
     * {@link Connecctor}s.
     */
    private interface ConnectorFactory
    {
        /**
         * Initializes a new {@code Connector} instance to be added to a
         * specific {@code Server} which is to be started in a specific
         * {@code BundleContext}.
         *
         * @param bundleContext the {@code BundleContext} in which
         * {@code server} is to be started
         * @param server the {@code Server} to which the new {@code Connector}
         * instance is to be added
         * @return a new {@code Connector} instance which is to be added to
         * {@code server}
         * @throws Exception 
         */
        Connector initializeConnector(
                BundleContext bundleContext,
                Server server)
            throws Exception;
    }

    /**
     * Implements {@link ConnectorFactory} for Jetty 8.
     */
    private class Jetty8ConnectorFactory
        implements ConnectorFactory
    {
        /**
         * {@inheritDoc}
         *
         * The implementation utilizes Jetty 8 application programming interface
         * (API) and is not (necessarily) compatible with Jetty 9.
         */
        @Override
        public Connector initializeConnector(
                BundleContext bundleContext,
                Server server)
            throws Exception
        {
            // The source code is compiled in the environment of Jetty 9. Unless
            // the Jetty 8 application programming interface (API) is available
            // in Jetty 9 as well, it is to be invoked through reflection.
            String className
                = "org.eclipse.jetty.server.nio.SelectChannelConnector";
            Class<?> clazz = Class.forName(className);
            Connector connector = (Connector) clazz.newInstance();

            // port
            setPort(connector, getCfgInt(JETTY_PORT_PNAME, getDefaultPort()));

            return connector;
        }
    }

    /**
     * Implements {@link ConnectorFactory} for Jetty 9.
     */
    private class Jetty9ConnectorFactory
        implements ConnectorFactory
    {
        /**
         * {@inheritDoc}
         *
         * The implementation utilizes Jetty 9 application programming interface
         * (API) and is not (necessarily) compatible with Jetty 8.
         */
        @Override
        public Connector initializeConnector(
                BundleContext bundleContext,
                Server server)
            throws Exception
        {
            HttpConfiguration httpCfg = new HttpConfiguration();
            int tlsPort = getCfgInt(JETTY_TLS_PORT_PNAME, getDefaultTlsPort());

            httpCfg.setSecurePort(tlsPort);
            httpCfg.setSecureScheme("https");

            String sslContextFactoryKeyStorePath
                = getCfgString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH, null);
            Connector connector;
            int port;

            // If HTTPS is not enabled, serve over HTTP.
            if (sslContextFactoryKeyStorePath == null)
            {
                // HTTP
                connector
                    = new MuxServerConnector(
                            server,
                            new HttpConnectionFactory(httpCfg));
                port = getCfgInt(JETTY_PORT_PNAME, getDefaultPort());
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
                port = tlsPort;
            }

            // port
            setPort(connector, port);

            return connector;
        }
    }
}
