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
package org.jitsi.videobridge;

import java.io.*;
import java.net.*;
import java.util.*;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.xmpp.*;
import org.osgi.framework.*;

/**
 * Implements the Jingle Raw UDP transport.
 *
 * @author Lyubomir Marinov
 */
public class RawUdpTransportManager
    extends TransportManager
{
    /**
     * The {@link Logger} used by the {@link RawUdpTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(RawUdpTransportManager.class);

    /**
     * The single <tt>Channel</tt> in this instance.
     */
    private final Channel channel;

    /**
     * The generation of the candidates produced by this Jingle transport.
     */
    private final int generation;

    /**
     * The ID of the RTCP candidate produced by this Jingle transport.
     */
    private final String rtcpCandidateID;

    /**
     * The ID of the RTP candidate produced by this Jingle transport.
     */
    private final String rtpCandidateID;

    /**
     * The <tt>StreamConnector</tt> that represents the datagram sockets
     * allocated by this instance for the purposes of RTP and RTCP transmission.
     */
    private final StreamConnector streamConnector;

    /**
     * Indicates whether this transport manager has been started.
     * {@link Channel#transportConnected()} call has been delayed to the moment
     * when we receive first transport info for this channel
     * ({@link #startConnectivityEstablishment(IceUdpTransportPacketExtension)}).
     * Flag is used to avoid calling it twice.
     */
    private boolean started = false;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>RawUdpTransportManager</tt> instance.
     *
     * @param channel the <tt>Channel</tt> which is initializing the new
     * instance
     */
    public RawUdpTransportManager(Channel channel)
        throws IOException
    {
        super();

        this.channel = channel;
        this.logger
            = Logger.getLogger(
                    classLogger,
                    channel.getContent().getConference().getLogger());
        addChannel(channel);

        streamConnector = createStreamConnector();
        /*
         * Each candidate harvest modifies the generation and the IDs of the RTP
         * and RTCP candidates.
         */
        generation = 0;
        rtpCandidateID = generateCandidateID();
        rtcpCandidateID = generateCandidateID();
    }

    /**
     * {@inheritDoc}
     *
     * RawUdpTransportManager only supports a single channel.
     */
    @Override
    public boolean addChannel(Channel c)
    {
        return getChannels().isEmpty() ? super.addChannel(c) : false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        super.close();

        if (streamConnector != null)
            streamConnector.close();
    }

    /**
     * {@inheritDoc}
     * Make sure that {@link #close()} is called.
     */
    @Override
    public boolean close(Channel channel)
    {
        if (channel == this.channel)
        {
            super.close(channel);
            channel.transportClosed();
            close();

            return true;
        }
        return false;
    }

    /**
     * Returns an <code>InetAddress</code> object encapsulating what is most
     * likely the machine's LAN IP address.
     * <p>
     * This method is intended for use as a replacement of JDK method
     * <code>InetAddress.getLocalHost</code>, because that method is ambiguous
     * on Linux systems. Linux systems enumerate the loopback network
     * interface the same way as regular LAN network interfaces, but the JDK
     * <code>InetAddress.getLocalHost</code> method does not specify the
     * algorithm used to select the address returned under such circumstances,
     * and will often return the loopback address, which is not valid for
     * network communication. Details
     * <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4665037">
     * here</a>.
     * <p>
     * This method will scan all IP addresses on all network interfaces on
     * the host machine to determine the IP address most likely to be the
     * machine's LAN address. If the machine has multiple IP addresses, this
     * method will prefer a site-local IP address (e.g. 192.168.x.x or
     * 10.10.x.x, usually IPv4) if the machine has one (and will return the
     * first site-local address if the machine has more than one), but if the
     * machine does not hold a site-local address, this method will return
     * simply the first non-loopback address found (IPv4 or IPv6).
     * <p>
     * If this method cannot find a non-loopback address using this selection
     * algorithm, it will fall back to calling and returning the result of JDK
     * method <code>InetAddress.getLocalHost</code>.
     * <p>
     *
     * @throws UnknownHostException If the LAN address of the machine cannot
     * be found.
     */
    private static InetAddress getLocalHostLanAddress()
            throws UnknownHostException
    {
        try
        {
            InetAddress candidateAddress = null;
            // Iterate all NICs (network interface cards)...
            for (Enumeration<NetworkInterface> networkInterfaces =
                    NetworkInterface.getNetworkInterfaces();
                 networkInterfaces.hasMoreElements(); )
            {
                NetworkInterface networkInterface =
                        networkInterfaces.nextElement();
                // Iterate all IP addresses assigned to each card...
                for (Enumeration<InetAddress> inetAddresses =
                        networkInterface.getInetAddresses();
                     inetAddresses.hasMoreElements(); )
                {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress())
                    {
                        if (inetAddress.isSiteLocalAddress())
                        {
                            // Found non-loopback site-local address.
                            // Return it immediately...
                            return inetAddress;
                        }
                        else if (candidateAddress == null)
                        {
                            // Found non-loopback address, but not necessarily
                            // site-local. Store it as a candidate to be
                            // returned if site-local address is not
                            // subsequently found...
                            candidateAddress = inetAddress;
                            // Note that we don't repeatedly assign
                            // non-loopback non-site-local addresses as
                            // candidates, only the first. For subsequent
                            // iterations, candidate will be non-null.
                        }
                    }
                }
            }
            if (candidateAddress != null)
            {
                // We did not find a site-local address, but we found some
                // other non-loopback address. Server might have a
                // non-site-local address assigned to its NIC (or it might be
                // running IPv6 which deprecates the "site-local" concept).
                // Return this non-loopback candidate address...
                return candidateAddress;
            }
            // At this point, we did not find a non-loopback address. Fall back
            // to returning whatever InetAddress.getLocalHost() returns...
            InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
            if (jdkSuppliedAddress == null)
            {
                throw new UnknownHostException("The JDK InetAddress" +
                        ".getLocalHost() method unexpectedly returned null.");
            }
            return jdkSuppliedAddress;
        }
        catch (Exception e)
        {
            UnknownHostException unknownHostException =
                    new UnknownHostException(
                            "Failed to determine LAN address: " + e);
            unknownHostException.initCause(e);
            throw unknownHostException;
        }
    }

    /**
     * Allocates the datagram sockets expected of this <tt>TransportManager</tt>
     * for the purposes of RTCP and RTP transmission and represents them in the
     * form of a <tt>StreamConnector</tt> instance.
     *
     * @return a new <tt>StreamConnector</tt> which represents the datagram
     * sockets allocated by this instance for the purposes of RTCP and RTP
     * transmission
     * @throws IOException if the allocation of datagram sockets fails
     */
    private StreamConnector createStreamConnector()
        throws IOException
    {
        /*
         * Determine the local InetAddress the new StreamConnector is to attempt
         * to bind to.
         */
        BundleContext bundleContext = channel.getBundleContext();
        NetworkAddressManagerService nams
            = ServiceUtils.getService(
                    bundleContext,
                    NetworkAddressManagerService.class);
        InetAddress bindAddr = null;

        if (nams != null)
        {
            /*
             * If there are any ComponentImpl instances implementing the XMPP
             * API of Jitsi Videobridge, their domains may be used as a hint to
             * the NetworkAddressManagerService.
             */
            Content content = channel.getContent();
            Conference conference = content.getConference();
            Videobridge videobridge = conference.getVideobridge();

            for (ComponentImpl component : videobridge.getComponents())
            {
                String domain = component.getDomain();

                if ((domain != null) && (domain.length() != 0))
                {
                    /*
                     * The domain reported by the Jabber component contains its
                     * dedicated subdomain and the goal here is to get the
                     * domain of the XMPP server.
                     */
                    int subdomainEnd = domain.indexOf('.');

                    if (subdomainEnd >= 0)
                        domain = domain.substring(subdomainEnd + 1);
                    if (domain.length() != 0)
                    {
                        try
                        {
                            bindAddr
                                = nams.getLocalHost(
                                        NetworkUtils.getInetAddress(domain));
                        }
                        catch (UnknownHostException uhe)
                        {
                            logger.info(
                                    "Failed to get InetAddress from " + domain
                                        + " for channel " + channel.getID()
                                        + " of content " + content.getName()
                                        + " of conference " + conference.getID()
                                        + ".",
                                    uhe);
                        }
                    }
                }
                if (bindAddr != null)
                    break;
            }
        }

        if (bindAddr == null)
        {
            // Note that this is only kept to preserve the previous behaviour
            // of using the address configured as NAT_HARVESTER_LOCAL_ADDRESS,
            // which doesn't seem correct.
            ConfigurationService cfg
                    = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);

            InetAddress localAddress = null;
            if (cfg != null)
            {
                String localAddressStr
                    = cfg.getString(
                            HarvesterConfiguration.NAT_HARVESTER_LOCAL_ADDRESS);
                if (localAddressStr != null && !localAddressStr.isEmpty())
                {
                    localAddress = InetAddress.getByName(localAddressStr);
                }
            }

            if (localAddress != null)
                bindAddr = localAddress;
            else
                bindAddr = getLocalHostLanAddress();
        }

        StreamConnector streamConnector = new DefaultStreamConnector(bindAddr);

        /*
         * Try to follow the convention that the RTCP port is the next one after
         * the RTP port.
         */
        streamConnector.getDataSocket();
        streamConnector.getControlSocket();

        return streamConnector;
    }

    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation to add compatibility with legacy Jitsi
     * and Jitsi Videobridge.
     */
    @Override
    @SuppressWarnings("deprecation") // Compatibility with legacy Jitsi and
                                     // Jitsi Videobridge
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        super.describe(iq);

        IceUdpTransportPacketExtension transport = iq.getTransport();

        if (transport != null)
        {
            String host = null;
            int rtcpPort = 0;
            int rtpPort = 0;

            for (CandidatePacketExtension candidate
                    : transport.getCandidateList())
            {
                switch (candidate.getComponent())
                {
                case CandidatePacketExtension.RTCP_COMPONENT_ID:
                    rtcpPort = candidate.getPort();
                    break;
                case CandidatePacketExtension.RTP_COMPONENT_ID:
                    rtpPort = candidate.getPort();
                    break;
                default:
                    continue;
                }

                if ((host == null) || (host.length() == 0))
                    host = candidate.getIP();
            }

            if(iq instanceof ColibriConferenceIQ.Channel)
            {
                ColibriConferenceIQ.Channel channelIq
                    = (ColibriConferenceIQ.Channel) iq;

                channelIq.setHost(host);
                channelIq.setRTCPPort(rtcpPort);
                channelIq.setRTPPort(rtpPort);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        StreamConnector streamConnector = getStreamConnector(channel);

        // RTP
        {
            DatagramSocket socket = streamConnector.getDataSocket();
            CandidatePacketExtension candidate = new CandidatePacketExtension();

            candidate.setComponent(
                    CandidatePacketExtension.RTP_COMPONENT_ID);
            candidate.setGeneration(generation);
            candidate.setID(rtpCandidateID);
            candidate.setIP(socket.getLocalAddress().getHostAddress());
            candidate.setPort(socket.getLocalPort());
            candidate.setType(CandidateType.host);

            pe.addCandidate(candidate);
        }

        // RTCP
        {
            DatagramSocket socket = streamConnector.getControlSocket();
            CandidatePacketExtension candidate = new CandidatePacketExtension();

            candidate.setComponent(CandidatePacketExtension.RTCP_COMPONENT_ID);
            candidate.setGeneration(generation);
            candidate.setID(rtcpCandidateID);
            candidate.setIP(socket.getLocalAddress().getHostAddress());
            candidate.setPort(socket.getLocalPort());
            candidate.setType(CandidateType.host);

            pe.addCandidate(candidate);
        }
    }

    /**
     * {@inheritDoc}
     * RawUdpTransportManager does not implement DTLS.
     */
    @Override
    public SrtpControl getSrtpControl(Channel channel)
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        return streamConnector;
    }

    /**
     * {@inheritDoc}
     *
     * The implementation of <tt>RawUdpTransportManager</tt> always returns
     * <tt>null</tt> because it does not establish connectivity and,
     * consequently, does not learn the remote addresses and requires latching.
     */
    @Override
    public MediaStreamTarget getStreamTarget(Channel channel)
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getXmlNamespace()
    {
        return RawUdpTransportPacketExtension.NAMESPACE;
    }

    /**
     * {@inheritDoc}
     *
     * The implementation of <tt>RawUdpTransportManager</tt> always returns
     * <tt>false</tt> because it does not establish connectivity.
     */
    @Override
    public void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
        if (started)
        {
            return;
        }

        if (!(transport instanceof RawUdpTransportPacketExtension))
        {
            logger.error(
                "Only RAW transport is accepted by this transport manager.");
            return;
        }

        // streamConnector is ready, let the channel start its stream (it
        // will have to implement latching itself)
        channel.transportConnected();

        started = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected()
    {
        return true;
    }
}
