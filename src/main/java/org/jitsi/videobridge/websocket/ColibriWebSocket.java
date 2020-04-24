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

import org.eclipse.jetty.websocket.api.*;
import org.jitsi.utils.collections.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * @author Boris Grozev
 */
public class ColibriWebSocket extends WebSocketAdapter
{
    /**
     * The logger instance used by this {@link ColibriWebSocket}.
     */
    private final Logger logger;

    /**
     * The {@link ColibriWebSocketServlet} which created this web socket.
     */
    private ColibriWebSocketServlet servlet;

    /**
     * The {@link Endpoint}, if any, associated with this web socket.
     */
    private final EventHandler eventHandler;

    /**
     * Initializes a new {@link ColibriWebSocket} instance.
     * @param servlet the {@link ColibriWebSocketServlet} which created the
     * instance.
     */
    ColibriWebSocket(
        String id,
        ColibriWebSocketServlet servlet,
        EventHandler eventHandler
    )
    {
        this.logger = new LoggerImpl(getClass().getName(), new LogContext(JMap.of("id", id)));
        this.servlet = servlet;
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
    }

    /**
     * Handles an a text message received on this web socket.
     * @param message the message.
     */
    @Override
    public void onWebSocketText(String message)
    {
        logger.debug(() -> "Received text: " + message);
        eventHandler.webSocketTextReceived(this, message);
    }

    /**
     * {@inheritDoc}
     * </p>
     * Handles the event of this web socket being connected. Finds the
     * destination COLIBRI {@link Endpoint} and authenticates the request
     * based on the password.
     */
    @Override
    public void onWebSocketConnect(Session sess)
    {
        super.onWebSocketConnect(sess);

        eventHandler.webSocketConnected(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        eventHandler.webSocketClosed(this, statusCode, reason);
    }

    public interface EventHandler {
        /**
         * Notifies that a specific {@link ColibriWebSocket}
         * instance associated with it has been closed.
         * @param ws the {@link ColibriWebSocket} which has been closed.
         */
        void webSocketClosed(ColibriWebSocket ws, int statusCode, String reason);
        /**
         * Notifies that a specific {@link ColibriWebSocket}
         * instance associated with it has connected.
         * @param ws the {@link ColibriWebSocket} which has connected.
         */
        void webSocketConnected(ColibriWebSocket ws);
        /**
         * Notifies this {@link Endpoint} that a message has been received from a
         * specific {@link ColibriWebSocket} instance associated with it.
         * @param ws the {@link ColibriWebSocket} from which a message was received.
         */
        void webSocketTextReceived(ColibriWebSocket ws, String message);
    }
}
