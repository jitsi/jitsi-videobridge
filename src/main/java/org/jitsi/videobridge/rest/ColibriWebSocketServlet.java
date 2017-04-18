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

import net.java.sip.communicator.util.*;
import org.eclipse.jetty.websocket.servlet.*;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

import java.io.*;

/**
 * @author Boris Grozev
 */
class ColibriWebSocketServlet
    extends WebSocketServlet
{
    /**
     * The logger instance used by this {@link ColibriWebSocketServlet}.
     */
    private static final Logger logger
        = Logger.getLogger(ColibriWebSocketServlet.class);

    /**
     * The {@link BundleContext} in which this instance is running.
     */
    private BundleContext bundleContext;

    /**
     * The {@link ColibriWebSocketService} instance which created this servlet.
     */
    private ColibriWebSocketService service;

    /**
     * Initializes a new {@link ColibriWebSocketServlet} instance.
     * @param bundleContext the {@code BundleContext} in which the new instance
     * is to be initialized
     */
    ColibriWebSocketServlet(BundleContext bundleContext,
                            ColibriWebSocketService service)
    {
        this.bundleContext = bundleContext;
        this.service = service;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(WebSocketServletFactory webSocketServletFactory)
    {
        // set a timeout of 1min
        webSocketServletFactory.getPolicy().setIdleTimeout(60000);

        webSocketServletFactory.setCreator(new WebSocketCreator()
        {
            @Override
            public Object createWebSocket(ServletUpgradeRequest request,
                                          ServletUpgradeResponse response)
            {
                try
                {
                    return
                        ColibriWebSocketServlet
                            .this.createWebSocket(request, response);
                }
                catch (IOException ioe)
                {
                    response.setSuccess(false);
                    return null;
                }
            }
        });
    }

    /**
     * Handles a request for a web-socket. Validates the request URI and either
     * accepts the request and creates a {@link ColibriWebSocket} instance, or
     * rejects rejects the request and sends an error.
     */
    private ColibriWebSocket createWebSocket(
            ServletUpgradeRequest request,
            ServletUpgradeResponse response)
        throws IOException
    {
        // A valid request URI looks like this:
        // /colibri-ws/server-id/conf-id/endpoint-id?pwd=password
        // The "path" does not include "?pwd=password", which is in the "query"
        String path = request.getRequestURI().getPath();
        if (path == null
            || !path.startsWith(ColibriWebSocketService.COLIBRI_WS_PATH))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Received request for an invalid path: " + path);
            }
            response.sendError(404, "invalid path");
            return null;
        }

        path
            = path.substring(
            ColibriWebSocketService.COLIBRI_WS_PATH.length(),
            path.length());
        String[] ids = path.split("/");
        if (ids.length < 3)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Received request for an invalid path: " + path);
            }
            response.sendError(404, "invalid path");
            return null;
        }

        String serverId = getService().getServerId();
        if (!serverId.equals(ids[0]))
        {
            logger.warn("Received request with a mismatching server ID "
                            + "(expected '" + serverId
                            + "', received '" + ids[0] + "').");
            response.sendError(404, "server ID mismatch");
            return null;
        }

        Videobridge videobridge = getVideobridge();
        if (videobridge == null)
        {
            logger.warn("No associated Videobridge");
            response.sendError(500, "no videobridge");
            return null;
        }

        // Note: intentionally fail with the exact same message (as to not leak
        // info)
        String authFailed = "authentication failed";
        Conference conference = videobridge.getConference(ids[1], null);
        if (conference == null)
        {
            logger.warn("Received request for an nonexistent conference: "
                            + ids[1]);
            response.sendError(403, authFailed);
            return null;
        }

        Endpoint endpoint = conference.getEndpoint(ids[2]);
        if (endpoint == null)
        {
            logger.warn("Received request for an nonexistent endpoint: "
                            + ids[1] + "(conference " + conference.getID());
            response.sendError(403, authFailed);
            return null;
        }

        String pwd = getPwd(request.getRequestURI().getQuery());
        if (!endpoint.acceptWebSocket(pwd))
        {
            response.sendError(403, authFailed);
            return null;
        }

        return new ColibriWebSocket(this, endpoint);
    }

    /**
     * Extracts the password from the "query" part of a URI (e.g. "pwd=12345").
     * @param query the "query" part of a URI (e.g. "pwd=12345").
     * @return the password (e.g. "12345"), or null if parsing failed.
     */
    private String getPwd(String query)
    {
        // TODO: this only deals with the simplest case.
        if (query == null)
        {
            return null;
        }
        if (!query.startsWith("pwd="))
        {
            return null;
        }
        return query.substring("pwd=".length());
    }


    /**
     * @return the {@link ColibriWebSocketService} of this servlet.
     */
    ColibriWebSocketService getService()
    {
        return service;
    }

    /**
     * @return the {@link Videobridge} instance associated with this instance.
     */
    Videobridge getVideobridge()
    {
       return ServiceUtils.getService(bundleContext, Videobridge.class);
    }
}
