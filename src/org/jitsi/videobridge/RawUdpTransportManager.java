/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.net.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
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
     * The <tt>Logger</tt> used by the <tt>RawUdpTransportManager</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
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
        addChannel(channel);

        streamConnector = createStreamConnector();
        /*
         * Each candidate harvest modifies the generation and the IDs of the RTP
         * and RTCP candidates.
         */
        generation = 0;
        rtpCandidateID = generateCandidateID();
        rtcpCandidateID = generateCandidateID();

        // streamConnector is ready, let the channel start its stream (it
        // will have to implement latching itself)
        channel.transportConnected();
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
            bindAddr = InetAddress.getLocalHost();

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
    public DtlsControl getDtlsControl(Channel channel)
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
    }
}
