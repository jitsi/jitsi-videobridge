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
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.collections.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.stats.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
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
    private final Logger logger;

    /**
     * The receive buffer size to set of the socket.
     */
    private static final int SO_RCVBUF = 10 * 1024 * 1024;

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
     * The average send bitrate in the last 1 second.
     */
    private RateStatistics sendBitrate = new RateStatistics(1000);

    /**
     * The average receive bitrate in the last 1 second.
     */
    private RateStatistics receiveBitrate = new RateStatistics(1000);

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
        logger = new LoggerImpl(
            OctoRelay.class.getName(),
            new LogContext(JMap.of("address", address, "port", Integer.toString(port)))
        );
        InetSocketAddress addr
                = new InetSocketAddress(InetAddress.getByName(address), port);
        socket = new DatagramSocket(addr);
        socket.setReceiveBufferSize(SO_RCVBUF);
        logger.info("Initialized OctoRelay with address " + addr +
                ". Receive buffer size " + socket.getReceiveBufferSize() +
                " (asked for " + SO_RCVBUF + ").");

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
        byte[] buf = ByteBufferPool.getBuffer(1500);
        DatagramPacket p = new DatagramPacket(buf, 0, 1500);
        while (true)
        {
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
     * @param buf the buffer which contains the received data.
     * @param off the offset where the data starts.
     * @param len the length
     */
    private void handlePacket(byte[] buf, int off, int len)
    {
        bytesReceived.addAndGet(len);
        packetsReceived.incrementAndGet();
        receiveBitrate.update(len, System.currentTimeMillis());

        String conferenceId = OctoPacket.readConferenceId(buf, off, len);
        if (conferenceId == null)
        {
            logger.warn("Invalid Octo packet, can not read conference ID.");
            packetsDropped.incrementAndGet();
            return;
        }

        PacketHandler handler = packetHandlers.get(conferenceId);
        if (handler == null)
        {
            logger.warn("Received an Octo packet for an unknown conference: "
                    + conferenceId);
            packetsDropped.incrementAndGet();
            return;
        }

        MediaType mediaType = OctoPacket.readMediaType(buf, off, len);
        String sourceEndpointId = OctoPacket.readEndpointId(buf, off, len);

        switch (mediaType)
        {
        case AUDIO:
        case VIDEO:
            int rtpLen = len - OCTO_HEADER_LENGTH;
            byte[] bufCopy
                = ByteBufferPool.getBuffer(
                        rtpLen +
                            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                            RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET);
            System.arraycopy(
                    buf, off + OCTO_HEADER_LENGTH,
                    bufCopy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    rtpLen);
            handler.handlePacket(
                new UnparsedPacket(
                        bufCopy,
                        RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                        rtpLen),
                sourceEndpointId);
            break;
        case DATA:
            String msg
                = new String(
                        buf,
                        off + OCTO_HEADER_LENGTH,
                        len - OCTO_HEADER_LENGTH,
                        StandardCharsets.UTF_8);

            if (logger.isDebugEnabled())
            {
                logger.debug("Received a message in an Octo data packet: " + msg);
            }

            handler.handleMessage(msg);
            break;
        default:
            logger.warn("Wrong media type: " + mediaType);
            packetsDropped.incrementAndGet();
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

    /**
     * Sends an RTP packet encapsulated in Octo to the specified targets.
     */
    void sendPacket(
            Packet packet,
            Set<SocketAddress> targets,
            String conferenceId,
            String endpointId)
    {
        send(
                packet.getBuffer(),
                packet.getOffset(),
                packet.getLength(),
                targets,
                conferenceId,
                endpointId == null ? "ffffffff" : endpointId,
                MediaType.VIDEO);
    }

    /**
     * Sends an string message encapsulated in Octo to the specified targets.
     */
    void sendString(
            String str,
            Set<SocketAddress> targets,
            String conferenceId,
            String endpointId)
    {
        byte[] msgBytes = str.getBytes(StandardCharsets.UTF_8);
        send(
                msgBytes, 0, msgBytes.length,
                targets,
                conferenceId, endpointId,
                MediaType.DATA);
    }

    /**
     * Sends a specific {@code byte[]} encapsulated in Octo to the
     * specified list of targets.
     * @param buf the array which holds the data to send.
     * @param off the offset at which data starts.
     * @param len the length of the data to send.
     * @param targets the list of targets to send to.
     * @param conferenceId the ID of the conference.
     * @param endpointId the ID of the source endpoint.
     * @param mediaType the media type.
     */
    void send(
            byte[] buf,
            int off,
            int len,
            Set<SocketAddress> targets,
            String conferenceId,
            String endpointId,
            MediaType mediaType)
    {
        int octoPacketLength = len + OCTO_HEADER_LENGTH;
        byte[] newBuf;
        int newOff;

        // Not all packets come from a valid endpoint (e.g. some packets
        // originate from the bridge). Use a default value for them.
        if (endpointId == null)
        {
            endpointId = "ffffffff";
        }

        if (off >= OCTO_HEADER_LENGTH)
        {
            newOff = off - OCTO_HEADER_LENGTH;
            newBuf = buf;
        }
        else if (buf.length >= octoPacketLength)
        {
            System.arraycopy(buf, off, buf, OCTO_HEADER_LENGTH, len);
            newOff = 0;
            newBuf = buf;
        }
        else
        {
            newBuf = ByteBufferPool.getBuffer(octoPacketLength);
            newOff = 0;
            System.arraycopy(buf, off, newBuf, OCTO_HEADER_LENGTH, len);
        }

        OctoPacket.writeHeaders(
                newBuf, newOff,
                true /* source is a relay */,
                mediaType,
                0 /* simulcast layers info */,
                conferenceId,
                endpointId);
        DatagramPacket datagramPacket
                = new DatagramPacket(newBuf, newOff, octoPacketLength);

        for (SocketAddress target : targets)
        {
            datagramPacket.setSocketAddress(target);
            try
            {
                bytesSent.addAndGet(octoPacketLength);
                packetsSent.incrementAndGet();
                sendBitrate.update(octoPacketLength, System.currentTimeMillis());
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
        synchronized (packetHandlers)
        {
            if (packetHandlers.containsKey(conferenceId))
            {
                logger.warn("Replacing an existing packet handler for gid="
                        + conferenceId);
            }
            packetHandlers.put(conferenceId, handler);
        }
    }

    /**
     * Removes the {@link PacketHandler} for a particular {@code conferenceId}
     * @param conferenceId the conference ID.
     */
    void removeHandler(String conferenceId, PacketHandler handler)
    {
        synchronized (packetHandlers)
        {
            // If the Colibri conference for this GID was re-created, and the
            // original Conference object is expired after a new packet handler
            // was registered, the new packet handler should not be removed (as
            // this would break the new conference).
            PacketHandler existingHandler = packetHandlers.get(conferenceId);
            if (handler == existingHandler)
            {
                packetHandlers.remove(conferenceId);
            }
        }
    }

    /**
     * Packet handler interface.
     */
    interface PacketHandler
    {
        /**
         * Handles an RTP or RTCP packet.
         * @param packet the packet.
         * @param sourceEndpointId the ID of the endpoint which is the original
         * sender of the packet.
         */
        void handlePacket(Packet packet, String sourceEndpointId);

        /**
         * Handles a string message.
         * @param message message.
         */
        void handleMessage(String message);
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

    /**
     * @return the send bitrate in bits per second
     */
    public long getSendBitrate()
    {
        return sendBitrate.getRate();
    }

    /**
     * @return the receive bitrate in bits per second
     */
    public long getReceiveBitrate()
    {
        return receiveBitrate.getRate();
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("relayId", relayId);
        debugState.put("publicAddress", publicAddress);
        debugState.put("port", port);
        debugState.put("bytesReceived", bytesReceived.get());
        debugState.put("bytesSent", bytesSent.get());
        debugState.put("packetsReceived", packetsReceived.get());
        debugState.put("packetsSent", packetsSent.get());
        debugState.put("packetsDropped", packetsDropped.get());

        return debugState;
    }
}
