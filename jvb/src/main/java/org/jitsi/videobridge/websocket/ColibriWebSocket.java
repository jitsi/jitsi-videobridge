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
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.websocket.config.*;

import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

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
     * The {@link EventHandler}, if any, associated with this web socket.
     */
    private final EventHandler eventHandler;

    /**
     * The clock used to compute lastSendTime.
     */
    private final Clock clock = Clock.systemUTC();

    /** The last time something was sent on this web socket */
    private Instant lastSendTime = Instant.MIN;

    /** The recurring task to send pings on the connection, if needed. */
    private ScheduledFuture<?> pinger = null;

    /**
     * Initializes a new {@link ColibriWebSocket} instance.
     */
    public ColibriWebSocket(
        String id,
        EventHandler eventHandler
    )
    {
        this.logger = new LoggerImpl(getClass().getName(), new LogContext(Map.of("id", id)));
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

        if (WebsocketServiceConfig.config.getSendKeepalivePings())
        {
            pinger = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(this::maybeSendPing,
                WebsocketServiceConfig.config.getKeepalivePingInterval().toMillis(),
                WebsocketServiceConfig.config.getKeepalivePingInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        }

        eventHandler.webSocketConnected(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        eventHandler.webSocketClosed(this, statusCode, reason);
        if (pinger != null)
        {
            pinger.cancel(true);
        }
    }

    public void sendString(String message)
    {
        RemoteEndpoint remote = getRemote();
        if (remote != null)
        {
            // We'll use the async version of sendString since this may be called
            // from multiple threads.  It's just fire-and-forget though, so we
            // don't wait on the result

            remote.sendString(message, WriteCallback.NOOP);
            synchronized (this)
            {
                lastSendTime = clock.instant();
            }
        }
    }

    private void maybeSendPing()
    {
        try
        {
            Instant now = clock.instant();
            synchronized (this)
            {
                if (Duration.between(lastSendTime, now).
                    compareTo(WebsocketServiceConfig.config.getKeepalivePingInterval()) < 0)
                {
                    RemoteEndpoint remote = getRemote();
                    if (remote != null)
                    {
                        remote.sendPing(ByteBuffer.allocate(0), WriteCallback.NOOP);
                        lastSendTime = clock.instant();
                    }
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Error sending websocket ping", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onWebSocketError(Throwable cause)
    {
        eventHandler.webSocketError(this, cause);
        if (pinger != null)
        {
            pinger.cancel(true);
        }
    }

    public interface EventHandler
    {
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
         * Notifies that a message has been received from a
         * specific {@link ColibriWebSocket} instance associated with it.
         * @param ws the {@link ColibriWebSocket} from which a message was received.
         */
        void webSocketTextReceived(ColibriWebSocket ws, String message);

        /**
         * An error occurred for a specific websocket.
         * @param ws the websocket.
         * @param cause the cause of the error.
         */
        void webSocketError(ColibriWebSocket ws, Throwable cause);
    }
}
