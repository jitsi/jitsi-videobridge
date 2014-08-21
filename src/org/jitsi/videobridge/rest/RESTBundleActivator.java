/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rest;

import java.io.*;
import java.lang.reflect.*;

import net.java.sip.communicator.util.*;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.*;
import org.jitsi.service.configuration.*;
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
 */
public class RESTBundleActivator
    implements BundleActivator
{
    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the port on which the REST API of Videobridge is
     * to be served over HTTP. The default value is <tt>8080</tt>.
     */
    private static final String JETTY_PORT_PNAME
        = Videobridge.REST_API_PNAME + ".jetty.port";

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
     * The Jetty <tt>Server</tt> which provides the HTTP(S) interface to the
     * REST API of Videobridge.
     */
    private Server server;

    /**
     * Gets an absolute path in the form of <tt>File</tt> from an absolute or
     * relative <tt>path</tt> specified in the form of a <tt>String</tt>. If
     * <tt>path</tt> is relative, it is resolved against
     * <tt>ConfigurationService.PNAME_SC_HOME_DIR_LOCATION</tt> and
     * <tt>ConfigurationService.PNAME_SC_HOME_DIR_NAME</tt>, <tt>user.home</tt>,
     * or the current working directory.
     *
     * @param path the absolute or relative path in the form of <tt>String</tt>
     * for/from which an absolute path in the form of <tt>File</tt> is to be
     * returned
     * @param cfg the <tt>ConfigurationService</tt> to be employed by the method
     * (invocation) if necessary
     * @return an absolute path in the form of <tt>File</tt> for/from the
     * specified <tt>path</tt>
     */
    private File getAbsoluteFile(String path, ConfigurationService cfg)
    {
        File file = new File(path);

        if (!file.isAbsolute())
        {
            String scHomeDirLocation, scHomeDirName;

            if (cfg == null)
            {
                scHomeDirLocation
                    = System.getProperty(
                            ConfigurationService.PNAME_SC_HOME_DIR_LOCATION);
                scHomeDirName
                    = System.getProperty(
                            ConfigurationService.PNAME_SC_HOME_DIR_NAME);
            }
            else
            {
                scHomeDirLocation = cfg.getScHomeDirLocation();
                scHomeDirName = cfg.getScHomeDirName();
            }
            if (scHomeDirLocation == null)
            {
                scHomeDirLocation = System.getProperty("user.home");
                if (scHomeDirLocation == null)
                    scHomeDirLocation = ".";
            }
            if (scHomeDirName == null)
                scHomeDirName = ".";
            file
                = new File(new File(scHomeDirLocation, scHomeDirName), path)
                    .getAbsoluteFile();
        }
        return file;
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
        // The REST API of Videobridge does not start by default.
        ConfigurationService cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);
        boolean start;
        int port = 8080, tlsPort = 8443;
        String sslContextFactoryKeyStorePassword, sslContextFactoryKeyStorePath;
        boolean sslContextFactoryNeedClientAuth = false;

        if (cfg == null)
        {
            port = Integer.getInteger(JETTY_PORT_PNAME, port);
            sslContextFactoryKeyStorePassword
                = System.getProperty(JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD);
            sslContextFactoryKeyStorePath
                = System.getProperty(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH);
            sslContextFactoryNeedClientAuth
                = Boolean.getBoolean(JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH);
            start = Boolean.getBoolean(Videobridge.REST_API_PNAME);
            tlsPort = Integer.getInteger(JETTY_TLS_PORT_PNAME, tlsPort);
        }
        else
        {
            port = cfg.getInt(JETTY_PORT_PNAME, port);
            sslContextFactoryKeyStorePassword
                = cfg.getString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD);
            sslContextFactoryKeyStorePath
                = cfg.getString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH);
            sslContextFactoryNeedClientAuth
                = cfg.getBoolean(
                        JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH,
                        sslContextFactoryNeedClientAuth);
            start = cfg.getBoolean(Videobridge.REST_API_PNAME, false);
            tlsPort = cfg.getInt(JETTY_TLS_PORT_PNAME, tlsPort);
        }
        if (!start)
            return;

        try
        {
            Server server = new Server();
            HttpConfiguration httpCfg = new HttpConfiguration();

            httpCfg.setSecurePort(tlsPort);
            httpCfg.setSecureScheme("https");

            /*
             * If HTTPS is not enabled, serve the REST API of Jitsi Videobridge
             * over HTTP.
             */
            if (sslContextFactoryKeyStorePath == null)
            {
                // HTTP
                ServerConnector httpConnector
                    = new ServerConnector(
                            server,
                            new HttpConnectionFactory(httpCfg));

                httpConnector.setPort(port);
                server.addConnector(httpConnector);
            }
            else
            {
                // HTTPS
                File sslContextFactoryKeyStoreFile
                    = getAbsoluteFile(sslContextFactoryKeyStorePath, cfg);
                SslContextFactory sslContextFactory = new SslContextFactory();

                sslContextFactory.setExcludeCipherSuites(
                        "SSL_RSA_WITH_DES_CBC_SHA",
                        "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                        "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                        "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                        "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                        "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                        "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
                sslContextFactory.setIncludeCipherSuites(".*RC4.*");
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

                ServerConnector sslConnector
                    = new ServerConnector(
                            server,
                            new SslConnectionFactory(
                                    sslContextFactory,
                                    "http/1.1"),
                            new HttpConnectionFactory(httpsCfg));
                sslConnector.setPort(tlsPort);
                server.addConnector(sslConnector);
            }

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
            server.stop();
            server = null;
        }
    }
}
