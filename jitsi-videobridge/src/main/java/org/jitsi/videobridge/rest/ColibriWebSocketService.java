/*
 * Copyright @ 2017 Atlassian Pty Ltd
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

import org.eclipse.jetty.servlet.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.rest.config.*;
import org.osgi.framework.*;

import static org.jitsi.videobridge.rest.config.WebsocketServiceConfig.Config;

/**
 * @author Boris Grozev
 */
public class ColibriWebSocketService
{
    /**
     * The root path of the HTTP endpoint for COLIBRI WebSockets.
     */
    public static final String COLIBRI_WS_PATH = "/colibri-ws/";

    private static final Logger logger
        = new LoggerImpl(ColibriWebSocketService.class.getName());

    /**
     * The common prefix which URLs advertised for all conferences and endpoints
     * will share, e.g.
     * {@code "wss://jitsi-videobridge.example.com/colibri-ws/server-id/"}.
     */
    private final String baseUrl;

    /**
     * A string which identifies the jitsi-videobridge instance.
     */
    private final String serverId;

    /**
     * Initializes a {@link ColibriWebSocketService} in a specific
     * {@link BundleContext}.
     *
     * @param tls whether to use "ws" or "wss" in advertised URLs in the absence
     * of configuration which overrides it (see
     * {@link WebsocketServiceConfig.Config#useTls()}).
     */
    public ColibriWebSocketService(boolean tls)
    {
        String baseUrl = null;
        String serverId = null;

        // The domain name is currently a required property.
        if (Config.enabled())
        {
            String domain = Config.domain();
            // We default to matching the protocol used by the local jetty
            // instance, but we allow for the configuration via properties
            // to override it since certain use-cases require it.
            Boolean tlsProp = Config.useTls();
            tls = tlsProp != null ? tlsProp : tls;

            // The server ID is not critical, just use a default string
            // unless configured.
            serverId = Config.serverId();

            baseUrl = tls ? "wss://" : "ws://";
            baseUrl += domain + COLIBRI_WS_PATH + serverId + "/";
        }

        this.baseUrl = baseUrl;
        this.serverId = serverId;
    }

    /**
     * @return the server-id configured for this instance.
     */
    String getServerId()
    {
        return serverId;
    }

    /**
     * @return the URL (as a string) to advertise for a specific endpoint of
     * a specific conference and with a specific password. Returns null if
     * the service is disabled.
     * @param conferenceId the ID of the conference.
     * @param endpointId the ID of the endpoint.
     * @param pwd the password.
     */
    public String getColibriWebSocketUrl(
            String conferenceId,
            String endpointId,
            String pwd)
    {
        if (baseUrl == null)
        {
            return null;
        }

        // "wss://example.com/colibri-ws/server-id/conf-id/endpoint-id?pwd=123
        return baseUrl + conferenceId + "/" + endpointId + "?pwd=" + pwd;
    }

    /**
     * Initializes a {@link ColibriWebSocketServlet} and adds it to the
     * specified {@link ServletContextHandler}.
     *
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     * @param servletContextHandler the {@code ServletContextHandler} to add the
     * new instance to
     * @return the {@link ServletHolder} which holds the newly initialized
     * servlet, or {@code null} if no servlet was initialized.
     */
    ServletHolder initializeColibriWebSocketServlet(
        BundleContext bundleContext,
        ServletContextHandler servletContextHandler)
    {
        ServletHolder holder = null;

        if (baseUrl != null && Config.enabled())
        {
            logger.info("Starting colibri websocket service with baseUrl: "
                + baseUrl);
            holder = new ServletHolder();

            holder.setServlet(new ColibriWebSocketServlet(bundleContext, this));

            // The rules for mappings of the Servlet specification do not allow
            // path matching in the middle of the path.
            servletContextHandler.addServlet(
                holder,
                COLIBRI_WS_PATH + "*");
        }

        return holder;
    }
}
