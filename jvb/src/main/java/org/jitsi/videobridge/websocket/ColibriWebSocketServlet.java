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
package org.jitsi.videobridge.websocket;

import org.eclipse.jetty.websocket.servlet.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;

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
    private static final Logger logger = new LoggerImpl(ColibriWebSocketServlet.class.getName());

    /**
     * The server ID of this websocket servlet.  Incoming requests must have a matching
     * server ID to be handled.
     */
    private final String serverId;

    @NotNull
    private final Videobridge videobridge;

    /**
     * Initializes a new {@link ColibriWebSocketServlet} instance.
     */
    ColibriWebSocketServlet(
        @NotNull String serverId,
        @NotNull Videobridge videobridge)
    {
        this.serverId = serverId;
        this.videobridge = videobridge;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(WebSocketServletFactory webSocketServletFactory)
    {
        // set a timeout of 1min
        webSocketServletFactory.getPolicy().setIdleTimeout(60000);

        webSocketServletFactory.setCreator((request, response) ->
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
        logger.debug(() -> "Got a create websocket request at path " + path);
        if (path == null
            || !path.startsWith(ColibriWebSocketService.COLIBRI_WS_PATH))
        {
            logger.debug(() -> "Received request for an invalid path: " + path);
            response.sendError(404, "invalid path");
            return null;
        }

        String wsPath
            = path.substring(ColibriWebSocketService.COLIBRI_WS_PATH.length());
        String[] ids = wsPath.split("/");
        if (ids.length < 3)
        {
            logger.debug(() -> "Received request for an invalid path: " + wsPath);
            response.sendError(404, "invalid path");
            return null;
        }

        if (!serverId.equals(ids[0]))
        {
            logger.warn("Received request with a mismatching server ID "
                            + "(expected '" + serverId
                            + "', received '" + ids[0] + "').");
            response.sendError(404, "server ID mismatch");
            return null;
        }

        // Note: intentionally fail with the exact same message (as to not leak
        // info)
        String authFailed = "authentication failed";
        Conference conference = videobridge.getConference(ids[1]);
        if (conference == null)
        {
            logger.warn("Received request for an nonexistent conference: "
                            + ids[1]);
            response.sendError(403, authFailed);
            return null;
        }

        AbstractEndpoint abstractEndpoint = conference.getEndpoint(ids[2]);
        if (!(abstractEndpoint instanceof Endpoint))
        {
            logger.warn("Received request for a nonexistent endpoint: "
                            + ids[2] + "(conference " + conference.getID());
            response.sendError(403, authFailed);
            return null;
        }

        Endpoint endpoint = (Endpoint) abstractEndpoint;
        String pwd = getPwd(request.getRequestURI().getQuery());
        if (!endpoint.acceptWebSocket(pwd))
        {
            response.sendError(403, authFailed);
            return null;
        }

        return new ColibriWebSocket(ids[2], this, endpoint.getMessageTransport());
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
}
