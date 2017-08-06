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
 * Implements a bridge-to-bridge (Octo) relay. This class is responsible for
 * holding the {@link DatagramSocket} which is to be used for bridge-to-bridge
 * communication, and the "relay ID" which other bridges use to discover the
 * socket address on which this bridge is accessible.
 *
 * @author Boris Grozev
 */
public class OctoRelay
{
    /**
     * The {@link Logger} used by the {@link OctoRelay} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(OctoRelay.class);

    /**
     * The socket used to send and receive Octo packets.
     */
    private MultiplexingDatagramSocket socket;

    /**
     * The Octo relay ID which will be advertised by jitsi-videobridge.
     * Other bridges can use this ID in order to discover the socket address
     * that this bridge is accessible on. With the current implementation the
     * ID just encodes a pre-configured IP address and port,
     * e.g. "10.0.0.1:20000"
     */
    private String relayId;

    /**
     * Initializes a new {@link OctoRelay} instance, binding on a specific
     * address and port.
     * @param address the address on which to bind.
     * @param port the port on which to bind.
     */
    public OctoRelay(String address, int port)
        throws UnknownHostException, SocketException
    {
        DatagramSocket s
            = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getByName(address), port));
        socket = new MultiplexingDatagramSocket(s, true /* persistent */);
        relayId = address + ":" + port;
    }

    /**
     * Stops this {@link OctoRelay}, closing its {@link DatagramSocket}.
     */
    void stop()
    {
        try
        {
            socket.close();
        }
        catch (Exception e)
        {
            logger.warn("Failed to stop OctoRelay: ", e);
        }
    }

    /**
     * @return the ID of this {@link OctoRelay}.
     */
    public String getId()
    {
        return relayId;
    }

    /**
     * @return  the {@link MultiplexingDatagramSocket} used by this
     * {@link OctoRelay}.
     */
    public MultiplexingDatagramSocket getSocket()
    {
        return socket;
    }
}
