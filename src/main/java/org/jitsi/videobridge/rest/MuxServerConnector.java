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
import java.nio.channels.*;
import java.util.*;

import org.eclipse.jetty.server.*;
import org.ice4j.socket.*;

/**
 * Implements a Jetty {@code ServerConnector} which is capable of sharing its
 * listening endpoint by utilizing {@link MuxServerSocketChannel}.
 *
 * @author Lyubomir Marinov
 */
public class MuxServerConnector
    extends ServerConnector
{
    /**
     * The {@code Field} reflection of the {@code _acceptChannel} field of the
     * class {@code ServerConnector}. 
     */
    private static final Field ACCEPT_CHANNEL_FIELD;

    /**
     * The {@code DatagramPacketFilter} which demultiplexes HTTP(S) from
     * {@link MuxServerSocketChannel} into {@code MuxServerConnector}.
     */
    private static final DatagramPacketFilter HTTP_DEMUX_FILTER;

    /**
     * The {@code Field} reflection of the {@code _localPort} field of the class
     * {@code ServerConnector}. 
     */
    private static final Field LOCAL_PORT_FIELD;

    static
    {
        // Allow the class MuxServerConnector to modify the private state of the
        // class ServerConnector.
        Field acceptChannelField = null;
        Field localPortField = null;

        try
        {
            Class<ServerConnector> clazz = ServerConnector.class;

            acceptChannelField = clazz.getDeclaredField("_acceptChannel");
            localPortField = clazz.getDeclaredField("_localPort");

            acceptChannelField.setAccessible(true);
            localPortField.setAccessible(true);
        }
        catch (NoSuchFieldException nsfe)
        {
        }
        catch (SecurityException se)
        {
        }
        if (acceptChannelField != null && localPortField != null)
        {
            ACCEPT_CHANNEL_FIELD = acceptChannelField;
            LOCAL_PORT_FIELD = localPortField;
        }
        else
        {
            // If the class MuxServerConnector cannot modify the private state
            // of the class ServerConnector, then a server will fail to bind if
            // a sharing of its listening endpoint is necessary.
            ACCEPT_CHANNEL_FIELD = null;
            LOCAL_PORT_FIELD = null;
        }

        HTTP_DEMUX_FILTER = new HttpDemuxFilter();
    }

    /**
     * Initializes a new <tt>MuxServerConnector</tt> instance.
     *
     * @param server the {@link Server} the new instance is to accept
     * connections for
     * @param factories the list of {@link ConnectionFactory} instances which
     * are to create and configure connections
     */
    public MuxServerConnector(Server server, ConnectionFactory... factories)
    {
        super(server, factories);
    }

    /**
     * Starts accepting incoming network connections.
     *
     * @throws IOException if this <tt>NetworkConnector</tt> cannot be opened
     */
    @Override
    public void open()
        throws IOException
    {
        Field acceptChannelField = ACCEPT_CHANNEL_FIELD;
        Field localPortField = LOCAL_PORT_FIELD;

        if (acceptChannelField != null && localPortField != null)
        {
            try
            {
                if (acceptChannelField.get(this) == null)
                {
                    ServerSocketChannel serverChannel = null;

                    if (isInheritChannel())
                    {
                        Channel channel = System.inheritedChannel();

                        if (channel instanceof ServerSocketChannel)
                            serverChannel = (ServerSocketChannel) channel;
                    }

                    if (serverChannel == null)
                    {
                        // Instead of the opening and binding of a
                        // ServerSocketChannel that the class ServerConnector
                        // does, open and bind a MuxServerSocketChannel which
                        // will allow sharing of the listening endpoint of this
                        // MuxServerConnector.

                        // properties
                        Map<String,Object> properties
                            = new HashMap<String,Object>();

                        properties.put(
                                MuxServerSocketChannelFactory
                                    .SOCKET_REUSE_ADDRESS_PROPERTY_NAME,
                                getReuseAddress());

                        // endpoint
                        String host = getHost();
                        int port = getPort();
                        InetSocketAddress endpoint;

                        if (host == null)
                            endpoint = new InetSocketAddress(port);
                        else
                            endpoint = new InetSocketAddress(host, port);

                        serverChannel
                            = MuxServerSocketChannelFactory
                                .openAndBindMuxServerSocketChannel(
                                        properties,
                                        endpoint,
                                        /* backlog */ getAcceptQueueSize(),
                                        HTTP_DEMUX_FILTER);

                        int localPort = serverChannel.socket().getLocalPort();

                        localPortField.set(this, localPort);
                        if (localPort <= 0)
                            throw new IOException("Server channel not bound");
                    }

                    serverChannel.configureBlocking(true);
                    addBean(serverChannel);

                    acceptChannelField.set(this, serverChannel);
                }
            }
            catch (Exception e)
            {
                if (e instanceof IOException)
                    throw (IOException) e;
                else if (e instanceof RuntimeException)
                    throw (RuntimeException) e;
                else
                    throw new RuntimeException(e);
            }
        }
        else
        {
            // If the class MuxServerConnector cannot modify the private state
            // of the class ServerConnector, then a server will fail to bind if
            // a sharing of its listening endpoint is necessary.
            super.open();
        }
    }
}
