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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.*;

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
    private final Logger logger;
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
    public SctpManager(SctpDataSender dataSender, Logger parentLogger)
    {
        this.dataSender = new BufferCopyingSctpDataSender(dataSender);
        this.logger = parentLogger.createChildLogger(SctpManager.class.getName());
    }

    /**
     * Process an incoming SCTP packet from the network
     * @param sctpPacket an incoming SCTP packet which may be either an SCTP protocol control packet or an SCTP
     *                   application packet
     */
    public void handleIncomingSctp(PacketInfo sctpPacket) {
        logger.debug(() -> "SCTP Socket " + socket.hashCode() + " receiving incoming SCTP data");
        //NOTE(brian): from what I can tell in usrsctp, we can assume that it will make a copy
        // of the buffer we pass it here (this ends up hitting usrsctp_conninput, and the sample
        // program for usrsctp re-uses the buffer that's passed here, and the code does appear
        // to make a copy).
        socket.onConnIn(sctpPacket.getPacket().getBuffer(), sctpPacket.getPacket().getOffset(), sctpPacket.getPacket().getLength());
        ByteBufferPool.returnBuffer(sctpPacket.getPacket().getBuffer());
    }

    /**
     * Create an {@link SctpServerSocket} to be used to wait for incoming SCTP connections
     * @return an {@link SctpServerSocket}
     */
    public SctpServerSocket createServerSocket()
    {
        socket = Sctp4j.createServerSocket(DEFAULT_SCTP_PORT);
        socket.outgoingDataSender = this.dataSender;
        logger.debug(() -> "Created SCTP server socket " + socket.hashCode());
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

    /**
     * We set an instance of this as the {@link SctpSocket#outgoingDataSender}
     * in order to change from a buffer that was allocated by jitsi-sctp
     * to one from our pool, this way it can be returned later in the pipeline.
     */
    private static class BufferCopyingSctpDataSender implements SctpDataSender {
        private final SctpDataSender innerSctpDataSender;
        BufferCopyingSctpDataSender(@NotNull SctpDataSender sctpDataSender)
        {
            this.innerSctpDataSender = sctpDataSender;
        }

        @Override
        public int send(byte[] data, int offset, int length)
        {
            byte[] newBuf = ByteBufferPool.getBuffer(length);
            System.arraycopy(data, offset, newBuf, 0, length);
            return innerSctpDataSender.send(newBuf, 0, length);
        }
    }
}
