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
import org.jitsi.rtp.*;
import org.jitsi.util.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.jitsi.videobridge.octo.OctoPacket.OCTO_HEADER_LENGTH;

/**
 * Implements a bridge-to-bridge (Octo) relay. This class is responsible for
 * holding the {@link DatagramSocket} which is to be used for bridge-to-bridge
 * communication, and the "relay ID" which other bridges use to discover the
 * socket address on which this bridge is accessible.
 *
 * @author Boris Grozev
 */
public class OctoRelay
    implements Runnable
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
    private DatagramSocket socket;

    /**
     * The Octo relay ID which will be advertised by jitsi-videobridge.
     * Other bridges can use this ID in order to discover the socket address
     * that this bridge is accessible on. With the current implementation the
     * ID just encodes a pre-configured IP address and port,
     * e.g. "10.0.0.1:20000"
     */
    private String relayId;

    /**
     * The address to use advertise in the relay ID.
     */
    private String publicAddress;

    /**
     * The port to use.
     */
    private int port;

    /**
     * Number of bytes received.
     */
    private AtomicLong bytesReceived = new AtomicLong();

    /**
     * Number of bytes sent.
     */
    private AtomicLong bytesSent = new AtomicLong();

    /**
     * Number of packets received.
     */
    private AtomicLong packetsReceived = new AtomicLong();

    /**
     * Number of packets sent.
     */
    private AtomicLong packetsSent = new AtomicLong();

    /**
     * Packets dropped (failure to parse, or wrong conference ID).
     */
    private AtomicLong packetsDropped = new AtomicLong();

    /**
     * Maps a conference ID (as contained in Octo packets) to a packet handler.
     */
    private Map<String, PacketHandler> packetHandlers
            = new ConcurrentHashMap<>();

    /**
     * Initializes a new {@link OctoRelay} instance, binding on a specific
     * address and port.
     * @param address the address on which to bind.
     * @param port the port on which to bind.
     */
    OctoRelay(String address, int port)
        throws UnknownHostException, SocketException
    {
        socket
            = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getByName(address), port));
        this.port = port;
        String id = address + ":" + port;
        setRelayId(id);

        TaskPools.IO_POOL.submit(this);
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
    private void setRelayId(String id)
    {
        relayId = id;
    }
    
    /**
    * Set the public address to be used as part of relayId
    **/
    void setPublicAddress(String address)
    {
         publicAddress = address;
         String id = publicAddress + ":" + port;
         setRelayId(id);
    }

    @Override
    public void run()
    {
        DatagramPacket p = new DatagramPacket(new byte[0], 0);
        while (true)
        {
            byte[] buf = ByteBufferPool.getBuffer(1500);
            p.setData(buf, 0, 1500);
            try
            {
                socket.receive(p);
                handlePacket(p.getData(), p.getOffset(), p.getLength());
            }
            catch (SocketClosedException sce)
            {
                logger.info("Octo socket closed, stopping.");
                break;
            }
            catch (IOException ioe)
            {
                logger.warn("Exception while reading: ", ioe);
            }
        }
    }

    /**
     * Handles an Octo packet that was received from the socket.
     * @param p the packet.
     */
    private void handlePacket(byte[] buf, int off, int len)
    {
        bytesReceived.addAndGet(len);
        packetsReceived.incrementAndGet();

        String conferenceId = OctoPacket.readConferenceId(buf, off, len);
        if (conferenceId == null)
        {
            logger.warn("Invalid Octo packet, can not read conference ID.");
            packetsDropped.incrementAndGet();
            ByteBufferPool.returnBuffer(buf);
            return;
        }

        PacketHandler handler = packetHandlers.get(conferenceId);
        if (handler == null)
        {
            packetsDropped.incrementAndGet();
            ByteBufferPool.returnBuffer(buf);
            logger.warn("Received an Octo packet for an unknown conference: "
                    + conferenceId);
        }
        else
        {
            handler.handlePacket(
                    buf,
                    off + OctoPacket.OCTO_HEADER_LENGTH,
                    len - OctoPacket.OCTO_HEADER_LENGTH);
        }
    }

     /**
      * Converts a "relay ID" to a socket address. The current implementation
      * assumes that the ID has the form of "address:port".
      * @param relayId the relay ID to convert
      * @return the socket address encoded in {@code relayId}.
      */
     static SocketAddress relayIdToSocketAddress(String relayId)
     {
         if (relayId == null || !relayId.contains(":"))
         {
             return null;
         }

         try
         {
             String[] addressAndPort = relayId.split(":");
             return new InetSocketAddress(
                 addressAndPort[0], Integer.valueOf(addressAndPort[1]));
         }
         catch (NumberFormatException nfe)
         {
             return null;
         }
     }

    void sendRtp(
            Packet packet,
            Set<SocketAddress> targets,
            String conferenceId,
            String endpointId)
    {
        byte[] buf = packet.getBuffer();
        int off = packet.getOffset();
        int len = packet.getLength();

        int lenNeeded = len + OCTO_HEADER_LENGTH;
        byte[] newBuf;
        int newOff;

        if (off >= OCTO_HEADER_LENGTH)
        {
            newOff = off - OCTO_HEADER_LENGTH;
            newBuf = buf;
        }
        else if (buf.length >= lenNeeded)
        {
            System.arraycopy(buf, off, buf, OCTO_HEADER_LENGTH, len);
            newOff = 0;
            newBuf = buf;
        }
        else
        {
            newBuf = ByteBufferPool.getBuffer(lenNeeded);
            newOff = 0;
            System.arraycopy(buf, off, newBuf, OCTO_HEADER_LENGTH, len);
        }

        OctoPacket.writeHeaders(
                newBuf, newOff,
                true /* source is a relay */,
                MediaType.VIDEO, // we don't care about the value anymore
                0 /* simulcast layers info */,
                conferenceId,
                endpointId);
        DatagramPacket datagramPacket
                = new DatagramPacket(newBuf, newOff, lenNeeded);

        for (SocketAddress target : targets)
        {
            datagramPacket.setSocketAddress(target);
            try
            {
                bytesSent.addAndGet(datagramPacket.getLength());
                packetsSent.incrementAndGet();
                socket.send(datagramPacket);
            }
            catch (IOException ioe)
            {
                logger.warn("Failed to send packet ", ioe);
            }
        }

        ByteBufferPool.returnBuffer(newBuf);
        if (newBuf != buf)
        {
            ByteBufferPool.returnBuffer(buf);
        }

    }

    /**
     * Registers a {@link PacketHandler} for a particular {@code conferenceId}.
     * @param conferenceId the conference ID.
     * @param handler the handler
     */
    void addHandler(String conferenceId, PacketHandler handler)
    {
        packetHandlers.put(conferenceId, handler);
    }

    /**
     * Removes the {@link PacketHandler} for a particular {@code conderenceId}
     * @param conferenceId the conference ID.
     */
    void removeHandler(String conferenceId)
    {
        packetHandlers.remove(conferenceId);
    }

    /**
     * Packet handler interface.
     */
    interface PacketHandler
    {
        void handlePacket(byte[] buf, int off, int len);
    }

    /**
     * @return the number of bytes received.
     */
    public long getBytesReceived()
    {
        return bytesReceived.get();
    }

    /**
     * @return the number of bytes sent.
     */
    public long getBytesSent()
    {
        return bytesSent.get();
    }

    /**
     * @return the number of packets received.
     */
    public long getPacketsReceived()
    {
        return packetsReceived.get();
    }

    /**
     * @return the number of packets sent.
     */
    public long getPacketsSent()
    {
        return packetsSent.get();
    }

    /**
     * @return the number of packets dropped.
     */
    public long getPacketsDropped()
    {
        return packetsDropped.get();
    }
}
