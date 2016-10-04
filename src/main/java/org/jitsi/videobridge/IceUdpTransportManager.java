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

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
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
     * The name of the property which disables the use of a
     * <tt>TcpHarvester</tt>.
     */
    private static final String DISABLE_TCP_HARVESTER
        = "org.jitsi.videobridge.DISABLE_TCP_HARVESTER";

    /**
     * The name of the property which controls the port number used for
     * <tt>SinglePortUdpHarvester</tt>s.
     */
    private static final String SINGLE_PORT_HARVESTER_PORT
            = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT";

    /**
     * The default value of the port to be used for
     * {@code SinglePortUdpHarvester}.
     */
    private static final int SINGLE_PORT_DEFAULT_VALUE = 10000;

    /**
     * The {@link Logger} used by the {@link IceUdpTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(IceUdpTransportManager.class);

    /**
     * The default port that the <tt>TcpHarvester</tt> will
     * bind to.
     */
    private static final int TCP_DEFAULT_PORT = 443;

    /**
     * The port on which the <tt>TcpHarvester</tt> will bind to
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
     * <tt>TcpHarvester</tt> will bind.
     */
    private static final String TCP_HARVESTER_PORT
        = "org.jitsi.videobridge.TCP_HARVESTER_PORT";

    /**
     * The name of the property which controls the use of ssltcp candidates by
     * <tt>TcpHarvester</tt>.
     */
    private static final String TCP_HARVESTER_SSLTCP
        = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP";

    /**
     * The name of the property that can be used to control the value of
     * {@link #ICE_UFRAG_PREFIX}.
     */
    private static final String ICE_UFRAG_PREFIX_PNAME
        = "org.jitsi.videobridge.ICE_UFRAG_PREFIX";

    /**
     * The optional prefix to use for generated ICE local username fragments.
     */
    private static String ICE_UFRAG_PREFIX;

    /**
     * The default value of the <tt>TCP_HARVESTER_SSLTCP</tt> property.
     */
    private static final boolean TCP_HARVESTER_SSLTCP_DEFAULT = true;

    /**
     * The single <tt>TcpHarvester</tt> instance for the
     * application.
     */
    private static TcpHarvester tcpHostHarvester = null;

    /**
     * The <tt>SinglePortUdpHarvester</tt>s which will be appended to ICE
     * <tt>Agent</tt>s managed by <tt>IceUdpTransportManager</tt> instances.
     */
    private static List<SinglePortUdpHarvester> singlePortHarvesters = null;

    /**
     * The flag which indicates whether application-wide harvesters, stored
     * in the static fields {@link #tcpHostHarvester} and
     * {@link #singlePortHarvesters} have been initialized.
     */
    private static boolean staticConfigurationInitialized = false;

    /**
     * The "mapped port" added to {@link #tcpHostHarvester}, or -1.
     */
    private static int tcpHostHarvesterMappedPort = -1;

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
    private final boolean controlling;

    /**
     * The number of {@link org.ice4j.ice.Component}-s to create in
     * {@link #iceStream}.
     */
    private int numComponents;

    /**
     * Whether we're using rtcp-mux or not.
     */
    private boolean rtcpmux;

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
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param controlling {@code true} if the new instance is to serve as a
     * controlling ICE agent and passive DTLS endpoint; otherwise, {@code false}
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling)
        throws IOException
    {
        this(conference, controlling, 2, DEFAULT_ICE_STREAM_NAME);
    }

    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents)
        throws IOException
    {
        this(conference, controlling, numComponents, DEFAULT_ICE_STREAM_NAME);
    }

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param controlling {@code true} if the new instance is to serve as a
     * controlling ICE agent and passive DTLS endpoint; otherwise, {@code false}
     * @param numComponents the number of ICE components that this instance is
     * to start with.
     * @param iceStreamName the name of the ICE stream to be created by this
     * instance.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents,
                                  String iceStreamName)
        throws IOException
    {
        this.conference = conference;
        this.controlling = controlling;
        this.numComponents = numComponents;
        this.rtcpmux = numComponents == 1;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());

        dtlsControl = createDtlsControl();

        iceAgent = createIceAgent(controlling, iceStreamName, rtcpmux);
        iceAgent.addStateChangeListener(iceAgentStateChangeListener);
        iceStream = iceAgent.getStream(iceStreamName);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportCreated(this));
        }
    }

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param controlling {@code true} if the new instance is to serve as a
     * controlling ICE agent and passive DTLS endpoint; otherwise, {@code false}
     * @param iceStreamName the name of the ICE stream to be created by this
     * instance.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  String iceStreamName)
        throws IOException
    {
        this(conference, controlling, 2, iceStreamName);
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
            logger.info(
                "Not adding a second SctpConnection to TransportManager.");
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
                // channelForDtls is necessarily an RtpChannel, because we don't
                // add more than one SctpConnection. The SctpConnection socket
                // will automatically accept DTLS.
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

        if (iceConnected)
            channel.transportConnected();

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportChannelAdded(channel));
        }

        return true;
    }

    private int addRemoteCandidates(
            List<CandidatePacketExtension> candidates,
            boolean iceAgentStateIsRunning)
    {
        // Sort the remote candidates (host < reflexive < relayed) in order to
        // create first the host, then the reflexive, the relayed candidates and
        // thus be able to set the relative-candidate matching the
        // rel-addr/rel-port attribute.
        Collections.sort(candidates);

        int generation = iceAgent.getGeneration();
        int remoteCandidateCount = 0;

        for (CandidatePacketExtension candidate : candidates)
        {
            // Is the remote candidate from the current generation of the
            // iceAgent?
            if (candidate.getGeneration() != generation)
                continue;

            if (rtcpmux && Component.RTCP == candidate.getComponent())
            {
                logger.warn(
                        "Received an RTCP candidate, but we're using rtcp-mux."
                            + " Ignoring.");
                continue;
            }

            Component component
                = iceStream.getComponent(candidate.getComponent());
            String relAddr;
            int relPort;
            TransportAddress relatedAddress = null;

            if ((relAddr = candidate.getRelAddr()) != null
                    && (relPort = candidate.getRelPort()) != -1)
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
                                Transport.parse(candidate.getProtocol())),
                        component,
                        org.ice4j.ice.CandidateType.parse(
                                candidate.getType().toString()),
                        candidate.getFoundation(),
                        candidate.getPriority(),
                        relatedCandidate);

            // XXX IceUdpTransportManager harvests host candidates only and the
            // ICE Components utilize the UDP protocol/transport only at the
            // time of this writing. The ice4j library will, of course, check
            // the theoretical reachability between the local and the remote
            // candidates. However, we would like (1) to not mess with a
            // possibly running iceAgent and (2) to return a consistent return
            // value.
            if (!canReach(component, remoteCandidate))
                continue;

            if (iceAgentStateIsRunning)
                component.addUpdateRemoteCandidates(remoteCandidate);
            else
                component.addRemoteCandidate(remoteCandidate);
            remoteCandidateCount++;
        }

        return remoteCandidateCount;
    }

    /**
     * Adds to <tt>iceAgent</tt> videobridge specific candidate harvesters such
     * as an Amazon AWS EC2 specific harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     * @param rtcpmux whether rtcp will be used by this
     * <tt>IceUdpTransportManager</tt>.
     */
    private void appendVideobridgeHarvesters(Agent iceAgent,
                                             boolean rtcpmux)
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);
        boolean enableDynamicHostHarvester = true;

        if (rtcpmux)
        {
            // TODO CandidateHarvesters may take (non-trivial) time to
            // initialize so initialize them as soon as possible, don't wait to
            // initialize them after a Channel is requested.
            // XXX Unfortunately, TcpHarvester binds to specific local addresses
            // while Jetty binds to all/any local addresses and, consequently,
            // the order of the binding is important at the time of this
            // writing. That's why TcpHarvester is left to initialize as late as
            // possible right now.
            initializeStaticConfiguration(cfg);

            if (tcpHostHarvester != null)
                iceAgent.addCandidateHarvester(tcpHostHarvester);
            if (singlePortHarvesters != null)
            {
                for (CandidateHarvester harvester : singlePortHarvesters)
                {
                    iceAgent.addCandidateHarvester(harvester);
                    enableDynamicHostHarvester = false;
                }
            }
        }

        // Use dynamic ports iff we're not sing "single port".
        iceAgent.setUseHostHarvester(enableDynamicHostHarvester);

        //if no configuration is found then we simply log and bail
        if (cfg == null)
        {
            logger.info("No configuration found. "
                        + "Will continue without custom candidate harvesters");
            return;
        }

        HarvesterConfiguration addressesConfig
            = HarvesterConfiguration.getInstance(cfg);
        MappingCandidateHarvester mappingHarvester
            = addressesConfig.getCandidateHarvester();

        //append the mapping harvester
        if( mappingHarvester != null)
        {
            iceAgent.addCandidateHarvester(mappingHarvester);
        }

        if(addressesConfig.getPublicAddress() != null
            && addressesConfig.getLocalAddress() != null)
        {
            //if configured, append a mapping harvester.
            MappingCandidateHarvester natHarvester
                = new MappingCandidateHarvester(
                addressesConfig.getPublicAddress(),
                addressesConfig.getLocalAddress());

            iceAgent.addCandidateHarvester(natHarvester);
        }
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
     * TODO: In the case of multiple {@code Channel}s in one
     * {@code TransportManager} it is unclear how to handle changes to the
     * {@code initiator} property from individual channels.
     */
    @Override
    protected void channelPropertyChange(PropertyChangeEvent ev)
    {
        super.channelPropertyChange(ev);

        /*
        if (Channel.INITIATOR_PROPERTY.equals(ev.getPropertyName())
                && iceAgent != null)
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

//            DatagramSocket[] datagramSockets = getStreamConnectorSockets();

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
             * It seems that the ICE agent takes care of closing these.
             *
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
                logger.info(
                    "Failed to close sockets when closing a channel:" + ioe);
            }

            EventAdmin eventAdmin = conference.getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.sendEvent(
                        EventFactory.transportChannelRemoved(channel));
            }

            channel.transportClosed();
        }

        if (getChannels().isEmpty())
            close();

        return removed;
    }

    /**
     * Initializes a new {@code DtlsControlImpl} instance.
     *
     * @return a new {@code DtlsControlImpl} instance
     */
    private DtlsControlImpl createDtlsControl()
    {
        DtlsControlImpl dtlsControl
            = new DtlsControlImpl(/* srtpDisabled */ false);

        dtlsControl.registerUser(this);
        // (1) According to https://tools.ietf.org/html/rfc5245#section-5.2, in
        // the case of two full ICE agents: [t]he agent that generated the offer
        // which started the ICE processing MUST take the controlling role, and
        // the other MUST take the controlled role.
        // (2) According to https://tools.ietf.org/html/rfc5763#section-5: [t]he
        // endpoint that is the offerer MUST use the setup attribute value of
        // setup:actpass and be prepared to receive a client_hello before it
        // receives the answer. The answerer MUST use either a setup attribute
        // value of setup:active or setup:passive.
        dtlsControl.setSetup(
                controlling
                    ? DtlsControl.Setup.ACTPASS
                    : DtlsControl.Setup.ACTIVE);
        // XXX For DTLS, the media type doesn't matter (as long as it's not
        // null).
        // XXX The actual start of the DTLS servers/clients will be delayed
        // until an rtpConnector is set (when a MediaStream with this
        // SrtpControl starts or is assigned a target).
        dtlsControl.start(MediaType.AUDIO);
        return dtlsControl;
    }

    /**
     * Initializes a new <tt>Agent</tt> instance which implements the ICE
     * protocol and which is to be used by this instance to implement the Jingle
     * ICE-UDP transport.
     *
     * @param controlling
     * @param iceStreamName
     * @param rtcpmux
     * @return a new <tt>Agent</tt> instance which implements the ICE protocol
     * and which is to be used by this instance to implement the Jingle ICE-UDP
     * transport
     * @throws IOException if initializing a new <tt>Agent</tt> instance for the
     * purposes of this <tt>TransportManager</tt> fails
     */
    private Agent createIceAgent(boolean controlling,
                                 String iceStreamName,
                                 boolean rtcpmux)
            throws IOException
    {
        NetworkAddressManagerService nams
            = ServiceUtils.getService(
                    getBundleContext(),
                    NetworkAddressManagerService.class);
        Agent iceAgent = new Agent(logger.getLevel(), ICE_UFRAG_PREFIX);

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        appendVideobridgeHarvesters(iceAgent, rtcpmux);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);

        PortTracker portTracker = JitsiTransportManager.getPortTracker(null);
        int portBase = portTracker.getPort();

        IceMediaStream iceStream
            = nams.createIceStream(
                    numComponents,
                    portBase,
                    iceStreamName,
                    iceAgent);

        // Attempt to minimize subsequent bind retries: see if we have allocated
        // any ports from the dynamic range, and if so update the port tracker.
        // Do NOT update the port tracker with non-dynamic ports (e.g. 4443
        // coming from TCP) because this will force it to revert back it its
        // configured min port. When maxPort is reached, allocation will begin
        // from minPort again, so we don't have to worry about wraps.
        int maxAllocatedPort
            = getMaxAllocatedPort(
                    iceStream,
                    portTracker.getMinPort(),
                    portTracker.getMaxPort());
        if (maxAllocatedPort > 0)
        {
            int nextPort = 1 + maxAllocatedPort;
            portTracker.setNextPort(nextPort);
            if (logger.isDebugEnabled())
                logger.debug("Updating the port tracker min port: " + nextPort);
        }

        return iceAgent;
    }

    /**
     * @return the highest local port used by any of the local candidates of
     * {@code iceStream}, which falls in the range [{@code min}, {@code max}].
     */
    private int getMaxAllocatedPort(IceMediaStream iceStream, int min, int max)
    {
        return
            Math.max(
                    getMaxAllocatedPort(
                            iceStream.getComponent(Component.RTP),
                            min, max),
                    getMaxAllocatedPort(
                            iceStream.getComponent(Component.RTCP),
                            min, max));
    }

    /**
     * @return the highest local port used by any of the local candidates of
     * {@code component}, which falls in the range [{@code min}, {@code max}].
     */
    private int getMaxAllocatedPort(Component component, int min, int max)
    {
        int maxAllocatedPort = -1;

        if (component != null)
        {
            for (LocalCandidate candidate : component.getLocalCandidates())
            {
                int candidatePort = candidate.getTransportAddress().getPort();

                if (min <= candidatePort
                        && candidatePort <= max
                        && maxAllocatedPort < candidatePort)
                {
                    maxAllocatedPort = candidatePort;
                }
            }
        }

        return maxAllocatedPort;
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

                if (candidates != null && !candidates.isEmpty())
                {
                    for (LocalCandidate candidate : candidates)
                    {
                        if (candidate.getTransport() == Transport.TCP
                              && tcpHostHarvesterMappedPort != -1
                              && candidate.getTransportAddress().getPort()
                                   != tcpHostHarvesterMappedPort)
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
        if (transport == Transport.TCP && candidate.isSSL())
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
        DtlsControlImpl dtlsControl = getDtlsControl(/* channel */ null);
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

        // setup
        DtlsControl.Setup setup = dtlsControl.getSetup();

        if (setup != null)
            fingerprintPE.setSetup(setup.toString());
    }

    /**
     * Sets up {@link #dtlsControl} according to <tt>transport</tt>, adds all
     * (supported) remote candidates from <tt>transport</tt> to
     * {@link #iceAgent} and starts {@link #iceAgent} if it isn't started
     * already.
     */
    private synchronized void doStartConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
        if (closed)
            return;

        // Reflect the transport's rtcpmux onto this instance.
        setRtcpmux(transport);

        // Reflect the transport's remote fingerprints onto this instance.
        setRemoteFingerprints(transport);

        IceProcessingState iceAgentState = iceAgent.getState();

        if (iceAgentState.isEstablished())
        {
            // Adding candidates to a completed Agent is unnecessary and has
            // been observed to cause problems.
            return;
        }

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning
            = IceProcessingState.RUNNING.equals(iceAgentState);

        if (rtcpmux)
        {
            Component rtcpComponent = iceStream.getComponent(Component.RTCP);
            if (rtcpComponent != null)
                iceStream.removeComponent(rtcpComponent);
        }

        // Different streams may have different ufrag/pwd.
        setRemoteUfragAndPwd(transport);

        List<CandidatePacketExtension> candidates
            = transport.getChildExtensionsOfType(
                    CandidatePacketExtension.class);

        if (iceAgentStateIsRunning && candidates.isEmpty())
            return;

        int remoteCandidateCount
            = addRemoteCandidates(candidates, iceAgentStateIsRunning);

        if (iceAgentStateIsRunning)
        {
            if (remoteCandidateCount == 0)
            {
                // XXX Effectively, the check above but realizing that all
                // candidates were ignored:
                // iceAgentStateIsRunning && candidates.isEmpty().
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
            // Once again because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
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
        else if (iceStream.getRemoteUfrag() != null
                && iceStream.getRemotePassword() != null)
        {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.info("Starting ICE agent without remote candidates.");
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
     * Gets the <tt>Conference</tt> object that this <tt>TransportManager</tt>
     * is associated with.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Gets the number of {@link org.ice4j.ice.Component}-s to create in
     * {@link #iceStream}.
     */
    public int getNumComponents()
    {
        return numComponents;
    }

    /**
     * Gets the ICE local username fragment.
     */
    public String getLocalUfrag()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalUfrag();
    }

    /**
     * Gets the <tt>IceMediaStream</tt> of {@link #iceAgent} associated with the
     * <tt>Channel</tt> of this instance.
     */
    public IceMediaStream getIceStream()
    {
        return iceStream;
    }

    /**
     * Returns a boolean value determining whether this
     * <tt>IceUdpTransportManager</tt> will serve as the the controlling or
     * the controlled ICE agent.
     */
    public boolean isControlling()
    {
        return controlling;
    }

    /**
     * Returns whether this {@code IceUdpTransportManager} is using rtcp-mux.
     *
     * @return {@code true} if this {@code IceUdpTransportManager} is using
     * rtcp-mux; otherwise, {@code false}
     */
    public boolean isRtcpmux()
    {
        return rtcpmux;
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
    public DtlsControlImpl getDtlsControl(Channel channel)
    {
        return dtlsControl;
    }

    /**
     * Gets the <tt>IceSocketWrapper</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     *
     * @param component the <tt>Component</tt> from which to get a socket.
     * @return the <tt>IceSocketWrapper</tt> from the selected pair (if any)
     * from a specific {@link org.ice4j.ice.Component}.
     */
    private IceSocketWrapper getSocketForComponent(Component component)
    {
        CandidatePair selectedPair = component.getSelectedPair();

        return
            (selectedPair == null) ? null : selectedPair.getIceSocketWrapper();
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
                        new Socket[]{tcpSocket0, tcpSocket1});
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
        IceSocketWrapper[] streamConnectorSockets = new IceSocketWrapper[2];

        // RTP
        Component rtpComponent = iceStream.getComponent(Component.RTP);

        if (rtpComponent != null)
        {
            streamConnectorSockets[0 /* RTP */]
                = getSocketForComponent(rtpComponent);
        }

        // RTCP
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
        InetSocketAddress[] streamTargetAddresses = new InetSocketAddress[2];
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

        if (rtcpmux)
        {
            streamTargetAddresses[1] = streamTargetAddresses[0];
            streamTargetAddressCount++;
        }
        else if (numComponents > 1)
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
        // Log the changes in the ICE processing state of this
        // IceUdpTransportManager for the purposes of debugging.

        boolean interrupted = false;

        try
        {
            IceProcessingState oldState = (IceProcessingState) ev.getOldValue();
            IceProcessingState newState = (IceProcessingState) ev.getNewValue();

            StringBuilder s
                = new StringBuilder("ICE processing state of ")
                    .append(getClass().getSimpleName()).append(" #")
                    .append(Integer.toHexString(hashCode()))
                    .append(" (for channels");
            for (Channel channel : getChannels())
                s.append(" ").append(channel.getID());
            s.append(")  of conference ").append(conference.getID())
                .append(" changed from ").append(oldState).append(" to ")
                .append(newState).append(".");
            logger.info(s.toString());

            EventAdmin eventAdmin = conference.getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.sendEvent(
                        EventFactory.transportStateChanged(
                                this,
                                oldState,
                                newState));
            }
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
                channel.touch(Channel.ActivityType.TRANSPORT);
        }
    }

    /**
     * Initializes the static <tt>Harvester</tt> instances used by all
     * <tt>IceUdpTransportManager</tt> instances, that is
     * {@link #tcpHostHarvester} and {@link #singlePortHarvesters}.
     *
     * @param cfg the {@link ConfigurationService} which provides values to
     * configurable properties of the behavior/logic of the method
     * implementation
     */
    static void initializeStaticConfiguration(ConfigurationService cfg)
    {
        synchronized (IceUdpTransportManager.class)
        {
            if (staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = true;

            ICE_UFRAG_PREFIX = cfg.getString(ICE_UFRAG_PREFIX_PNAME, null);

            int singlePort = cfg.getInt(SINGLE_PORT_HARVESTER_PORT,
                                        SINGLE_PORT_DEFAULT_VALUE);
            if (singlePort != -1)
            {
                singlePortHarvesters
                    = SinglePortUdpHarvester.createHarvesters(singlePort);
                if (singlePortHarvesters.isEmpty())
                {
                    singlePortHarvesters = null;
                    classLogger.info("No single-port harvesters created.");
                }
            }

            if (!cfg.getBoolean(DISABLE_TCP_HARVESTER, false))
            {
                int port = cfg.getInt(TCP_HARVESTER_PORT, -1);
                boolean fallback = false;
                boolean ssltcp = cfg.getBoolean(TCP_HARVESTER_SSLTCP,
                                                TCP_HARVESTER_SSLTCP_DEFAULT);

                if (port == -1)
                {
                    port = TCP_DEFAULT_PORT;
                    fallback = true;
                }

                try
                {
                    tcpHostHarvester = new TcpHarvester(port, ssltcp);
                }
                catch (IOException ioe)
                {
                    classLogger.warn(
                            "Failed to initialize TCP harvester on port " + port
                                + ": " + ioe
                                + (fallback
                                    ? ". Retrying on port " + TCP_FALLBACK_PORT
                                    : "")
                                + ".");
                    // If no fallback is allowed, the method will return.
                }
                if (tcpHostHarvester == null)
                {
                    // If TCP_HARVESTER_PORT specified a port, then fallback was
                    // disabled. However, if the binding on the port (above)
                    // fails, then the method should return.
                    if (!fallback)
                        return;

                    port = TCP_FALLBACK_PORT;
                    try
                    {
                        tcpHostHarvester
                            = new TcpHarvester(port, ssltcp);
                    }
                    catch (IOException ioe)
                    {
                        classLogger.warn(
                                "Failed to initialize TCP harvester on fallback"
                                    + " port " + port + ": " + ioe);
                        return;
                    }
                }

                if (classLogger.isInfoEnabled())
                {
                    classLogger.info("Initialized TCP harvester on port " + port
                                        + ", using SSLTCP:" + ssltcp);
                }

                HarvesterConfiguration addressesConfig
                    = HarvesterConfiguration.getInstance(cfg);
                // if there is mapping addresses configured or discovered
                // use them
                if(addressesConfig.getPublicAddress() != null
                    && addressesConfig.getLocalAddress() != null)
                {
                    tcpHostHarvester.addMappedAddress(
                        addressesConfig.getPublicAddress().getAddress(),
                        addressesConfig.getLocalAddress().getAddress());
                }

                int mappedPort = cfg.getInt(TCP_HARVESTER_MAPPED_PORT, -1);
                if (mappedPort != -1)
                {
                    tcpHostHarvesterMappedPort = mappedPort;
                    tcpHostHarvester.addMappedPort(mappedPort);
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

        if (conference.includeInStatistics())
        {
            Transport transport = getTransport();
            if (transport == null)
            {
                logger.warn("Cannot get transport type.");
            }
            else
            {
                Conference.Statistics statistics = conference.getStatistics();
                if (transport == Transport.TCP || transport == Transport.SSLTCP)
                {
                    statistics.totalTcpTransportManagers.incrementAndGet();
                }
                else if (transport == Transport.UDP)
                {
                    statistics.totalUdpTransportManagers.incrementAndGet();
                }
            }
        }

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportConnected(this));
        }

        for (Channel channel : getChannels())
        {
            channel.transportConnected();
        }
    }

    /**
     * @return the {@link Transport} (e.g. UDP or TCP) of the selected pair
     * of this {@link IceUdpTransportManager}. If the transport manager is
     currently not connected, returns {@code null}.
     */
    private Transport getTransport()
    {
        Transport transport = null;

        Component component = iceStream.getComponent(Component.RTP);
        if (component != null)
        {
            CandidatePair selectedPair = component.getSelectedPair();
            if (selectedPair != null)
            {
                transport
                    = selectedPair.getLocalCandidate().getHostAddress()
                            .getTransport();
            }
        }

        return transport;
    }

    /**
     * Sets the remote DTLS fingerprints which are to validate and verify the
     * remote DTLS certificate in the DTLS sessions in which this instance is
     * the local peer.
     *
     * @param transport the {@code IceUdpTransportPacketExtension} which carries
     * the remote DTLS fingerprints which are to validate and verify the remote
     * DTLS certificate in the DTLS sessions in which this instance is the local
     * peer
     */
    private void setRemoteFingerprints(IceUdpTransportPacketExtension transport)
    {
        // In accord with https://xmpp.org/extensions/xep-0320.html, the
        // fingerprint signals the setup attribute defined by
        // https://tools.ietf.org/html/rfc4145 as well.
        List<DtlsFingerprintPacketExtension> dfpes
            = transport.getChildExtensionsOfType(
                    DtlsFingerprintPacketExtension.class);

        if (!dfpes.isEmpty())
        {
            Map<String, String> remoteFingerprints = new LinkedHashMap<>();
            boolean setSetup = true;
            DtlsControl.Setup setup = null;

            for (DtlsFingerprintPacketExtension dfpe : dfpes)
            {
                // fingerprint & hash
                remoteFingerprints.put(
                        dfpe.getHash(),
                        dfpe.getFingerprint());

                // setup
                if (setSetup)
                {
                    String aSetupStr = dfpe.getSetup();
                    DtlsControl.Setup aSetup = null;

                    if (!StringUtils.isNullOrEmpty(aSetupStr, /* trim */ true))
                    {
                        try
                        {
                            aSetup = DtlsControl.Setup.parseSetup(aSetupStr);
                        }
                        catch (IllegalArgumentException e)
                        {
                            // The value of aSetup will remain null and will
                            // thus signal the exception.
                        }
                    }
                    if (aSetup == null)
                    {
                        // The null setup of dfpe disagrees with any non-null
                        // setups of previous and/or next remoteFingerprints.
                        // For the sake of clarity, don't set a setup on
                        // dtlsControl.
                        setSetup = false;
                    }
                    else if (setup == null)
                    {
                        setup = aSetup;
                    }
                    else if (!setup.equals(aSetup))
                    {
                        // The setup of dfpe disagrees with the setups of the
                        // prevous remoteFingerprints. For the sake of clarity,
                        // don't set a setup on dtlsControl.
                        setSetup = false;
                    }
                }
            }

            // setup
            if (setSetup && setup != null)
            {
                // The setup of the local peer is the inverse of the setup of
                // the remote peer.
                switch (setup)
                {
                case ACTIVE:
                    if (DtlsControl.Setup.ACTIVE.equals(dtlsControl.getSetup()))
                    {
                        // ACTPASS is the same as passive to DtlsControlImpl at
                        // the time of this writing.
                        dtlsControl.setSetup(DtlsControl.Setup.ACTPASS);
                    }
                    break;
                case PASSIVE:
                    dtlsControl.setSetup(DtlsControl.Setup.ACTIVE);
                    break;
                default:
                    // TODO I don't have the time to deal with these at the time
                    // of this writing.
                    break;
                }
            }

            dtlsControl.setRemoteFingerprints(remoteFingerprints);
        }
    }

    private void setRemoteUfragAndPwd(IceUdpTransportPacketExtension transport)
    {
        String ufrag = transport.getUfrag();

        if (ufrag != null)
            iceStream.setRemoteUfrag(ufrag);

        String password = transport.getPassword();

        if (password != null)
            iceStream.setRemotePassword(password);
    }

    private void setRtcpmux(IceUdpTransportPacketExtension transport)
    {
        if (transport.isRtcpMux())
        {
            rtcpmux = true;
            if (channelForDtls != null && channelForDtls instanceof RtpChannel)
            {
                ((RtpChannel) channelForDtls)
                    .getDatagramFilter(true)
                        .setAcceptNonRtp(false);
            }
        }
        dtlsControl.setRtcpmux(rtcpmux);
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
                            logger.info(
                                "Failed to connect IceUdpTransportManager: "
                                         + ofe);

                            synchronized (connectThreadSyncRoot)
                            {
                                connectThread = null;
                                return;
                            }
                        }

                        // XXX The value of the field iceAgent is null at times.
                        Agent iceAgent = IceUdpTransportManager.this.iceAgent;

                        if (iceAgent == null)
                        {
                            // This TransportManager has (probably) been closed.
                            return;
                        }

                        IceProcessingState state = iceAgent.getState();

                        if (state.isEstablished())
                        {
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
                    // Wait for ICE to finish establishing connectivity (or to
                    // determine that no connectivity can be successfully
                    // established, of course).
                    Agent iceAgent = (Agent) ev.getSource();

                    if (iceAgent.isOver())
                    {
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

        Agent iceAgent = this.iceAgent;
        if (iceAgent == null)
        {
            // The TransportManager has been closed, so we should return and
            // let the thread finish.
            return;
        }

        iceAgent.addStateChangeListener(propertyChangeListener);

        // Wait for the connectivity checks to finish if they have been started.
        boolean interrupted = false;

        IceProcessingState state = iceAgent.getState();
        synchronized (syncRoot)
        {
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
                    state = iceAgent.getState();
                    if (this.iceAgent == null)
                        break;
                }
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        // Make sure stateChangeListener is removed from iceAgent in case its
        // #propertyChange(PropertyChangeEvent) has never been executed.
        iceAgent.removeStateChangeListener(propertyChangeListener);

        // Check the state of ICE processing and throw an exception if failed.
        if (this.iceAgent == null)
        {
            throw new OperationFailedException(
                    "TransportManager closed",
                    OperationFailedException.GENERAL_ERROR);
        }
        else if (IceProcessingState.FAILED.equals(state))
        {
            throw new OperationFailedException(
                    "ICE failed",
                    OperationFailedException.GENERAL_ERROR);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected()
    {
        return iceConnected;
    }
}
