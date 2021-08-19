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

import org.eclipse.jetty.websocket.api.extensions.*;
import org.eclipse.jetty.websocket.servlet.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.websocket.config.*;

import java.io.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.stream.*;

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

    private WebsocketServiceConfig config = new WebsocketServiceConfig();

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
                            + ids[2] + "(conference " + conference.getID() + ")");
            response.sendError(403, authFailed);
            return null;
        }

        Endpoint endpoint = (Endpoint) abstractEndpoint;
        String pwd = getPwd(request.getRequestURI().getQuery());
        if (pwd == null || !endpoint.acceptWebSocket(pwd))
        {
            response.sendError(403, authFailed);
            return null;
        }

        if (!config.shouldEnableCompression())
        {
            List<ExtensionConfig> extensions = response.getExtensions().stream().
                    filter((ext) -> !ext.getName().equals("permessage-deflate")).
                        collect(Collectors.toList());
            response.setExtensions(extensions);
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
        try {
            Map<String, List<String>> parametersMap = splitQuery(query);
            if (parametersMap.get("pwd") != null){
                return parametersMap.get("pwd").get(0);
            }
        }catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Converts query string to Map<String, List<String>> format. Supports multiple parameters with the same key.
     * @param query query string
     * @return parsed list
     */
    public static Map<String, List<String>> splitQuery(String query) throws UnsupportedEncodingException {
        final Map<String, List<String>> query_pairs = new LinkedHashMap<>();
        final String[] pairs = query.split("&");
        for (String pair : pairs) {
            final int idx = pair.indexOf("=");
            final String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
            if (!query_pairs.containsKey(key)) {
                query_pairs.put(key, new LinkedList<>());
            }
            final String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : null;
            query_pairs.get(key).add(value);
        }
        return query_pairs;
    }
}
