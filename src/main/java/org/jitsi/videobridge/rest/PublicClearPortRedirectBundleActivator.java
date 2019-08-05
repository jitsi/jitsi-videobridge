/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.jitsi.rest.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle which implements a
 * redirection from clear port 80 to the configured secure port.
 *
 * @author Damian Minkov
 */
public class PublicClearPortRedirectBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The logger instance used by this
     * {@link PublicClearPortRedirectBundleActivator}.
     */
    private static final Logger logger
        = Logger.getLogger(PublicClearPortRedirectBundleActivator.class);

    /**
     * The prefix of the property names for the Jetty instance managed by
     * this {@link AbstractJettyBundleActivator}.
     */
    public static final String JETTY_PROPERTY_PREFIX
        = "org.jitsi.videobridge.clearport.redirect";

    /**
     * Initializes a new {@link PublicRESTBundleActivator}.
     */
    public PublicClearPortRedirectBundleActivator()
    {
        super(JETTY_PROPERTY_PREFIX);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean willStart(BundleContext bundleContext)
        throws Exception
    {
        // redirection from clear port to the secure port, depends on the
        // configured jetty to have the secure port setup, if missing
        // we do not want to start this jetty instance
        if(cfg.getProperty(
            PublicRESTBundleActivator.JETTY_PROPERTY_PREFIX
                    + JETTY_TLS_PORT_PNAME) == null)
        {
            return false;
        }

        // If there is no setting for the clear port, set it.
        // We do this check to have the default value
        // for {@link AbstractJettyBundleActivator} and to be able to set in the
        // config a value of -1 which will disable this redirect jetty instance
        if (cfg.getProperty(JETTY_PROPERTY_PREFIX + JETTY_PORT_PNAME) == null)
        {
            cfg.setProperty(JETTY_PROPERTY_PREFIX + JETTY_PORT_PNAME, 80);
        }

        return super.willStart(bundleContext);
    }


    /**
     * Initializes the redirect handler.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param server the {@code Server} on which the new instance will be set
     * @return the new {code HandlerList} instance to be set on {@code server}
     * @throws Exception
     */
    @Override
    protected Handler initializeHandlerList(
        BundleContext bundleContext,
        Server server)
            throws Exception
    {
        List<Handler> handlers = new ArrayList<>();

        handlers.add(
            new RedirectHandler(
                cfg.getInt(
                    PublicRESTBundleActivator.JETTY_PROPERTY_PREFIX
                        + JETTY_TLS_PORT_PNAME,
                    443)));

        return initializeHandlerList(handlers);
    }

    /**
     * {@inheritDoc}
     *
     * Just skips few of the printed errors in case of not having permission
     * to start it.
     */
    @Override
    public void start(BundleContext bundleContext) throws Exception {
        try
        {
            super.start(bundleContext);
        }
        catch (Exception t)
        {
            logger.warn(
                "Could not start redirect from clear port(80) to secure port:"
                + t.getMessage());
        }
    }

    /**
     * Redirects requests to the https location using the specific port.
     */
    private class RedirectHandler extends AbstractHandler
    {
        /**
         * The port of the target location.
         */
        private final int targetPort;

        /**
         * Initializes a new {@link RedirectHandler} for a specific port.
         */
        RedirectHandler(int targetPort)
        {
            this.targetPort = targetPort;
        }

        /**
         * Handles all requests by redirecting them
         * (with a 301) to the https location with the specified port.
         */
        @Override
        public void handle(String target, Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
                throws IOException, ServletException
        {
            String host = request.getServerName();

            String location
                = "https://" + host + ":" + targetPort + target;
            response.setHeader("Location", location);

            response.setStatus(301);
            response.setContentLength(0);
            baseRequest.setHandled(true);
        }
    }
}
