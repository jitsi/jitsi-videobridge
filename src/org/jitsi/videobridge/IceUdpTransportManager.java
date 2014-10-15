/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

import javax.media.rtp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.media.*;
import net.java.sip.communicator.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.osgi.framework.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class IceUdpTransportManager
    extends TransportManager
{
    /**
     * The name default of the single <tt>IceStream</tt> that this
     * <tt>TransportManager</tt> will create/use.
     */
    private static final String DEFAULT_ICE_STREAM_NAME = "stream";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be explicitly disabled.
     */
    private static final String DISABLE_AWS_HARVESTER
        = "org.jitsi.videobridge.DISABLE_AWS_HARVESTER";

    /**
     * The name of the property which disables the use of a
     * <tt>MultiplexingTcpHostHarvester</tt>.
     */
    private static final String DISABLE_TCP_HARVESTER
        = "org.jitsi.videobridge.DISABLE_TCP_HARVESTER";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be forced without first trying to auto detect it.
     */
    private static final String FORCE_AWS_HARVESTER
        = "org.jitsi.videobridge.FORCE_AWS_HARVESTER";

    /**
     * The <tt>Logger</tt> used by the <tt>IceUdpTransportManager</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(IceUdpTransportManager.class);

    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the local
     * address that we should be mapping.
     */
    private static final String NAT_HARVESTER_LOCAL_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS";

    /**
     * Contains the name of the property that would tell us if we should use
     * address mapping as one of our NAT traversal options as well as the public
     * address that we should be using in addition to our local one.
     */
    private static final String NAT_HARVESTER_PUBLIC_ADDRESS
        = "org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS";

    /**
     * The default port that the <tt>MultiplexingTcpHostHarvester</tt> will
     * bind to.
     */
    private static final int TCP_DEFAULT_PORT = 443;

    /**
     * The port on which the <tt>MultiplexingTcpHostHarvester</tt> will bind to
     * if no port is specifically configured, and binding to
     * <tt>DEFAULT_TCP_PORT</tt> fails (for example, if the process doesn't have
     * the required privileges to bind to a port below 1024).
     */
    private static final int TCP_FALLBACK_PORT = 4443;

    /**
     * The name of the property which specifies an additional port to be
     * advertised by the TCP harvester.
     */
    private static final String TCP_HARVESTER_MAPPED_PORT
        = "org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT";

    /**
     * The name of the property which controls the port to which the
     * <tt>MultiplexingTcpHostHarvester</tt> will bind.
     */
    private static final String TCP_HARVESTER_PORT
        = "org.jitsi.videobridge.TCP_HARVESTER_PORT";

    /**
     * The name of the property which controls the use of ssltcp candidates by
     * <tt>MultiplexingTcpHostHarvester</tt>.
     */
    private static final String TCP_HARVESTER_SSLTCP
        = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP";

    /**
     * The default value of the <tt>TCP_HARVESTER_SSLTCP</tt> property.
     */
    private static final boolean TCP_HARVESTER_SSLTCP_DEFAULT = true;

    /**
     * The single <tt>MultiplexingTcpHostHarvester</tt> instance for the
     * application.
     */
    private static MultiplexingTcpHostHarvester tcpHostHarvester = null;

    /**
     * The flag which indicates whether {@link #tcpHostHarvester} has been
     * initialized.
     */
    private static boolean tcpHostHarvesterInitialized = false;

    /**
     * The "mapped port" added to {@link #tcpHostHarvester}, or -1.
     */
    private static int tcpHostHarvesterMappedPort = -1;

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level
     */
    private static void logd(String s)
    {
        logger.info(s);
    }

    /**
     * The single (if any) <tt>Channel</tt> instance, whose sockets are
     * currently configured to accept DTLS packets.
     */
    private Channel channelForDtls = null;

    /**
     * Whether this <tt>TransportManager</tt> has been closed.
     */
    private boolean closed = false;

    /**
     * The <tt>Conference</tt> object that this <tt>TransportManager</tt> is
     * associated with.
     */
    private final Conference conference;

    /**
     * The <tt>Thread</tt> used by this <tt>TransportManager</tt> to wait until
     * {@link #iceAgent} has established a connection.
     */
    private Thread connectThread;

    /**
     * Used to synchronize access to {@link #connectThread}.
     */
    private final Object connectThreadSyncRoot = new Object();

    /**
     * The <tt>DtlsControl</tt> that this <tt>TransportManager</tt> uses.
     */
    private final DtlsControlImpl dtlsControl;

    /**
     * The <tt>Agent</tt> which implements the ICE protocol and which is used
     * by this instance to implement the Jingle ICE-UDP transport.
     */
    private Agent iceAgent;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the <tt>state</tt> of {@link #iceAgent}.
     */
    private final PropertyChangeListener iceAgentStateChangeListener
        = new PropertyChangeListener()
        {
            @Override
            public void propertyChange(PropertyChangeEvent ev)
            {
                iceAgentStateChange(ev);
            }
        };

    /**
     * Whether ICE connectivity has been established.
     */
    private boolean iceConnected = false;

    /**
     * The <tt>IceMediaStream</tt> of {@link #iceAgent} associated with the
     * <tt>Channel</tt> of this instance.
     */
    private final IceMediaStream iceStream;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the properties of the <tt>CandidatePair</tt>s of
     * {@link #iceStream}.
     */
    private final PropertyChangeListener iceStreamPairChangeListener
        = new PropertyChangeListener()
        {
            @Override
            public void propertyChange(PropertyChangeEvent ev)
            {
                iceStreamPairChange(ev);
            }
        };

    /**
     * Whether this <tt>IceUdpTransportManager</tt> will serve as the the
     * controlling or controlled ICE agent.
     */
    private final boolean isControlling;

    /**
     * The number of {@link org.ice4j.ice.Component}-s to create in
     * {@link #iceStream}.
     */
    private int numComponents;

    /**
     * Whether we're using rtcp-mux or not.
     */
    private boolean rtcpmux = false;

    /**
     * The <tt>SctpConnection</tt> instance, if any, added as a <tt>Channel</tt>
     * to this <tt>IceUdpTransportManager</tt>.
     *
     * Currently we support a single <tt>SctpConnection</tt> in one
     * <tt>IceUdpTransportManager</tt> and if it exists, it will receive all
     * DTLS packets.
     */
    private SctpConnection sctpConnection = null;

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param isControlling whether the new instance is to server as a
     * controlling or controlled ICE agent.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean isControlling)
        throws IOException
    {
        this(conference, isControlling, 2, DEFAULT_ICE_STREAM_NAME);
    }

    public IceUdpTransportManager(Conference conference,
                                  boolean isControlling,
                                  int numComponents)
        throws IOException
    {
        this(conference, isControlling, numComponents, DEFAULT_ICE_STREAM_NAME);
    }

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param isControlling whether the new instance is to server as a
     * controlling or controlled ICE agent.
     * @param numComponents the number of ICE components that this instance is
     * to start with.
     * @param iceStreamName the name of the ICE stream to be created by this
     * instance.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean isControlling,
                                  int numComponents,
                                  String iceStreamName)
        throws IOException
    {
        super();

        this.conference = conference;
        this.numComponents = numComponents;
        this.rtcpmux = numComponents == 1;
        this.isControlling = isControlling;

        this.dtlsControl = new DtlsControlImpl(false);
        dtlsControl.registerUser(this);

        iceAgent = createIceAgent(isControlling, iceStreamName);
        iceAgent.addStateChangeListener(iceAgentStateChangeListener);
        iceStream = iceAgent.getStream(iceStreamName);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);
    }

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param isControlling whether the new instance is to server as a
     * controlling or controlled ICE agent.
     * @param iceStreamName the name of the ICE stream to be created by this
     * instance.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean isControlling,
                                  String iceStreamName)
        throws IOException
    {
        this(conference, isControlling, 2, iceStreamName);
    }

    /**
     * {@inheritDoc}
     *
     * Assures that no more than one <tt>SctpConnection</tt> is added. Keeps
     * {@link #sctpConnection} and {@link #channelForDtls} up to date.
     */
    @Override
    public boolean addChannel(Channel channel)
    {
        if (closed)
            return false;

        if (channel instanceof SctpConnection
                && sctpConnection != null
                && sctpConnection != channel)
        {
            logd("Not adding a second SctpConnection to TransportManager.");
            return false;
        }

        if (!super.addChannel(channel))
            return false;

        if (channel instanceof SctpConnection)
        {
            // When an SctpConnection is added, it automatically replaces
            // channelForDtls, because it needs DTLS packets for the application
            // data inside them.
            sctpConnection = (SctpConnection) channel;
            if (channelForDtls != null)
            {
                /*
                 * channelForDtls is necessarily an RtpChannel, because we don't
                 * add more than one SctpConnection. The SctpConnection socket
                 * will automatically accept DTLS.
                 */
                RtpChannel rtpChannelForDtls = (RtpChannel) channelForDtls;

                rtpChannelForDtls.getDatagramFilter(false).setAcceptNonRtp(
                        false);
                rtpChannelForDtls.getDatagramFilter(true).setAcceptNonRtp(
                        false);
            }
            channelForDtls = sctpConnection;
        }
        else if (channelForDtls == null)
        {
            channelForDtls = channel;

            RtpChannel rtpChannel = (RtpChannel) channel;

            // The new channelForDtls will always accept DTLS packets on its
            // RTP socket.
            rtpChannel.getDatagramFilter(false).setAcceptNonRtp(true);
            // If we use rtcpmux, we don't want to accept DTLS packets on the
            // RTCP socket, because they will be duplicated from the RTP socket,
            // because both sockets are actually filters on the same underlying
            // socket.
            rtpChannel.getDatagramFilter(true).setAcceptNonRtp(!rtcpmux);
        }

        updatePayloadTypeFilters();

        if (iceConnected)
            channel.transportConnected();

        return true;
    }

    /**
     * Adds to <tt>iceAgent</tt> videobridge specific candidate harvesters such
     * as an Amazon AWS EC2 specific harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     */
    private void appendVideobridgeHarvesters(Agent iceAgent)
    {
        initializeTcpHarvester();
        if (tcpHostHarvester != null)
            iceAgent.addCandidateHarvester(tcpHostHarvester);

        AwsCandidateHarvester awsHarvester = null;
        //does this look like an Amazon AWS EC2 machine?
        if(AwsCandidateHarvester.smellsLikeAnEC2())
            awsHarvester = new AwsCandidateHarvester();

        ConfigurationService cfg
            = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);

        //if no configuration is found then we simply log and bail
        if (cfg == null)
        {
            logger.info("No configuration found. "
                        + "Will continue without custom candidate harvesters");
            return;
        }

        //now that we have a conf service, check if AWS use is forced and
        //comply if necessary.
        if (awsHarvester == null
                && cfg.getBoolean(FORCE_AWS_HARVESTER, false))
        {
            //ok. this doesn't look like an EC2 machine but since you
            //insist ... we'll behave as if it is.
            logger.info("Forcing use of AWS candidate harvesting.");
            awsHarvester = new AwsCandidateHarvester();
        }


        //append the AWS harvester for AWS machines.
        if( awsHarvester != null
                && !cfg.getBoolean(DISABLE_AWS_HARVESTER, false))
        {
            logger.info("Appending an AWS harvester to the ICE agent.");
            iceAgent.addCandidateHarvester(awsHarvester);
        }

        //if configured, append a mapping harvester.
        String localAddressStr = cfg.getString(NAT_HARVESTER_LOCAL_ADDRESS);
        String publicAddressStr = cfg.getString(NAT_HARVESTER_PUBLIC_ADDRESS);

        if (localAddressStr == null || publicAddressStr == null)
            return;

        TransportAddress localAddress;
        TransportAddress publicAddress;

        try
        {
            // It appears that the port number values ("9") are unused (and
            // presumably it would be too much of a cliché to use "42")
            localAddress
                = new TransportAddress(localAddressStr, 9, Transport.UDP);
            publicAddress
                = new TransportAddress(publicAddressStr, 9, Transport.UDP);

            logger.info("Will append a NAT harvester for " +
                                localAddress + "=>" + publicAddress);

        }
        catch(Exception exc)
        {
            logger.info("Failed to create a NAT harvester for"
                                + " local address=" + localAddressStr
                                + " and public address=" + publicAddressStr);
            return;
        }

        MappingCandidateHarvester natHarvester
            = new MappingCandidateHarvester(publicAddress, localAddress);

        iceAgent.addCandidateHarvester(natHarvester);
    }

    /**
     * Determines whether at least one <tt>LocalCandidate</tt> of a specific ICE
     * <tt>Component</tt> can reach (in the terms of the ice4j library) a
     * specific <tt>RemoteCandidate</tt>
     *
     * @param component the ICE <tt>Component</tt> which contains the
     * <tt>LocalCandidate</tt>s to check whether at least one of them can reach
     * the specified <tt>remoteCandidate</tt>
     * @param remoteCandidate the <tt>RemoteCandidate</tt> to check whether at
     * least one of the <tt>LocalCandidate</tt>s of the specified
     * <tt>component</tt> can reach it
     * @return <tt>true</tt> if at least one <tt>LocalCandidate</tt> of the
     * specified <tt>component</tt> can reach the specified
     * <tt>remoteCandidate</tt>
     */
    private boolean canReach(
            Component component,
            RemoteCandidate remoteCandidate)
    {
        for (LocalCandidate localCandidate : component.getLocalCandidates())
        {
            if (localCandidate.canReach(remoteCandidate))
                return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * TODO: in the case of multiple <tt>Channel</tt>s in one TransportManager
     * it is not clear how to handle changes to the 'initiator' property
     * from individual channels.
     */
    @Override
    protected void channelPropertyChange(PropertyChangeEvent ev)
    {
        super.channelPropertyChange(ev);

        /*
        if (Channel.INITIATOR_PROPERTY.equals(ev.getPropertyName())
                && (iceAgent != null))
        {
            Channel channel = (Channel) ev.getSource();

            iceAgent.setControlling(channel.isInitiator());
        }
        */
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close()
    {
        if (!closed)
        {
            // Set this early to prevent double closing when the last channel
            // is removed.
            closed = true;

            for (Channel channel : getChannels())
                close(channel);

            if (dtlsControl != null)
            {
                dtlsControl.start(null); //stop
                dtlsControl.cleanup(this);
            }

            //DatagramSocket[] datagramSockets = getStreamConnectorSockets();
            if (iceStream != null)
            {
                iceStream.removePairStateChangeListener(
                        iceStreamPairChangeListener);
            }
            if (iceAgent != null)
            {
                iceAgent.removeStateChangeListener(iceAgentStateChangeListener);
                iceAgent.free();
                iceAgent = null;
            }

            /*
             * It seems that the ICE agent takes care of closing these
            if (datagramSockets != null)
            {
                if (datagramSockets[0] != null)
                    datagramSockets[0].close();
                if (datagramSockets[1] != null)
                    datagramSockets[1].close();
            }
            */

            synchronized (connectThreadSyncRoot)
            {
                if (connectThread != null)
                    connectThread.interrupt();
            }

            super.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * Keeps {@link #sctpConnection} and {@link #channelForDtls} up to date.
     */
    @Override
    public boolean close(Channel channel)
    {
        boolean removed = super.close(channel);

        if (removed)
        {
            if (channel == sctpConnection)
            {
                sctpConnection = null;
            }

            if (channel == channelForDtls)
            {
                if (sctpConnection != null)
                {
                    channelForDtls = sctpConnection;
                }
                else if (channel instanceof RtpChannel)
                {
                    RtpChannel newChannelForDtls = null;

                    for (Channel c : getChannels())
                    {
                        if (c instanceof RtpChannel)
                            newChannelForDtls = (RtpChannel) c;
                    }
                    if (newChannelForDtls != null)
                    {
                        newChannelForDtls.getDatagramFilter(false)
                                .setAcceptNonRtp(true);
                        newChannelForDtls.getDatagramFilter(true)
                                .setAcceptNonRtp(!rtcpmux);
                    }
                    channelForDtls = newChannelForDtls;
                }

                if (channel instanceof RtpChannel)
                {
                    RtpChannel rtpChannel = (RtpChannel) channel;

                    rtpChannel.getDatagramFilter(false).setAcceptNonRtp(false);
                    rtpChannel.getDatagramFilter(true).setAcceptNonRtp(false);
                }
            }

            try
            {
                StreamConnector connector = channel.getStreamConnector();

                if (connector != null)
                {
                    DatagramSocket datagramSocket = connector.getDataSocket();

                    if (datagramSocket != null)
                        datagramSocket.close();
                    datagramSocket = connector.getControlSocket();
                    if (datagramSocket != null)
                        datagramSocket.close();

                    Socket socket = connector.getDataTCPSocket();

                    if (socket != null)
                        socket.close();
                    socket = connector.getControlTCPSocket();
                    if (socket != null)
                        socket.close();
                }
            }
            catch (IOException ioe)
            {
                logd(
                        "Failed to close sockets when closing a channel:"
                            + ioe);
            }

            updatePayloadTypeFilters();

            channel.transportClosed();
        }

        if (getChannels().isEmpty())
            close();

        return removed;
    }

    /**
     * Initializes a new <tt>Agent</tt> instance which implements the ICE
     * protocol and which is to be used by this instance to implement the Jingle
     * ICE-UDP transport.
     *
     * @return a new <tt>Agent</tt> instance which implements the ICE protocol
     * and which is to be used by this instance to implement the Jingle ICE-UDP
     * transport
     * @throws IOException if initializing a new <tt>Agent</tt> instance for the
     * purposes of this <tt>TransportManager</tt> fails
     */
    private Agent createIceAgent(boolean isControlling,
                                 String iceStreamName)
            throws IOException
    {
        NetworkAddressManagerService nams
                = ServiceUtils.getService(
                getBundleContext(), NetworkAddressManagerService.class);
        Agent iceAgent = nams.createIceAgent();

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        appendVideobridgeHarvesters(iceAgent);
        iceAgent.setControlling(isControlling);
        iceAgent.setPerformConsentFreshness(true);

        PortTracker portTracker
                = JitsiTransportManager.getPortTracker(null);
        int portBase = portTracker.getPort();

        IceMediaStream iceStream
                = nams.createIceStream(
                numComponents,
                portBase,
                iceStreamName,
                iceAgent);

        // Attempt to minimize subsequent bind retries.
        try
        {
            portTracker.setNextPort(
                    1 + iceStream.getComponent(
                            numComponents > 1 ? Component.RTCP : Component.RTP)
                        .getLocalCandidates()
                        .get(0).getTransportAddress().getPort());
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                Thread.currentThread().interrupt();
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
            else
                portTracker.setNextPort(numComponents + portBase);
        }

        return iceAgent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        if (!closed)
        {
            pe.setPassword(iceAgent.getLocalPassword());
            pe.setUfrag(iceAgent.getLocalUfrag());

            for (Component component : iceStream.getComponents())
            {
                List<LocalCandidate> candidates
                        = component.getLocalCandidates();

                if ((candidates != null) && !candidates.isEmpty())
                {
                    for (LocalCandidate candidate : candidates)
                    {
                        if (candidate.getTransport() == Transport.TCP
                              && tcpHostHarvesterMappedPort != -1
                              && (candidate.getTransportAddress().getPort()
                                   != tcpHostHarvesterMappedPort))
                        {
                            // In case we use a mapped port with the TCP
                            // harvester, do not advertise the candidates with
                            // the actual port that we listen on.
                            continue;
                        }
                        describe(candidate, pe);
                    }
                }
            }

            if (rtcpmux)
                pe.addChildExtension(new RtcpmuxPacketExtension());

            describeDtlsControl(pe);
        }
    }

    /**
     * Adds a new <tt>CandidatePacketExtension</tt> to <tt>pe</tt>, sets the
     * values of its properties to the values of the respective properties of
     * <tt>candidate</tt>.
     *
     * @param candidate the <tt>LocalCandidate</tt> from which to take the values
     * of the properties to set.
     * @param pe the <tt>IceUdpTransportPacketExtension</tt> to which to add a
     * new <tt>CandidatePacketExtension</tt>.
     */
    private void describe(
            LocalCandidate candidate,
            IceUdpTransportPacketExtension pe)
    {
        CandidatePacketExtension candidatePE = new CandidatePacketExtension();
        org.ice4j.ice.Component component = candidate.getParentComponent();

        candidatePE.setComponent(component.getComponentID());
        candidatePE.setFoundation(candidate.getFoundation());
        candidatePE.setGeneration(
                component.getParentStream().getParentAgent().getGeneration());
        candidatePE.setID(generateCandidateID(candidate));
        candidatePE.setNetwork(0);
        candidatePE.setPriority(candidate.getPriority());

        // Advertise 'tcp' candidates for which SSL is enabled as 'ssltcp'
        // (although internally their transport protocol remains "tcp")
        Transport transport = candidate.getTransport();
        if (transport == Transport.TCP
                && candidate.isSSL())
        {
            transport = Transport.SSLTCP;
        }
        candidatePE.setProtocol(transport.toString());

        if (transport == Transport.TCP || transport == Transport.SSLTCP)
        {
            candidatePE.setTcpType(candidate.getTcpType());
        }

        candidatePE.setType(
                CandidateType.valueOf(candidate.getType().toString()));

        TransportAddress transportAddress = candidate.getTransportAddress();

        candidatePE.setIP(transportAddress.getHostAddress());
        candidatePE.setPort(transportAddress.getPort());

        TransportAddress relatedAddress = candidate.getRelatedAddress();

        if (relatedAddress != null)
        {
            candidatePE.setRelAddr(relatedAddress.getHostAddress());
            candidatePE.setRelPort(relatedAddress.getPort());
        }

        pe.addChildExtension(candidatePE);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>IceUdpTransportPacketExtension</tt> to the values of the
     * respective properties of {@link #dtlsControl}
     *
     * @param transportPE the <tt>IceUdpTransportPacketExtension</tt> on which
     * to set the values of the properties of <tt>dtlsControl</tt>
     */
    private void describeDtlsControl(IceUdpTransportPacketExtension transportPE)
    {
        String fingerprint = dtlsControl.getLocalFingerprint();
        String hash = dtlsControl.getLocalFingerprintHashFunction();

        DtlsFingerprintPacketExtension fingerprintPE
                = transportPE.getFirstChildOfType(
                DtlsFingerprintPacketExtension.class);

        if (fingerprintPE == null)
        {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            transportPE.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(fingerprint);
        fingerprintPE.setHash(hash);
    }

    /**
     * Sets up {@link #dtlsControl} according to <tt>transport</tt>,
     * adds all (supported) remote candidates from <tt>transport</tt>
     * to {@link #iceAgent} and starts {@link #iceAgent} if it isn't already
     * started.
     */
    private synchronized void doStartConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
        if (transport.isRtcpMux())
        {
            rtcpmux = true;
            if (channelForDtls != null && channelForDtls instanceof RtpChannel)
            {
                ((RtpChannel) channelForDtls)
                        .getDatagramFilter(true).setAcceptNonRtp(false);
            }
        }

        List<DtlsFingerprintPacketExtension> dfpes
            = transport.getChildExtensionsOfType(
                    DtlsFingerprintPacketExtension.class);

        if (!dfpes.isEmpty())
        {
            Map<String, String> remoteFingerprints
                = new LinkedHashMap<String, String>();

            for (DtlsFingerprintPacketExtension dfpe : dfpes)
            {
                remoteFingerprints.put(
                        dfpe.getHash(),
                        dfpe.getFingerprint());
            }
            dtlsControl.setRemoteFingerprints(remoteFingerprints);
        }

        IceProcessingState state = iceAgent.getState();
        if (IceProcessingState.COMPLETED.equals(state)
            || IceProcessingState.TERMINATED.equals(state))
        {
            // Adding candidates to a completed Agent is unnecessary and has
            // been observed to cause problems.
            return;
        }

        /*
         * If ICE is running already, we try to update the checklists with the
         * candidates. Note that this is a best effort.
         */
        boolean iceAgentStateIsRunning
            = IceProcessingState.RUNNING.equals(state);
        int remoteCandidateCount = 0;

        if (rtcpmux)
        {
            Component rtcpComponent = iceStream.getComponent(Component.RTCP);
            if (rtcpComponent != null)
                iceStream.removeComponent(rtcpComponent);
        }

        // Different stream may have different ufrag/password
        String ufrag = transport.getUfrag();

        if (ufrag != null)
            iceStream.setRemoteUfrag(ufrag);

        String password = transport.getPassword();

        if (password != null)
            iceStream.setRemotePassword(password);

        List<CandidatePacketExtension> candidates
                = transport.getChildExtensionsOfType(
                CandidatePacketExtension.class);

        if (iceAgentStateIsRunning && (candidates.size() == 0))
            return;

        // Sort the remote candidates (host < reflexive < relayed) in order
        // to create first the host, then the reflexive, the relayed
        // candidates and thus be able to set the relative-candidate
        // matching the rel-addr/rel-port attribute.
        Collections.sort(candidates);

        int generation = iceAgent.getGeneration();

        for (CandidatePacketExtension candidate : candidates)
        {
            /*
             * Is the remote candidate from the current generation of the
             * iceAgent?
             */
            if (candidate.getGeneration() != generation)
                continue;

            if (rtcpmux && Component.RTCP == candidate.getComponent())
            {
                logger.warn("Received an RTCP candidate, but we're using"
                                    + " rtcp-mux. Ignoring.");
                continue;
            }

            Component component
                    = iceStream.getComponent(candidate.getComponent());
            String relAddr;
            int relPort;
            TransportAddress relatedAddress = null;

            if (((relAddr = candidate.getRelAddr()) != null)
                    && ((relPort = candidate.getRelPort()) != -1))
            {
                relatedAddress
                        = new TransportAddress(
                        relAddr,
                        relPort,
                        Transport.parse(candidate.getProtocol()));
            }

            RemoteCandidate relatedCandidate
                    = component.findRemoteCandidate(relatedAddress);
            RemoteCandidate remoteCandidate
                    = new RemoteCandidate(
                    new TransportAddress(
                            candidate.getIP(),
                            candidate.getPort(),
                            Transport.parse(
                                    candidate.getProtocol())),
                    component,
                    org.ice4j.ice.CandidateType.parse(
                            candidate.getType().toString()),
                    candidate.getFoundation(),
                    candidate.getPriority(),
                    relatedCandidate);

            /*
             * XXX IceUdpTransportManager harvests host candidates only and
             * the ICE Components utilize the UDP protocol/transport only at
             * the time of this writing. The ice4j library will, of course,
             * check the theoretical reachability between the local and the
             * remote candidates. However, we would like (1) to not mess
             * with a possibly running iceAgent and (2) to return a
             * consistent return value.
             */
            if (!canReach(component, remoteCandidate))
                continue;

            if (iceAgentStateIsRunning)
                component.addUpdateRemoteCandidates(remoteCandidate);
            else
                component.addRemoteCandidate(remoteCandidate);
            remoteCandidateCount++;
        }

        if (iceAgentStateIsRunning)
        {
            if (remoteCandidateCount == 0)
            {
                /*
                 * XXX Effectively, the check above but realizing that all
                 * candidates were ignored: iceAgentStateIsRunning
                 * && (candidates.size() == 0).
                 */
                return;
            }
            else
            {
                // update all components of all streams
                for (IceMediaStream stream : iceAgent.getStreams())
                {
                    for (Component component : stream.getComponents())
                        component.updateRemoteCandidates();
                }
            }
        }
        else if (remoteCandidateCount != 0)
        {
            /*
             * Once again because the ICE Agent does not support adding
             * candidates after the connectivity establishment has been started
             * and because multiple transport-info JingleIQs may be used to send
             * the whole set of transport candidates from the remote peer to the
             * local peer, do not really start the connectivity establishment
             * until we have at least one remote candidate per ICE Component.
             */
            for (IceMediaStream stream : iceAgent.getStreams())
            {
                for (Component component : stream.getComponents())
                {
                    if (component.getRemoteCandidateCount() < 1)
                    {
                        remoteCandidateCount = 0;
                        break;
                    }
                }
                if (remoteCandidateCount == 0)
                    break;
            }
            if (remoteCandidateCount != 0)
                iceAgent.startConnectivityEstablishment();
        }
    }

    /**
     * Generates an ID to be set on a <tt>CandidatePacketExtension</tt> to
     * represent a specific <tt>LocalCandidate</tt>.
     *
     * @param candidate the <tt>LocalCandidate</tt> whose ID is to be generated
     * @return an ID to be set on a <tt>CandidatePacketExtension</tt> to
     * represent the specified <tt>candidate</tt>
     */
    private String generateCandidateID(LocalCandidate candidate)
    {
        StringBuilder candidateID = new StringBuilder();

        candidateID.append(conference.getID());
        candidateID.append(Long.toHexString(hashCode()));

        Agent iceAgent
            = candidate.getParentComponent().getParentStream().getParentAgent();

        candidateID.append(Long.toHexString(iceAgent.hashCode()));
        candidateID.append(Long.toHexString(iceAgent.getGeneration()));
        candidateID.append(Long.toHexString(candidate.hashCode()));

        return candidateID.toString();
    }

    /**
     * Gets the <tt>BundleContext</tt> associated with the <tt>Channel</tt>
     * that this {@link net.java.sip.communicator.service.protocol.media
     * .TransportManager} is servicing. The method is a
     * convenience which gets the <tt>BundleContext</tt> associated with the
     * XMPP component implementation in which the <tt>Videobridge</tt>
     * associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this
     * <tt>IceUdpTransportManager</tt>
     */
    public BundleContext getBundleContext()
    {
        return conference != null ? conference.getBundleContext() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DtlsControl getDtlsControl(Channel channel)
    {
        return dtlsControl;
    }

    /**
     * Gets the <tt>IceSocketWrapper</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     * @param component the <tt>Component</tt> from which to get a socket.
     * @return the <tt>IceSocketWrapper</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     */
    private IceSocketWrapper getSocketForComponent(Component component)
    {
        CandidatePair selectedPair = component.getSelectedPair();

        if (selectedPair != null)
        {
            return selectedPair.getIceSocketWrapper();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        if (!getChannels().contains(channel))
            return null;

        IceSocketWrapper[] iceSockets = getStreamConnectorSockets();
        IceSocketWrapper iceSocket0;

        if (iceSockets == null || (iceSocket0 = iceSockets[0]) == null)
            return null;

        if (channel instanceof SctpConnection)
        {
            DatagramSocket udpSocket = iceSocket0.getUDPSocket();

            if (udpSocket != null)
            {
                if (udpSocket instanceof MultiplexingDatagramSocket)
                {
                    MultiplexingDatagramSocket multiplexing
                        = (MultiplexingDatagramSocket) udpSocket;

                    try
                    {
                        DatagramSocket dtlsSocket
                            = multiplexing.getSocket(new DTLSDatagramFilter());

                        return new DefaultStreamConnector(dtlsSocket, null);
                    }
                    catch (IOException ioe)
                    {
                        logger.warn("Failed to create DTLS socket: " + ioe);
                    }
                }
            }
            else
            {
                Socket tcpSocket = iceSocket0.getTCPSocket();

                if (tcpSocket != null
                        && tcpSocket instanceof MultiplexingSocket)
                {
                    MultiplexingSocket multiplexing
                        = (MultiplexingSocket) tcpSocket;

                    try
                    {
                        Socket dtlsSocket
                            = multiplexing.getSocket(new DTLSDatagramFilter());

                        return new DefaultTCPStreamConnector(dtlsSocket, null);
                    }
                    catch(IOException ioe)
                    {
                        logger.warn("Failed to create DTLS socket: " + ioe);
                    }
                }
            }
            return null;
        }

        if (! (channel instanceof RtpChannel))
            return null;

        DatagramSocket udpSocket0;
        IceSocketWrapper iceSocket1 = iceSockets[1];
        RtpChannel rtpChannel = (RtpChannel) channel;

        if ((udpSocket0 = iceSocket0.getUDPSocket()) != null)
        {
            DatagramSocket udpSocket1
                = (iceSocket1 == null) ? null : iceSocket1.getUDPSocket();

            return
                getUDPStreamConnector(
                        rtpChannel,
                        new DatagramSocket[] { udpSocket0, udpSocket1 });
        }
        else
        {
            Socket tcpSocket0 = iceSocket0.getTCPSocket();
            Socket tcpSocket1
                = (iceSocket1 == null) ? null : iceSocket1.getTCPSocket();

            return
                getTCPStreamConnector(
                        rtpChannel,
                        new Socket[] { tcpSocket0, tcpSocket1 });
        }
    }

    /**
     * Gets the <tt>IceSocketWrapper</tt>s from the selected
     * <tt>CandidatePair</tt>(s) of the ICE agent.
     * TODO cache them in this instance?
     * @return the <tt>IceSocketWrapper</tt>s from the selected
     * <tt>CandidatePair</tt>(s) of the ICE agent.
     */
    private IceSocketWrapper[] getStreamConnectorSockets()
    {
        IceSocketWrapper[] streamConnectorSockets
                = new IceSocketWrapper[2];

        Component rtpComponent = iceStream.getComponent(Component.RTP);
        if (rtpComponent != null)
        {
            streamConnectorSockets[0 /* RTP */]
                = getSocketForComponent(rtpComponent);
        }

        if (numComponents > 1 && !rtcpmux)
        {
            Component rtcpComponent = iceStream.getComponent(Component.RTCP);
            if (rtcpComponent != null)
            {
                streamConnectorSockets[1 /* RTCP */]
                   = getSocketForComponent(rtcpComponent);
            }
        }
        return streamConnectorSockets;
    }

    private MediaStreamTarget getStreamTarget()
    {
        MediaStreamTarget streamTarget = null;
        InetSocketAddress[] streamTargetAddresses
                = new InetSocketAddress[2];
        int streamTargetAddressCount = 0;

        Component rtpComponent = iceStream.getComponent(Component.RTP);
        if (rtpComponent != null)
        {
            CandidatePair selectedPair = rtpComponent.getSelectedPair();

            if (selectedPair != null)
            {
                InetSocketAddress streamTargetAddress
                        = selectedPair
                        .getRemoteCandidate()
                        .getTransportAddress();

                if (streamTargetAddress != null)
                {
                    streamTargetAddresses[0] = streamTargetAddress;
                    streamTargetAddressCount++;
                }
            }
        }

        if (numComponents > 1 && !rtcpmux)
        {
            Component rtcpComponent = iceStream.getComponent(Component.RTCP);
            if (rtcpComponent != null)
            {
                CandidatePair selectedPair = rtcpComponent.getSelectedPair();

                if (selectedPair != null)
                {
                    InetSocketAddress streamTargetAddress
                            = selectedPair
                            .getRemoteCandidate()
                            .getTransportAddress();

                    if (streamTargetAddress != null)
                    {
                        streamTargetAddresses[1] = streamTargetAddress;
                        streamTargetAddressCount++;
                    }
                }
            }
        }

        if (rtcpmux)
        {
            streamTargetAddresses[1] = streamTargetAddresses[0];
            streamTargetAddressCount++;
        }

        if (streamTargetAddressCount > 0)
        {
            streamTarget
                    = new MediaStreamTarget(
                        streamTargetAddresses[0 /* RTP */],
                        streamTargetAddresses[1 /* RTCP */]);
        }
        return streamTarget;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTarget getStreamTarget(Channel channel)
    {
        return getStreamTarget();
    }

    /**
     * Creates and returns a TCP <tt>StreamConnector</tt> to be used by a
     * specific <tt>RtpChannel</tt>, using <tt>iceSockets</tt> as the
     * underlying <tt>Socket</tt>s.
     *
     * Does not use <tt>iceSockets</tt> directly, but creates
     * <tt>MultiplexedSocket</tt> instances on top of them.
     *
     * @param rtpChannel the <tt>RtpChannel</tt> which is to use the created
     * <tt>StreamConnector</tt>.
     * @param iceSockets the <tt>Socket</tt>s which are to be used by the
     * created <tt>StreamConnector</tt>.
     * @return a TCP <tt>StreamConnector</tt> with the <tt>Socket</tt>s
     * given in <tt>iceSockets</tt> to be used by a specific
     * <tt>RtpChannel</tt>.
     */
    private StreamConnector getTCPStreamConnector(RtpChannel rtpChannel,
                                                  Socket[] iceSockets)
    {
        StreamConnector connector = null;

        if (iceSockets != null)
        {
            Socket iceSocket0 = iceSockets[0];
            Socket channelSocket0 = null;

            if (iceSocket0 != null && iceSocket0 instanceof MultiplexingSocket)
            {
                MultiplexingSocket multiplexing
                    = (MultiplexingSocket) iceSocket0;

                try
                {
                    channelSocket0
                        = multiplexing.getSocket(
                                rtpChannel.getDatagramFilter(false /* RTP */));
                }
                catch (SocketException se) // never thrown
                {}
            }

            Socket iceSocket1 = rtcpmux ? iceSocket0 : iceSockets[1];
            Socket channelSocket1 = null;

            if (iceSocket1 != null && iceSocket1 instanceof MultiplexingSocket)
            {
                MultiplexingSocket multiplexing
                    = (MultiplexingSocket) iceSocket1;

                try
                {
                    channelSocket1
                        = multiplexing.getSocket(
                                rtpChannel.getDatagramFilter(true /* RTCP */));
                }
                catch (SocketException se) // never thrown
                {}
            }

            if (channelSocket0 != null || channelSocket1 != null)
            {
                connector
                    = new DefaultTCPStreamConnector(
                            channelSocket0,
                            channelSocket1,
                            rtcpmux);
            }
        }

        return connector;
    }

    /**
     * Creates and returns a UDP <tt>StreamConnector</tt> to be used by a
     * specific <tt>RtpChannel</tt>, using <tt>iceSockets</tt> as the
     * underlying <tt>DatagramSocket</tt>s.
     *
     * Does not use <tt>iceSockets</tt> directly, but creates
     * <tt>MultiplexedDatagramSocket</tt> instances on top of them.
     *
     * @param rtpChannel the <tt>RtpChannel</tt> which is to use the created
     * <tt>StreamConnector</tt>.
     * @param iceSockets the <tt>DatagramSocket</tt>s which are to be used by the
     * created <tt>StreamConnector</tt>.
     * @return a UDP <tt>StreamConnector</tt> with the <tt>DatagramSocket</tt>s
     * given in <tt>iceSockets</tt> to be used by a specific
     * <tt>RtpChannel</tt>.
     */
    private StreamConnector getUDPStreamConnector(RtpChannel rtpChannel,
                                                  DatagramSocket[] iceSockets)
    {
        StreamConnector connector = null;

        if (iceSockets != null)
        {
            DatagramSocket iceSocket0 = iceSockets[0];
            DatagramSocket channelSocket0 = null;

            if (iceSocket0 != null
                    && iceSocket0 instanceof MultiplexingDatagramSocket)
            {
                MultiplexingDatagramSocket multiplexing
                    = (MultiplexingDatagramSocket) iceSocket0;

                try
                {
                    channelSocket0
                        = multiplexing.getSocket(
                                rtpChannel.getDatagramFilter(false /* RTP */));
                }
                catch (SocketException se) // never thrown
                {}
            }

            DatagramSocket iceSocket1 = rtcpmux ? iceSocket0 : iceSockets[1];
            DatagramSocket channelSocket1 = null;

            if (iceSocket1 != null
                    && iceSocket1 instanceof MultiplexingDatagramSocket)
            {
                MultiplexingDatagramSocket multiplexing
                    = (MultiplexingDatagramSocket) iceSocket1;

                try
                {
                    channelSocket1
                        = multiplexing.getSocket(
                                rtpChannel.getDatagramFilter(true /* RTCP */));
                }
                catch (SocketException se) // never thrown
                {}
            }

            if (channelSocket0 != null || channelSocket1 != null)
            {
                connector
                    = new DefaultStreamConnector(
                            channelSocket0,
                            channelSocket1,
                            rtcpmux);
            }
        }

        return connector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    /**
     * Notifies this instance about a change of the value of the <tt>state</tt>
     * property of {@link #iceAgent}.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the old and new
     * values of the <tt>state</tt> property of {@link #iceAgent}.
     */
    private void iceAgentStateChange(PropertyChangeEvent ev)
    {
        /*
         * Log the changes in the ICE processing state of this
         * IceUdpTransportManager for the purposes of debugging.
         */

        boolean interrupted = false;

        try
        {
            StringBuilder s = new StringBuilder("ICE processing state of ")
                .append(getClass().getSimpleName()).append(" #")
                .append(Integer.toHexString(hashCode()))
                .append(" (for channels");
            for (Channel channel : getChannels())
                s.append(" ").append(channel.getID());
            s.append(")  of conference ").append(conference.getID())
                .append(" changed from ").append(ev.getOldValue())
                 .append(" to ").append(ev.getNewValue()).append(".");

            logd(s.toString());
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                interrupted = true;
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }
        finally
        {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Notifies this instance about a change of the value of a property of a
     * <tt>CandidatePair</tt> of {@link #iceStream}.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the
     * <tt>CandidatePair</tt>, the name of the <tt>CandidatePair</tt> property,
     * and its old and new values
     */
    private void iceStreamPairChange(PropertyChangeEvent ev)
    {
        if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED.equals(
                ev.getPropertyName()))
        {
            // TODO we might not necessarily want to keep all channels alive by
            // the ICE connection.
            for (Channel channel : getChannels())
                channel.touch();
        }
    }

    /**
     * Initializes {@link #tcpHostHarvester}.
     */
    private void initializeTcpHarvester()
    {
        synchronized (IceUdpTransportManager.class)
        {
            if (tcpHostHarvesterInitialized)
                return;
            tcpHostHarvesterInitialized = true;

            ConfigurationService cfg =
                    conference.getVideobridge().getConfigurationService();

            boolean fallback = false;
            if (!cfg.getBoolean(DISABLE_TCP_HARVESTER, false))
            {
                boolean ssltcp = cfg.getBoolean(TCP_HARVESTER_SSLTCP,
                                                TCP_HARVESTER_SSLTCP_DEFAULT);

                int port = cfg.getInt(TCP_HARVESTER_PORT, -1);
                if (port == -1)
                {
                    fallback = true;
                    port = TCP_DEFAULT_PORT;
                }

                try
                {
                    tcpHostHarvester
                            = new MultiplexingTcpHostHarvester(port, ssltcp);
                }
                catch (IOException ioe)
                {
                    logger.warn("Failed to initialize TCP harvester on port "
                                + port + ": " + ioe
                                + (fallback
                                    ? ". Retrying on port " + TCP_FALLBACK_PORT
                                    : "")
                                + ".");
                }

                if (tcpHostHarvester == null && fallback)
                {
                    port = TCP_FALLBACK_PORT;
                    try
                    {
                        tcpHostHarvester
                            = new MultiplexingTcpHostHarvester(
                                port,
                                ssltcp);

                    }
                    catch (IOException e)
                    {
                        logger.warn("Failed to initialize TCP harvester on "
                                    + "fallback port " + port + ": " + e);
                        return;
                    }
                }

                if (logger.isInfoEnabled())
                {
                    logger.info("Initialized TCP harvester on port " + port
                                        + ", using SSLTCP:" + ssltcp);
                }

                String localAddressStr
                        = cfg.getString(NAT_HARVESTER_LOCAL_ADDRESS);
                String publicAddressStr
                        = cfg.getString(NAT_HARVESTER_PUBLIC_ADDRESS);
                if (localAddressStr != null && publicAddressStr != null)
                {
                    try
                    {
                        tcpHostHarvester.addMappedAddress(
                                InetAddress.getByName(publicAddressStr),
                                InetAddress.getByName(localAddressStr));
                    }
                    catch (UnknownHostException uhe)
                    {
                        logger.warn("Failed to add mapped address for "
                            + publicAddressStr + " -> " + localAddressStr
                            + ": " + uhe);
                    }
                }

                int mappedPort = cfg.getInt(TCP_HARVESTER_MAPPED_PORT, -1);
                if (mappedPort != -1)
                {
                    tcpHostHarvesterMappedPort = mappedPort;
                    tcpHostHarvester.addMappedPort(mappedPort);
                }

                if (AwsCandidateHarvester.smellsLikeAnEC2())
                {
                    TransportAddress localAddress
                            = AwsCandidateHarvester.getFace();
                    TransportAddress publicAddress
                            = AwsCandidateHarvester.getMask();

                    if (localAddress != null && publicAddress != null)
                    {
                        tcpHostHarvester.addMappedAddress(
                                publicAddress.getAddress(),
                                localAddress.getAddress());
                    }
                }
            }
        }
    }

    /**
     * Notifies all channels of this <tt>TransportManager</tt> that connectivity
     * has been established (and they can now obtain valid values through
     * {@link #getStreamConnector(Channel)} and
     * {@link #getStreamTarget(Channel)}.
     */
    private void onIceConnected()
    {
        iceConnected = true;

        for (Channel channel : getChannels())
        {
            channel.transportConnected();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
        doStartConnectivityEstablishment(transport);

        synchronized (connectThreadSyncRoot)
        {
            if (connectThread == null)
            {
                connectThread = new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            wrapupConnectivityEstablishment();
                        }
                        catch (OperationFailedException ofe)
                        {
                            logd("Failed to connect IceUdpTransportManager: "
                                         + ofe);

                            synchronized (connectThreadSyncRoot)
                            {
                                connectThread = null;
                                return;
                            }
                        }

                        IceProcessingState state = iceAgent.getState();
                        if (IceProcessingState.COMPLETED.equals(state)
                            || IceProcessingState.TERMINATED.equals(state))
                        {
                            startDtls();
                            onIceConnected();
                        }
                        else
                        {
                            logger.warn("Failed to establish ICE connectivity,"
                                        + " state: " + state);
                        }
                    }
                };

                connectThread.setDaemon(true);
                connectThread.setName("IceUdpTransportManager connect thread");
                connectThread.start();
            }
        }
    }

    /**
     * Sets up {@link #dtlsControl} with a proper target (the remote sockets
     * from the pair(s) selected by the ICE agent) and starts it.
     */
    private void startDtls()
    {
        dtlsControl.setSetup(
                isControlling
                    ? DtlsControl.Setup.PASSIVE
                    : DtlsControl.Setup.ACTIVE);
        dtlsControl.setRtcpmux(rtcpmux);

        // Setup the connector
        IceSocketWrapper[] iceSockets = getStreamConnectorSockets();
        if (iceSockets == null || iceSockets[0] == null)
        {
            logd("Cannot start DTLS, no sockets from ICE.");
            return;
        }

        StreamConnector streamConnector;
        AbstractRTPConnector rtpConnector;

        DatagramSocket udpSocket = iceSockets[0].getUDPSocket();
        if (udpSocket != null)
        {
            streamConnector
                    = new DefaultStreamConnector(
                        udpSocket,
                        iceSockets[1] == null ? null : iceSockets[1].getUDPSocket(),
                        rtcpmux);
            rtpConnector = new RTPConnectorUDPImpl(streamConnector);
        }
        else
        {
            //Assume/try TCP
            streamConnector
                = new DefaultTCPStreamConnector(
                        iceSockets[0].getTCPSocket(),
                        iceSockets[1] == null ? null : iceSockets[1].getTCPSocket(),
                        rtcpmux);
            rtpConnector = new RTPConnectorTCPImpl(streamConnector);
        }

        MediaStreamTarget target = getStreamTarget();
        if (target != null)
        {
            try
            {
                rtpConnector.addTarget(
                    new SessionAddress(target.getDataAddress().getAddress(),
                                       target.getDataAddress().getPort()));
            }
            catch (IOException ioe)
            {
                logger.warn("Failed to add target to DTLS connector: " + ioe);
                // But do go on, because it is not necessary in all cases
            }
        }

        dtlsControl.setConnector(rtpConnector);
        dtlsControl.registerUser(this);

        // For DTLS, the media type doesn't matter (as long as it's not
        // null).
        dtlsControl.start(MediaType.AUDIO);
    }

    /**
     * Updates the states of the <tt>RtpChannelDatagramFilters</tt> for our
     * RTP channels, according to whether we are (effectively) using bundle
     * (that is, we have more than one <tt>RtpChannel</tt>). The filters are
     * configured to check the RTP payload type and the RTCP Sender SSRC fields
     * if and only if bundle is used.
     *
     * This allows non-bundled channels to work correctly without the need for
     * the focus to explicitly specify via COLIBRI the payload types to be used
     * by each channel.
     */
    private void updatePayloadTypeFilters()
    {
        List<RtpChannel> rtpChannels = new LinkedList<RtpChannel>();
        for (Channel channel : getChannels())
            if (channel instanceof RtpChannel)
                rtpChannels.add((RtpChannel) channel);

        int numRtpChannels = rtpChannels.size();
        if (numRtpChannels > 0)
        {
            boolean bundle = numRtpChannels > 1;
            for (RtpChannel channel : rtpChannels)
            {
                // Enable filtering by PT iff we are (effectively) using bundle
                channel.getDatagramFilter(false).setCheckRtpPayloadType(bundle);
                // Enable RTCP filtering by SSRC iff we are bundle
                channel.getDatagramFilter(true).setCheckRtcpSsrc(bundle);
            }
        }
    }

    /**
     * Waits until {@link #iceAgent} exits the RUNNING or WAITING state.
     */
    private void wrapupConnectivityEstablishment()
        throws OperationFailedException
    {
        final Object syncRoot = new Object();
        PropertyChangeListener propertyChangeListener
            = new PropertyChangeListener()
            {
                @Override
                public void propertyChange(PropertyChangeEvent ev)
                {
                    Object newValue = ev.getNewValue();

                    if (IceProcessingState.COMPLETED.equals(newValue)
                            || IceProcessingState.FAILED.equals(newValue)
                            || IceProcessingState.TERMINATED.equals(newValue))
                    {
                        Agent iceAgent = (Agent) ev.getSource();

                        iceAgent.removeStateChangeListener(this);
                        if (iceAgent == IceUdpTransportManager.this.iceAgent)
                        {
                            synchronized (syncRoot)
                            {
                                syncRoot.notify();
                            }
                        }
                    }
                }
            };

        iceAgent.addStateChangeListener(propertyChangeListener);

        // Wait for the connectivity checks to finish if they have been started.
        boolean interrupted = false;

        synchronized (syncRoot)
        {
            Agent iceAgent = this.iceAgent;
            IceProcessingState state
                = iceAgent == null ? null : iceAgent.getState();

            while (IceProcessingState.RUNNING.equals(state)
                    || IceProcessingState.WAITING.equals(state))
            {
                try
                {
                    syncRoot.wait(1000);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
                finally
                {
                    iceAgent = this.iceAgent;
                    state = iceAgent == null ? null : iceAgent.getState();
                }
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        /*
         * Make sure stateChangeListener is removed from iceAgent in case its
         * #propertyChange(PropertyChangeEvent) has never been executed.
         */
        iceAgent.removeStateChangeListener(propertyChangeListener);

        // Check the state of ICE processing and throw an exception if failed.
        if (IceProcessingState.FAILED.equals(iceAgent.getState()))
        {
            throw new OperationFailedException(
                    "Could not establish connection (ICE failed)",
                    OperationFailedException.GENERAL_ERROR);
        }
    }

    /**
     * Extends
     * <tt>net.java.sip.communicator.service.protocol.media.TransportManager</tt>
     * in order to get access to certain protected static methods and thus avoid
     * duplicating their implementations here.
     *
     * @author Lyubomir Marinov
     */
    private static class JitsiTransportManager
        extends
            net.java.sip.communicator.service.protocol.media.TransportManager
                <MediaAwareCallPeer<?,?,?>>
    {
        public static PortTracker getPortTracker(MediaType mediaType)
        {
            return
                net.java.sip.communicator.service.protocol.media.TransportManager
                    .getPortTracker(mediaType);
        }

        public static void initializePortNumbers()
        {
            net.java.sip.communicator.service.protocol.media.TransportManager
                .initializePortNumbers();
        }

        private JitsiTransportManager(MediaAwareCallPeer<?,?,?> callPeer)
        {
            super(callPeer);
        }

        @Override
        public long getHarvestingTime(String arg0)
        {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public String getICECandidateExtendedType(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICELocalHostAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICELocalReflexiveAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICELocalRelayedAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICERemoteHostAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICERemoteReflexiveAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public InetSocketAddress getICERemoteRelayedAddress(String arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public String getICEState()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        protected InetAddress getIntendedDestination(
                MediaAwareCallPeer<?,?,?> arg0)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public int getNbHarvesting()
        {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public int getNbHarvesting(String arg0)
        {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public long getTotalHarvestingTime()
        {
            // TODO Auto-generated method stub
            return 0;
        }
    }
}
