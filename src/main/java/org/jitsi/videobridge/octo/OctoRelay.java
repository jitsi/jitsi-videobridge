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
import org.jitsi.utils.logging.*;

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
     * The receive buffer size to set of the socket.
     */
    private static final int SO_RCVBUF = 10 * 1024 * 1024;

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
    
    private String publicAddress;

    private int port;

    /**
     * Initializes a new {@link OctoRelay} instance, binding on a specific
     * address and port.
     * @param address the address on which to bind.
     * @param port the port on which to bind.
     */
    public OctoRelay(String address, int port)
        throws UnknownHostException, SocketException
    {
        InetSocketAddress addr
                = new InetSocketAddress(InetAddress.getByName(address), port);
        DatagramSocket s = new DatagramSocket(addr);
        s.setReceiveBufferSize(SO_RCVBUF);
        logger.info("Initialized OctoRelay with address " + addr +
                ". Receive buffer size " + s.getReceiveBufferSize() +
                " (asked for " + SO_RCVBUF + ").");

        socket = new MultiplexingDatagramSocket(s, true /* persistent */)
        {
            @Override
            public void setReceiveBufferSize(int size)
            {
                // We want to keep the buffer size to the one we set above.
            }
        };
        this.port = port;
        String id = address + ":" + port;
        setRelayId(id);
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
    * Set the relayId
    **/
    public void setRelayId(String id)
    {
        relayId = id;
    }
    
    /**
    * Set the public address to be used as part of relayId
    **/
    public void setPublicAddress(String address)
    {
         publicAddress = address;
         String id = publicAddress + ":" + port;
         setRelayId(id);
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
