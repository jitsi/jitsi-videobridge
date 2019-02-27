/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.sctp;

import net.java.sip.communicator.util.*;
import org.jitsi.nlj.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.ByteBuffer;

/**
 * Manages the SCTP connection and handles incoming and outgoing SCTP packets.
 *
 * We create one of these per endpoint and currently only support a single SCTP connection per endpoint, because we
 * that's all we use.
 *
 * All incoming SCTP data received should be passed to {@link SctpManager#handleIncomingSctp(PacketInfo)}.  This class
 * will route it through the {@link SctpSocket} instance so that, if it is an SCTP app packet the user of the
 * {@link SctpSocket} will receive it via the data callback.
 */
public class SctpManager {
    private static Logger logger = Logger.getLogger(SctpManager.class);
    /**
     * The {@link SctpDataSender} is necessary from the start, as it's needed to send outgoing SCTP protocol packets
     * (e.g. used in the connection negotiation)
     */
    private final SctpDataSender dataSender;

    /**
     * We hold a reference to the create socket so any received data maybe be routed through it.  Currently we only
     * support a single active socket at a time.
     */
    private SctpSocket socket = null;


    // We hard-code 5000 in the offer, so just mark it as the default here.
    private static int DEFAULT_SCTP_PORT = 5000;
    static
    {
        Sctp4j.init(DEFAULT_SCTP_PORT);
    }

    /**
     * Create a new {@link SctpManager} with the given data sender
     * @param dataSender a {@link SctpDataSender} which will be used when we need to send SCTP packets to the remote
     *                   peer
     */
    public SctpManager(SctpDataSender dataSender)
    {
        this.dataSender = dataSender;
    }

    /**
     * Process an incoming SCTP packet from the network
     * @param sctpPacket an incoming SCTP packet which may be either an SCTP protocol control packet or an SCTP
     *                   application packet
     */
    public void handleIncomingSctp(PacketInfo sctpPacket) {
        if (logger.isDebugEnabled())
        {
            logger.debug("SCTP Socket " + socket.hashCode() + " receiving incoming SCTP data");
        }
        ByteBuffer packetBuffer = sctpPacket.getPacket().getBuffer();
        socket.onConnIn(packetBuffer.array(), packetBuffer.arrayOffset(), packetBuffer.limit());
    }

    /**
     * Create an {@link SctpServerSocket} to be used to wait for incoming SCTP connections
     * @return an {@link SctpServerSocket}
     */
    public SctpServerSocket createServerSocket()
    {
        socket = Sctp4j.createServerSocket(DEFAULT_SCTP_PORT);
        socket.outgoingDataSender = this.dataSender;
        if (logger.isDebugEnabled())
        {
            logger.debug("Created SCTP server socket " + socket.hashCode());
        }
        return (SctpServerSocket)socket;
    }

    /**
     * Create an {@link SctpClientSocket} to be used to open an SCTP connection
     * @return an {@link SctpClientSocket}
     */
    public SctpClientSocket createClientSocket() {
        socket = Sctp4j.createClientSocket(DEFAULT_SCTP_PORT);
        socket.outgoingDataSender = this.dataSender;
        if (logger.isDebugEnabled())
        {
            logger.debug("Created SCTP client socket " + socket.hashCode());
        }
        return (SctpClientSocket)socket;
    }

    /**
     * Close the active {@link SctpSocket}, if there is one
     */
    public void closeConnection() {
        if (socket != null) {
            if (logger.isDebugEnabled())
            {
                logger.debug("Closing SCTP socket " + socket.hashCode());
            }
            socket.close();
            socket = null;
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No SCTP socket to close");
            }
        }
    }
}
