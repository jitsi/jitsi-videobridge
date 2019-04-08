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
package org.jitsi.videobridge.octo;

import org.jitsi.xmpp.extensions.jingle.*;
import net.java.sip.communicator.util.*;
import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

/**
 * A {@link TransportManager} implementation for communication with other
 * jitsi-videobridge instances and/or relays.
 *
 * @author Boris Grozev
 */
public class OctoTransportManager
    extends TransportManager
{
    /**
     * The {@link Logger} used by the {@link OctoTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoTransportManager.class);

    /**
     * A namespace which identifies the Octo transport manager.
     */
    public static final String NAMESPACE = "http://jitsi.org/octo";

    /**
     * The timeout to set on the sockets that we create.
     */
    private static final int SO_TIMEOUT = 1000;

    /**
     * Converts a "relay ID" to a socket address. The current implementation
     * assumes that the ID has the form of "address:port".
     * @param relayId the relay ID to convert
     * @return the socket address encoded in {@code relayId}.
     */
    private static SocketAddress relayIdToSocketAddress(String relayId)
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
     * The single {@link OctoChannel} that this {@link TransportManager} is
     * associated with.
     */
    private OctoChannel channel;

    /**
     * The underlying {@link OctoRelay} which manages the socket through which
     * packets will be sent, and from which packets will be received.
     */
    private OctoRelay octoRelay;

    /**
     * The {@link MultiplexedDatagramSocket} derived from the {@link OctoRelay}'s
     * socket, which receives only RTP packets destined for this transport
     * manager (or rather its {@link OctoChannel}.
     */
    private DatagramSocket rtpSocket;

    /**
     * The {@link MultiplexedDatagramSocket} derived from the {@link OctoRelay}'s
     * socket, which receives only RTCP packets destined for this transport
     * manager (or rather its {@link OctoChannel}.
     */
    private DatagramSocket rtcpSocket;

    /**
     * The (non-functional) {@link SrtpControl} maintained by this instance,
     * which is used as the {@link SrtpControl} of the {@link MediaStream} of
     * the channel. It is necessary to use a {@link NullSrtpControl}, because
     * {@link MediaStream} defaults to a ZRTP implementation otherwise.
     */
    private final SrtpControl srtpControl = new NullSrtpControl();

    /**
     * The list of addresses of remote relays to which outgoing packets should
     * be sent. This list is maintained by the conference organizer based on
     * the videobridges participating in the conference, and configured to this
     * {@link TransportManager} through Colibri signaling.
     */
    private List<SocketAddress> remoteRelays;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Synchronizes access to {@link #rtpSocket} and {@link #rtcpSocket}.
     */
    private final Object socketsSyncRoot = new Object();

    /**
     * Initializes a new {@link OctoTransportManager} instance.
     * @param channel the associated {@link OctoChannel}
     */
    public OctoTransportManager(Channel channel)
    {
        if (!(channel instanceof OctoChannel))
        {
            throw new IllegalArgumentException("channel is not an OctoChannel");
        }

        this.channel = (OctoChannel) channel;

        logger
            = Logger.getLogger(
                classLogger,
                channel.getContent().getConference().getLogger());

        OctoRelayService relayService
            = ServiceUtils.getService(
                    channel.getBundleContext(),
                    OctoRelayService.class);
        octoRelay = Objects.requireNonNull(relayService.getRelay());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        super.close();

        synchronized (socketsSyncRoot)
        {
            if (rtpSocket != null)
            {
                rtpSocket.close();
            }
            if (rtcpSocket != null)
            {
                rtcpSocket.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        try
        {
            initializeSockets();
        }
        catch (IOException ioe)
        {
            logger.error("Failed to initialize sockets: ", ioe);
            return null;
        }

        // Do we want to cache the instance?
        return new DefaultStreamConnector(
            rtpSocket,
            rtcpSocket,
            true /* rtcpmux */);
    }

    /**
     * Initializes the filtered sockets derived from the socket maintained
     * by the {@link OctoRelay}, which are to receive RTP (or RTCP) packets
     * from the {@link OctoChannel} associated with this transport manager.
     * @throws IOException
     */
    private void initializeSockets()
        throws IOException
    {
        synchronized (socketsSyncRoot)
        {
            if (rtpSocket != null)
            {
                //rtpSocket and rtcpSocket are always set together
                return;
            }

            MultiplexingDatagramSocket relaySocket = octoRelay.getSocket();

            rtpSocket
                = createOctoSocket(
                    relaySocket.getSocket(channel.getDatagramFilter(false)));
            rtcpSocket
                = createOctoSocket(
                    relaySocket.getSocket(channel.getDatagramFilter(true)));
        }
    }

    /**
     * Creates a {@link DatagramSocket} instance which wraps a specific other
     * {@link DatagramSocket} instance for the purposes of Octo. Specifically,
     * it overrides {@code receive()} to strip the Octo headers, and overrides
     * {@code send()} to send the packet to the addresses of all remote relays.
     *
     * @param socket the socket to be wrapped.
     * @return the wrapped socket.
     * @throws SocketException
     */
    private DatagramSocket createOctoSocket(DatagramSocket socket)
        throws SocketException
    {
        DatagramSocket s = new DelegatingDatagramSocket(socket)
        {
            @Override
            public void receive(DatagramPacket p)
                throws IOException
            {
                super.receive(p);

                try
                {
                    // Strip the Octo header. The code which actually handles
                    // the values in the Octo header has already executed in
                    // the accept() method of the DatagramPacketFilter for the
                    // associated channel.
                    p.setData(
                            p.getData(),
                            p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                            p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);
                }
                catch (Exception e)
                {
                    // TODO: more graceful handling
                    logger.error(
                        "Failed to strip Octo header while receiving a packet:"
                            + e);
                }
            }

            @Override
            public void send(DatagramPacket p)
                throws IOException
            {
                doSend(p, true);
            }
        };

        // With the hierarchy of sockets that we use for Octo (Delegating ->
        // Multiplexed -> Multiplexing -> DatagramSocket) the calls receive()
        // are handled by the Multiplexing instance. Since it is persistent, it
        // will not get closed when this socket instance is closed, and will
        // therefore not throw a SocketClosedException. This means that we can
        // not rely on this exception to stop the receive thread
        // (RTPConnectorInputStream#receiveThread), and therefore we need a
        // finite timeout.
        s.setSoTimeout(SO_TIMEOUT);

        return s;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTarget getStreamTarget(Channel channel)
    {
        // We'll just hack in the local address so that we have something
        // non-null. It should never be used anyway.
        DatagramSocket socket = octoRelay.getSocket();
        InetAddress inetAddress = socket.getLocalAddress();
        int port = socket.getLocalPort();
        return new MediaStreamTarget(inetAddress, port, inetAddress, port);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        // No need to describe anything at this point.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SrtpControl getSrtpControl(Channel channel)
    {
        // We don't want SRTP with Octo. But returning null makes the
        // MediaStream initialize ZRTP.
        return srtpControl;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getXmlNamespace()
    {
        return NAMESPACE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startConnectivityEstablishment(
        IceUdpTransportPacketExtension transport)
    {
        // No-op. We're always connected.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected()
    {
        return true;
    }

    /**
     * Sets the list of IDs of remote relays, i.e. the targets that this
     * {@link TransportManager} will send packets to.
     */
    public void setRelayIds(List<String> relayIds)
    {
        List<SocketAddress> remoteRelays = new ArrayList<>(relayIds.size());

        for (String relayId : relayIds)
        {
            SocketAddress socketAddress = relayIdToSocketAddress(relayId);
            if (socketAddress == null)
            {
                logger.error(
                    "Could not convert a relay ID to a socket address: "
                        + relayId);
                continue;
            }

            if (remoteRelays.contains(socketAddress))
            {
                logger.info("Relay ID duplicate: " + relayId);
                continue;
            }

            remoteRelays.add(socketAddress);
        }

        this.remoteRelays = remoteRelays;
    }

    /**
     * Sends a specific {@link DatagramPacket} to all targets of this
     * {@link TransportManager} (i.e. to all {@link #remoteRelays}).
     * The payload of the packet is first encapsulated in Octo (i.e. the fixed
     * size Octo header is prepended).
     *
     * @param p the packet to send.
     * @param addHeaders whether this call should add the Octo headers to the
     * packet before sending or not.
     * @throws IOException if calling {@code send()} on any of the target
     * sockets results in an {@link IOException}.
     */
    private void doSend(DatagramPacket p, boolean addHeaders)
        throws IOException
    {
        if (addHeaders)
        {
            p = addOctoHeaders(p);
        }
        DatagramSocket relaySocket = octoRelay.getSocket();
        IOException exception = null;
        int exceptions = 0;

        if (remoteRelays != null)
        {
            for (SocketAddress remoteAddress : remoteRelays)
            {
                try
                {
                    p.setSocketAddress(remoteAddress);

                    relaySocket.send(p);
                }
                catch (IOException ioe)
                {
                    exceptions++;
                    exception = ioe;
                }
            }
        }

        if (exception != null)
        {
            logger.warn(
                "Caught " + exceptions + " while trying to send a packet.");
            throw exception;
        }
    }

    /**
     * Prepends the fixed-length Octo header to a packet.
     * @param p the packet
     * @return the packet to which an Octo header was added.
     */
    private DatagramPacket addOctoHeaders(DatagramPacket p)
    {
        byte[] buf = p.getData();
        int off = p.getOffset();
        int len = p.getLength();


        if (off >= OctoPacket.OCTO_HEADER_LENGTH)
        {
            // If there is space before the offset, just use it.
            off -= OctoPacket.OCTO_HEADER_LENGTH;
        }
        else if (buf.length >= len + OctoPacket.OCTO_HEADER_LENGTH)
        {
            // Otherwise, if the byte[] is big enough, make enough space before
            // the payload by moving it.
            System.arraycopy(buf, off, buf, OctoPacket.OCTO_HEADER_LENGTH, len);
            off = 0;
        }
        else
        {
            // Otherwise a new byte[] is needed.
            byte[] newBuf = new byte[len + OctoPacket.OCTO_HEADER_LENGTH];
            System.arraycopy(
                buf, off, newBuf, OctoPacket.OCTO_HEADER_LENGTH, len);

            buf = newBuf;
            off = 0;
        }


        len += OctoPacket.OCTO_HEADER_LENGTH;
        p.setData(buf, off, len);

        OctoPacket.writeHeaders(
            buf, off,
            true /* source is a relay */,
            channel.getMediaType(),
            0 /* simulcast layers info */,
            channel.getConferenceId(),
            /* todo: add source endpoint id */ "ffffffff");
        return p;
    }

    /**
     * Sends a string message with a specific Octo conference ID and a specific
     * source endpoint over the Octo transport.
     * @param msg the message to send.
     * @param sourceEndpointId the ID of the source endpoint or {@code null}.
     * @param conferenceId the Octo conference ID.
     */
    void sendMessage(String msg, String sourceEndpointId, String conferenceId)
    {
        if (StringUtils.isNullOrEmpty(sourceEndpointId))
        {
            sourceEndpointId = "ffffffff";
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("Sending a message through Octo: " + msg);
        }

        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[msgBytes.length + OctoPacket.OCTO_HEADER_LENGTH];
        System.arraycopy(
            msgBytes, 0,
            buf, OctoPacket.OCTO_HEADER_LENGTH,
            msgBytes.length);

        OctoPacket.writeHeaders(
            buf, 0,
            true /* source is a relay */,
            MediaType.DATA,
            0 /* simulcast layers info */,
            conferenceId,
            sourceEndpointId);

        try
        {
            doSend(
                new DatagramPacket(buf, 0, buf.length),
                false);
        }
        catch (IOException ioe)
        {
            logger.error("Failed to send Octo data message: ", ioe);
        }
    }
}
