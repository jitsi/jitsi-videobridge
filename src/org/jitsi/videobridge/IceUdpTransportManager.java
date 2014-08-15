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

import javax.media.rtp.*;

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
     * The <tt>Logger</tt> used by the <tt>IceUdpTransportManager</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(IceUdpTransportManager.class);

    /**
     * The indicator which determines whether the one-time method
     * {@link JitsiTransportManager#initializePortNumbers()} is to be executed.
     */
    private static boolean initializePortNumbers = true;

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be explicitly disabled.
     */
    private static final String DISABLE_AWS_HARVESTER
            = "org.jitsi.videobridge.DISABLE_AWS_HARVESTER";

    /**
     * Contains the name of the property flag that may indicate that AWS address
     * harvesting should be forced without first trying to auto detect it.
     */
    private static final String FORCE_AWS_HARVESTER
            = "org.jitsi.videobridge.FORCE_AWS_HARVESTER";

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
     * The name default of the single <tt>IceStream</tt> that this
     * <tt>TransportManager</tt> will create/use.
     */
    private static final String DEFAULT_ICE_STREAM_NAME = "stream";

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
     * Whether we're using rtcp-mux or not.
     */
    private boolean rtcpmux = false;

    /**
     * The number of {@link org.ice4j.ice.Component}-s to create in
     * {@link #iceStream}.
     */
    private int numComponents;

    /**
     * The <tt>Conference</tt> object that this <tt>TransportManager</tt> is
     * associated with.
     */
    private final Conference conference;

    /**
     * The <tt>DtlsControl</tt> that this <tt>TransportManager</tt> uses.
     */
    private final DtlsControlImpl dtlsControl;

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
     * Whether this <tt>IceUdpTransportManager</tt> will serve as the the
     * controlling or controlled ICE agent.
     */
    private final boolean isControlling;

    /**
     * Whether ICE connectivity has been established.
     */
    private boolean iceConnected = false;

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
     * The single (if any) <tt>Channel</tt> instance, whose sockets are
     * currently configured to accept DTLS packets.
     */
    private Channel channelForDtls = null;

    /**
     * Whether this <tt>TransportManager</tt> has been closed.
     */
    private boolean closed = false;

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
        this.isControlling = isControlling;

        /*
         * XXX This is pretty much a hack. We only use numComponents=1 for
         * non-bundle data channels, in which case it is unknown if enabling
         * SRTP extensions in DTLS will work or not. Remove once we're sure it
         * works.
         */
        this.dtlsControl = new DtlsControlImpl(numComponents == 1);

        iceAgent = createIceAgent(isControlling, iceStreamName);
        iceAgent.addStateChangeListener(iceAgentStateChangeListener);
        iceStream = iceAgent.getStream(iceStreamName);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);
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
     *
     * Assures that no more than one <tt>SctpConnection</tt> is added.Keeps
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

        boolean added = super.addChannel(channel);
        if (!added)
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
                RtpChannel rtpChannelForDtls
                    = (RtpChannel) channelForDtls;

                rtpChannelForDtls.getDatagramFilter(false).setAcceptNonRtp(false);
                rtpChannelForDtls.getDatagramFilter(true).setAcceptNonRtp(false);
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

        if (iceConnected)
            channel.transportConnected();

        return true;
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
                dtlsControl.start(null);
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
                {
                    connectThread.interrupt();
                }
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

                    List<Channel> channels = getChannels();
                    for (Channel c : channels)
                        if (c instanceof RtpChannel)
                            newChannelForDtls = (RtpChannel) c;

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

            // FIXME We give SctpConnection the "raw" MultiplexingDatagramSocket
            // from ice4j, so we cannot close that, because it would affect
            // other channels it.
            if (!(channel instanceof SctpConnection))
            {
                try
                {
                    StreamConnector connector = channel.getStreamConnector();
                    if (connector != null)
                    {
                        DatagramSocket rtpSocket = connector.getDataSocket();
                        if (rtpSocket != null)
                            rtpSocket.close();

                        DatagramSocket rtcpSocket = connector.getControlSocket();

                        if (rtcpSocket != null)
                            rtcpSocket.close();
                    }
                }
                catch (IOException ioe)
                {
                    logd("Failed to close sockets when closing a channel:" +
                                 ioe);
                }
            }

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

        // createIceStream

        /*
         * Read the ranges of ports to be utilized from the ConfigurationService
         * once.
         */
        synchronized (IceUdpTransportManager.class)
        {
            if (initializePortNumbers)
            {
                initializePortNumbers = false;
                JitsiTransportManager.initializePortNumbers();
            }
        }

        // TODO: Use the default tracker somehow?
        PortTracker portTracker
                = JitsiTransportManager.getPortTracker(MediaType.AUDIO);

        IceMediaStream iceStream
                = nams.createIceStream(
                numComponents,
                portTracker.getPort(),
                iceStreamName,
                iceAgent);

        // Attempt to minimize subsequent bind retries.
        try
        {
            portTracker.setNextPort(
                    1 + iceStream.getComponent(Component.RTCP)
                            .getLocalCandidates()
                            .get(0).getTransportAddress().getPort());
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                Thread.currentThread().interrupt();
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }

        return iceAgent;
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
    protected void describe(IceUdpTransportPacketExtension pe)
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
                    describe(candidate, pe);
            }
        }

        describeDtlsControl(pe);
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
     * {@inheritDoc}
     */
    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        if (!getChannels().contains(channel))
            return null;

        if (channel instanceof SctpConnection)
        {
            // We give the SctpConnection the real ICE socket, because it
            // needs to read DTLS packets.
            // TODO: give it a Multiplexed socket for DTLS only
            DatagramSocket[] iceSockets = getStreamConnectorSockets();
            if (iceSockets == null || iceSockets[0] == null)
                return null;
            return new DefaultStreamConnector(iceSockets[0], null);
        }

        if (! (channel instanceof RtpChannel))
            return null;
        RtpChannel rtpChannel = (RtpChannel) channel;

        StreamConnector connector = null;
        DatagramSocket[] iceSockets = getStreamConnectorSockets();
        DatagramSocket[] channelSockets = new DatagramSocket[2];
        if (iceSockets != null)
        {
            if (iceSockets[0] instanceof MultiplexingDatagramSocket)
            {
                MultiplexingDatagramSocket multiplexing
                    = (MultiplexingDatagramSocket) iceSockets[0];
                try
                {
                    channelSockets[0]
                            = multiplexing.getSocket(
                            rtpChannel.getDatagramFilter(false));
                }
                catch (SocketException se) //never thrown
                {}
            }

            DatagramSocket iceRtcpSocket = rtcpmux
                    ? iceSockets[0]
                    : iceSockets[1];
            if (iceRtcpSocket instanceof MultiplexingDatagramSocket)
            {
                MultiplexingDatagramSocket multiplexing
                        = (MultiplexingDatagramSocket) iceRtcpSocket;
                try
                {
                    channelSockets[1]
                            = multiplexing.getSocket(
                            rtpChannel.getDatagramFilter(true));
                }
                catch (SocketException se) //never thrown
                {}
            }

            if (channelSockets[0] != null
                    || channelSockets[1] != null)
            {
                connector
                    = new DefaultStreamConnector(channelSockets[0],
                                                 channelSockets[1],
                                                 rtcpmux);
            }
        }

        return connector;
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
     * {@inheritDoc}
     */
    @Override
    public DtlsControl getDtlsControl(Channel channel)
    {
        return dtlsControl;
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
        candidatePE.setProtocol(candidate.getTransport().toString());
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

    private DatagramSocket[] getStreamConnectorSockets()
    {
        DatagramSocket[] streamConnectorSockets
                = new DatagramSocket[2];

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

    /**
     * Gets the <tt>DatagramSocket</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     * @param component the <tt>Component</tt> from which to get a socket.
     * @return the <tt>DatagramSocket</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     */
    private DatagramSocket getSocketForComponent(Component component)
    {
        CandidatePair selectedPair = component.getSelectedPair();

        if (selectedPair != null)
        {
            return selectedPair.getLocalCandidate().getDatagramSocket();
        }
        return null;
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
     * Sets up {@link #dtlsControl} according to <tt>transport</tt>,
     * adds all (supported) remote candidates from <tt>transport</tt>
     * to {@link #iceAgent} and starts {@link #iceAgent} if it isn't already
     * started.
     */
    private synchronized boolean doStartConnectivityEstablishment(
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

        /*
         * If ICE is running already, we try to update the checklists with the
         * candidates. Note that this is a best effort.
         */
        boolean iceAgentStateIsRunning
                = IceProcessingState.RUNNING.equals(iceAgent.getState());
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
            return false;

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
                return false;
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

        return iceAgentStateIsRunning || (remoteCandidateCount != 0);
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
                        if (IceProcessingState.COMPLETED.equals(state))
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
        DatagramSocket[] datagramSockets = getStreamConnectorSockets();
        if (datagramSockets == null || datagramSockets[0] == null)
        {
            logd("Cannot start DTLS, no sockets from ICE.");
            return;
        }

        DefaultStreamConnector streamConnector
                = new DefaultStreamConnector(
                datagramSockets[0],
                datagramSockets[1],
                rtcpmux);
        RTPConnectorUDPImpl rtpConnector
                = new RTPConnectorUDPImpl(streamConnector);

        MediaStreamTarget streamTarget = getStreamTarget();
        SessionAddress target;
        if (datagramSockets[1] == null)
        {
            target = new SessionAddress(
                    streamTarget.getDataAddress().getAddress(),
                    streamTarget.getDataAddress().getPort());
        }
        else
        {
            target = new SessionAddress(
                    streamTarget.getDataAddress().getAddress(),
                    streamTarget.getDataAddress().getPort(),
                    streamTarget.getControlAddress().getAddress(),
                    streamTarget.getControlAddress().getPort());
        }

        try
        {
            rtpConnector.addTarget(target);
        }
        catch (IOException ioe)
        {
            logd("Failed to add target to RTP Connector: " + ioe);
            return;
        }

        dtlsControl.setConnector(rtpConnector);
        dtlsControl.registerUser(this);

        // For DTLS, the media type doesn't matter (as long as it's not
        // null).
        dtlsControl.start(MediaType.AUDIO);
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

