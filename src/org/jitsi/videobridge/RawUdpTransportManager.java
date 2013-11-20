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
        super(channel);

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
     */
    @Override
    public void close()
    {
        super.close();

        if (streamConnector != null)
            streamConnector.close();
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
        ComponentImpl component
            = getChannel()
                .getContent()
                    .getConference()
                        .getVideobridge()
                            .getComponent();
        BundleContext bundleContext = component.getBundleContext();
        NetworkAddressManagerService nams
            = ServiceUtils.getService(
                    bundleContext,
                    NetworkAddressManagerService.class);
        InetAddress bindAddr = null;

        if (nams != null)
        {
            String domain = component.getDomain();

            if ((domain != null) && (domain.length() != 0))
            {
                /*
                 * The domain reported by the Jabber component contains its
                 * dedicated subdomain and the goal here is to get the domain of
                 * the XMPP server.
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
                        uhe.printStackTrace(System.err);
                    }
                }
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
    public void describe(ColibriConferenceIQ.Channel iq)
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

            iq.setHost(host);
            iq.setRTCPPort(rtcpPort);
            iq.setRTPPort(rtpPort);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        StreamConnector streamConnector = getStreamConnector();

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
     */
    @Override
    public StreamConnector getStreamConnector()
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
    public MediaStreamTarget getStreamTarget()
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
    public boolean startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
         return false;
    }

    /**
     * {@inheritDoc}
     *
     * The implementation of <tt>RawUdpTransportManager</tt> does nothing
     * because it does not establish connectivity.
     */
    @Override
    public void wrapupConnectivityEstablishment()
    {
    }
}
