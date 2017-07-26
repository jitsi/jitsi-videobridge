/*
 * Copyright @ 2015-2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge.octo;

import org.ice4j.socket.*;
import org.jitsi.util.*;

import java.net.*;

/**
 * Implements a bridge-to-bridge (Octo) relay.
 *
 * @author Boris Grozev
 */
public class OctoRelayImpl
{
    /**
     * The {@link Logger} used by the {@link OctoRelayImpl} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(OctoRelayImpl.class);

    /**
     * The socket used to send and receive Octo packets.
     */
    private MultiplexingDatagramSocket socket;

    /**
     * The Octo relay ID which will be advertised by jitsi-videobridge.
     */
    private String relayId;

    /**
     * Initializes a new {@link OctoRelayImpl} instance, binding on a specific
     * address and port.
     * @param address the address on which to bind.
     * @param port the port on which to bind.
     */
    public OctoRelayImpl(String address, int port)
        throws UnknownHostException, SocketException
    {
        DatagramSocket s
            = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getByName(address), port));
        socket = new MultiplexingDatagramSocket(s, true /* persistent */);
        relayId = address + ":" + port;
    }

    /**
     * Stops this {@link OctoRelayImpl}, closing its {@link DatagramSocket}.
     */
    void stop()
    {
        try
        {
            socket.close();
        }
        catch (Exception e)
        {
            logger.warn("Failed to stop OctoRelayImpl: ", e);
        }
    }

    /**
     * @return the ID of this {@link OctoRelayImpl}.
     */
    public String getId()
    {
        return relayId;
    }

    /**
     * @return  the {@link MultiplexingDatagramSocket} used by this
     * {@link OctoRelayImpl}.
     */
    public MultiplexingDatagramSocket getSocket()
    {
        return socket;
    }
}
