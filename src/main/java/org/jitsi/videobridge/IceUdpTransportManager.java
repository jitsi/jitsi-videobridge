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
import java.util.logging.*;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.CandidateType;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.rest.*;
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
    public static final String DISABLE_TCP_HARVESTER
        = "org.jitsi.videobridge.DISABLE_TCP_HARVESTER";

    /**
     * The name of the property which controls the port number used for
     * <tt>SinglePortUdpHarvester</tt>s.
     */
    public static final String SINGLE_PORT_HARVESTER_PORT
            = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT";

    /**
     * The name of the property used to control {@link #keepAliveStrategy}.
     */
    public static final String KEEP_ALIVE_STRATEGY_PNAME
            = "org.jitsi.videobridge.KEEP_ALIVE_STRATEGY";

    /**
     * The {@link KeepAliveStrategy} to configure for ice4j {@link Component}s,
     * which will dictate which candidate pairs to keep alive.
     * Default to keeping alive the selected pair and any TCP pairs.
     */
    private static KeepAliveStrategy keepAliveStrategy
        = KeepAliveStrategy.SELECTED_AND_TCP;

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
    public static final String TCP_HARVESTER_MAPPED_PORT
        = "org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT";

    /**
     * The name of the property which controls the port to which the
     * <tt>TcpHarvester</tt> will bind.
     */
    public static final String TCP_HARVESTER_PORT
        = "org.jitsi.videobridge.TCP_HARVESTER_PORT";

    /**
     * The name of the property which controls the use of ssltcp candidates by
     * <tt>TcpHarvester</tt>.
     */
    public static final String TCP_HARVESTER_SSLTCP
        = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP";

    /**
     * The name of the property that can be used to control the value of
     * {@link #iceUfragPrefix}.
     */
    public static final String ICE_UFRAG_PREFIX_PNAME
        = "org.jitsi.videobridge.ICE_UFRAG_PREFIX";

    /**
     * The optional prefix to use for generated ICE local username fragments.
     */
    private static String iceUfragPrefix;

    /**
     * The default value of the <tt>TCP_HARVESTER_SSLTCP</tt> property.
     */
    private static final boolean TCP_HARVESTER_SSLTCP_DEFAULT = true;

    /**
     * The single <tt>TcpHarvester</tt> instance for the
     * application.
     */
    private static TcpHarvester tcpHarvester = null;

    /**
     * The <tt>SinglePortUdpHarvester</tt>s which will be appended to ICE
     * <tt>Agent</tt>s managed by <tt>IceUdpTransportManager</tt> instances.
     */
    private static List<SinglePortUdpHarvester> singlePortHarvesters = null;

    /**
     * Global variable do we consider this transport manager as healthy.
     * By default we consider healthy, if we fail to bind to the single port
     * port we consider the bridge as unhealthy.
     */
    public static boolean healthy = true;

    /**
     * The flag which indicates whether application-wide harvesters, stored
     * in the static fields {@link #tcpHarvester} and
     * {@link #singlePortHarvesters} have been initialized.
     */
    private static boolean staticConfigurationInitialized = false;

    /**
     * The "mapped port" added to {@link #tcpHarvester}, or -1.
     */
    private static int tcpHarvesterMappedPort = -1;

    /**
     * Whether the "component socket" feature of ice4j should be used. If this
     * feature is used, ice4j will create a separate merging socket instance
     * for each component, which reads from the sockets of all successful
     * candidate pairs. Otherwise, this merging socket instance is not created,
     * and the sockets from the individual candidate pairs should be used
     * directly.
     */
    private static boolean useComponentSocket = true;

    /**
     * The name of the property which configures {@link #useComponentSocket}.
     */
    public static final String USE_COMPONENT_SOCKET_PNAME
        = "org.jitsi.videobridge.USE_COMPONENT_SOCKET";

    /**
     * Initializes the static <tt>Harvester</tt> instances used by all
     * <tt>IceUdpTransportManager</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     *
     * @param cfg the {@link ConfigurationService} which provides values to
     * configurable properties of the behavior/logic of the method
     * implementation
     */
    private static void initializeStaticConfiguration(ConfigurationService cfg)
    {
        synchronized (IceUdpTransportManager.class)
        {
            if (staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = true;

            useComponentSocket
                = cfg.getBoolean(USE_COMPONENT_SOCKET_PNAME, useComponentSocket);
            classLogger.info("Using component socket: " + useComponentSocket);

            iceUfragPrefix = cfg.getString(ICE_UFRAG_PREFIX_PNAME, null);

            String strategyName = cfg.getString(KEEP_ALIVE_STRATEGY_PNAME);
            KeepAliveStrategy strategy
                = KeepAliveStrategy.fromString(strategyName);
            if (strategyName != null && strategy == null)
            {
                classLogger.warn("Invalid keep alive strategy name: "
                                     + strategyName);
            }
            else if (strategy != null)
            {
                keepAliveStrategy = strategy;
            }

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

                IceUdpTransportManager.healthy = singlePortHarvesters != null;
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
                    tcpHarvester = new TcpHarvester(port, ssltcp);
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
                if (tcpHarvester == null)
                {
                    // If TCP_HARVESTER_PORT specified a port, then fallback was
                    // disabled. However, if the binding on the port (above)
                    // fails, then the method should return.
                    if (!fallback)
                        return;

                    port = TCP_FALLBACK_PORT;
                    try
                    {
                        tcpHarvester
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

                int mappedPort = cfg.getInt(TCP_HARVESTER_MAPPED_PORT, -1);
                if (mappedPort != -1)
                {
                    tcpHarvesterMappedPort = mappedPort;
                    tcpHarvester.addMappedPort(mappedPort);
                }
            }
        }
    }

    /**
     * Stops the static <tt>Harvester</tt> instances used by all
     * <tt>IceUdpTransportManager</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     *
     * @param cfg the {@link ConfigurationService} which provides values to
     * configurable properties of the behavior/logic of the method
     * implementation
     */
    public static void closeStaticConfiguration(ConfigurationService cfg)
    {
        synchronized (IceUdpTransportManager.class)
        {
            if (!staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = false;

            if (singlePortHarvesters != null)
            {
                singlePortHarvesters.forEach(AbstractUdpListener::close);
                singlePortHarvesters = null;
            }

            if (tcpHarvester != null)
            {
                tcpHarvester.close();
                tcpHarvester = null;
            }

            // Reset the flag to initial state.
            healthy = true;
        }
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
     * The {@link DiagnosticContext} of this diagnostic instance provider.
     */
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();

    /**
     * An identifier of this {@link IceUdpTransportManager}.
     */
    private final String id;

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
     * The ICE {@link Agent}.
     */
    private Agent iceAgent;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the <tt>state</tt> of {@link #iceAgent}.
     */
    private final PropertyChangeListener iceAgentStateChangeListener
        = this::iceAgentStateChange;

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
        = this::iceStreamPairChange;

    /**
     * Whether this <tt>IceUdpTransportManager</tt> will serve as the the
     * controlling or controlled ICE agent.
     */
    private final boolean controlling;

    /**
     * The number of {@link Component}-s to create in
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
     * The {@link TransportCCEngine} instance for this transport channel. It
     * handles transport-wide numbering of packets. It is shared among the
     * {@link RtpChannel}s/{@link MediaStream}s of this transport manager.
     */
    private final TransportCCEngine transportCCEngine;

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
        this(conference, controlling, 2, DEFAULT_ICE_STREAM_NAME, null);
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
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents)
        throws IOException
    {
        this(conference, controlling, numComponents,
             DEFAULT_ICE_STREAM_NAME, null);
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
     * @param id an identifier of the {@link IceUdpTransportManager}.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents,
                                  String id)
        throws IOException
    {
        this(conference, controlling, numComponents,
             DEFAULT_ICE_STREAM_NAME, id);
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
     * @param id an identifier of the {@link IceUdpTransportManager}.
     * @throws IOException
     */
    public IceUdpTransportManager(Conference conference,
                                  boolean controlling,
                                  int numComponents,
                                  String iceStreamName,
                                  String id)
        throws IOException
    {
        this.conference = conference;
        this.id = id;
        this.controlling = controlling;
        this.numComponents = numComponents;
        this.rtcpmux = numComponents == 1;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
        this.transportCCEngine = new TransportCCEngine(diagnosticContext);

        // Setup the diagnostic context.
        conference.appendDiagnosticInformation(diagnosticContext);
        diagnosticContext.put("transport", hashCode());

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
        {
            return false;
        }

        if (channel instanceof SctpConnection
                && sctpConnection != null
                && sctpConnection != channel)
        {
            logger.error(
                "Not adding a second SctpConnection to TransportManager.");
            return false;
        }

        if (!super.addChannel(channel))
        {
            return false;
        }

        if (channel instanceof SctpConnection)
        {
            // When an SctpConnection is added, it automatically replaces
            // channelForDtls, because it needs DTLS packets for the application
            // data inside them.
            sctpConnection = (SctpConnection) channel;
            if (channelForDtls != null && channelForDtls instanceof RtpChannel)
            {
                // channelForDtls is usually an RtpChannel, unless a second
                // SctpConnection is added for this transport manager. This has
                // been observed to happen when an endpoint ID is reused and
                // new channels (including a new SctpConnection) are allocated
                // before the IceUdpTransportManager instance is disposed. In
                // this case, we just replace the old SctpConnection with the
                // new one.
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
        {
            channel.transportConnected();
        }

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
            {
                continue;
            }

            if (iceAgentStateIsRunning)
            {
                component.addUpdateRemoteCandidates(remoteCandidate);
            }
            else
            {
                component.addRemoteCandidate(remoteCandidate);
            }
            remoteCandidateCount++;
        }

        return remoteCandidateCount;
    }

    /**
     * Adds to {@link #iceAgent} the
     * {@link org.ice4j.ice.harvest.CandidateHarvester} instances managed by
     * jitsi-videobridge (the TCP and SinglePort harvesters), and configures the
     * use of the dynamic host harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     * @param rtcpmux whether rtcpmux will be used by this
     * <tt>IceUdpTransportManager</tt>.
     */
    private void configureHarvesters(Agent iceAgent, boolean rtcpmux)
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);
        boolean disableDynamicHostHarvester = false;

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

            if (tcpHarvester != null)
            {
                iceAgent.addCandidateHarvester(tcpHarvester);
            }
            if (singlePortHarvesters != null)
            {
                for (CandidateHarvester harvester : singlePortHarvesters)
                {
                    iceAgent.addCandidateHarvester(harvester);
                    disableDynamicHostHarvester = true;
                }
            }
        }

        // Disable dynamic ports (UDP) if we're using "single port" (UPD), as
        // there's no need for a client to try a similar UDP candidate twice.
        if (disableDynamicHostHarvester)
        {
            iceAgent.setUseHostHarvester(false);
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
        return component.getLocalCandidates().stream().
            anyMatch(
                localCandidate -> localCandidate.canReach(remoteCandidate));
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

            getChannels().forEach(this::close);

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

                    for (Channel c : getChannels())
                    {
                        if (c instanceof RtpChannel)
                        {
                            newChannelForDtls = (RtpChannel) c;
                        }
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
                    {
                        datagramSocket.close();
                    }
                    datagramSocket = connector.getControlSocket();
                    if (datagramSocket != null)
                    {
                        datagramSocket.close();
                    }

                    Socket socket = connector.getDataTCPSocket();

                    if (socket != null)
                    {
                        socket.close();
                    }
                    socket = connector.getControlTCPSocket();
                    if (socket != null)
                    {
                        socket.close();
                    }
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
        {
            close();
        }

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
        Agent iceAgent = new Agent(logger.getLevel(), iceUfragPrefix);

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        configureHarvesters(iceAgent, rtcpmux);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);

        int portBase = portTracker.getPort();

        IceMediaStream iceStream = iceAgent.createMediaStream(iceStreamName);

        iceAgent.createComponent(
                iceStream, Transport.UDP,
                portBase, portBase, portBase + 100,
                keepAliveStrategy,
                useComponentSocket);

        if (numComponents > 1)
        {
            iceAgent.createComponent(
                    iceStream, Transport.UDP,
                    portBase + 1, portBase + 1, portBase + 101,
                    keepAliveStrategy,
                    useComponentSocket);
        }

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
            {
                logger.debug("Updating the port tracker min port: " + nextPort);
            }
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
                              && tcpHarvesterMappedPort != -1
                              && candidate.getTransportAddress().getPort()
                                   != tcpHarvesterMappedPort)
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

            String colibriWsUrl = getColibriWsUrl();
            if (colibriWsUrl != null)
            {
                WebSocketPacketExtension wsPacketExtension
                    = new WebSocketPacketExtension(colibriWsUrl);
                pe.addChildExtension(wsPacketExtension);
            }

            if (rtcpmux)
            {
                pe.addChildExtension(new RtcpmuxPacketExtension());
            }

            describeDtlsControl(pe);
        }
    }

    /**
     * @return the URL to advertise for COLIBRI WebSocket connections for this
     * transport manager.
     */
    private String getColibriWsUrl()
    {
        BundleContext bundleContext
            = getConference().getVideobridge().getBundleContext();
        ColibriWebSocketService colibriWebSocketService
            = ServiceUtils.getService(
                    bundleContext, ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
        {
            return colibriWebSocketService.getColibriWebSocketUrl(
                getConference().getID(),
                id,
                iceAgent.getLocalPassword());
        }

        return null;
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
            candidatePE.setTcpType(candidate.getTcpType().toString());
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
        DtlsControlImpl dtlsControl = this.dtlsControl;
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
        {
            fingerprintPE.setSetup(setup.toString());
        }
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
        {
            return;
        }

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
            {
                iceStream.removeComponent(rtcpComponent);
            }
        }

        // Different streams may have different ufrag/pwd.
        setRemoteUfragAndPwd(transport);

        List<CandidatePacketExtension> candidates
            = transport.getChildExtensionsOfType(
                    CandidatePacketExtension.class);

        if (iceAgentStateIsRunning && candidates.isEmpty())
        {
            return;
        }

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
                iceAgent.getStreams()
                    .forEach(stream -> stream.getComponents()
                                .forEach(Component::updateRemoteCandidates));
            }
        }
        else if (remoteCandidateCount != 0)
        {
            // Once again, because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            if (iceAgent.getStreams().stream().allMatch(
                stream -> stream.getComponents().stream().allMatch(
                    component -> component.getRemoteCandidateCount() >= 1)))
            {
                logger.info(
                    "We have remote candidates for all ICE components. "
                        + "Starting the ICE agent.");
                iceAgent.startConnectivityEstablishment();
            }
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
     * Gets the ICE password.
     */
    public String getIcePassword()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalPassword();
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
    public SrtpControl getSrtpControl(Channel channel)
    {
        return dtlsControl;
    }

    /**
     * {@inheritDoc}
     * </p>
     * Note, that we don't cache any instances that we create here, so this
     * method should be called no more than once for each channel!
     */
    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        if (!getChannels().contains(channel))
        {
            return null;
        }

        IceSocketWrapper rtpSocket
            = getSocketForComponent(iceStream.getComponent(Component.RTP));

        IceSocketWrapper rtcpSocket;
        if (numComponents > 1 && !rtcpmux)
        {
            rtcpSocket
                = getSocketForComponent(iceStream.getComponent(Component.RTCP));
        }
        else
        {
            rtcpSocket = rtpSocket;
        }

        if (rtpSocket == null || rtcpSocket == null)
        {
            throw new IllegalStateException("No sockets from ice4j.");
        }


        if (channel instanceof SctpConnection)
        {
            DatagramSocket udpSocket = rtpSocket.getUDPSocket();
            Socket tcpSocket = rtpSocket.getTCPSocket();
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
                catch (SocketException se)
                {
                    logger.warn("Failed to create DTLS socket: " + se);
                }
            }
            else if (tcpSocket instanceof MultiplexingSocket)
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
            else
            {
                logger.warn("No valid sockets from ice4j.");
                return null;
            }
        }

        if (! (channel instanceof RtpChannel))
        {
            return null;
        }

        RtpChannel rtpChannel = (RtpChannel) channel;
        DatagramSocket rtpUdpSocket = rtpSocket.getUDPSocket();
        DatagramSocket rtcpUdpSocket = rtcpSocket.getUDPSocket();
        if (rtpUdpSocket instanceof MultiplexingDatagramSocket
            && rtcpUdpSocket instanceof MultiplexingDatagramSocket)
        {
            return getUDPStreamConnector(
                rtpChannel,
                (MultiplexingDatagramSocket) rtpUdpSocket,
                (MultiplexingDatagramSocket) rtcpUdpSocket);
        }

        Socket rtpTcpSocket = rtpSocket.getTCPSocket();
        Socket rtcpTcpSocket = rtcpSocket.getTCPSocket();
        if (rtpTcpSocket instanceof MultiplexingSocket
            && rtcpTcpSocket instanceof MultiplexingSocket)
        {
            return getTCPStreamConnector(
                rtpChannel,
                (MultiplexingSocket) rtpTcpSocket,
                (MultiplexingSocket) rtcpTcpSocket);
        }

        logger.warn("No valid sockets from ice4j");
        return null;
    }

    /**
     * Creates and returns a UDP <tt>StreamConnector</tt> to be used by a
     * specific <tt>RtpChannel</tt>, using a specific set of
     * {@link MultiplexingDatagramSocket} from which to read. The provided
     * sockets are not used directly, but using a filter for the specified
     * channel (so that they can be shared for example by an audio channel and
     * a video channel).
     *
     * @param rtpChannel the <tt>RtpChannel</tt> which is to use the created
     * <tt>StreamConnector</tt>.
     * @param rtpSocket the socket from which to read RTP
     * @param rtcpSocket the socket from which to read RTCP.
     * @return a UDP <tt>StreamConnector</tt>.
     */
    private StreamConnector getUDPStreamConnector(
        RtpChannel rtpChannel,
        MultiplexingDatagramSocket rtpSocket,
        MultiplexingDatagramSocket rtcpSocket)
    {
        Objects.requireNonNull(rtpSocket, "rtpSocket");
        Objects.requireNonNull(rtcpSocket, "rtcpSocket");

        try
        {
            MultiplexedDatagramSocket channelRtpSocket
                = rtpSocket.getSocket(
                    rtpChannel.getDatagramFilter(false /* RTP */));
            MultiplexedDatagramSocket channelRtcpSocket
                = rtcpSocket.getSocket(
                    rtpChannel.getDatagramFilter(true /* RTCP */));

            return new DefaultStreamConnector(
                channelRtpSocket,
                channelRtcpSocket,
                rtcpmux);
        }
        catch (SocketException se) // never thrown
        {
            logger.error("An unexpected exception occurred.", se );
            return null;
        }
    }

    /**
     * Creates and returns a TCP <tt>StreamConnector</tt> to be used by a
     * specific <tt>RtpChannel</tt>, using a specific pair of
     * {@link MultiplexingSocket} from which to read. The provided sockets are
     * not used directly, but using a filter for the specified channel (so that
     * they can be shared for example by an audio channel and a video channel).
     *
     * @param rtpChannel the <tt>RtpChannel</tt> which is to use the created
     * <tt>StreamConnector</tt>.
     * @param rtpSocket the socket from which to read RTP
     * @param rtcpSocket the socket from which to read RTCP.
     * @return a UDP <tt>StreamConnector</tt>.
     */
    private StreamConnector getTCPStreamConnector(
        RtpChannel rtpChannel,
        MultiplexingSocket rtpSocket,
        MultiplexingSocket rtcpSocket)
    {
        Objects.requireNonNull(rtpSocket, "rtpSocket");
        Objects.requireNonNull(rtcpSocket, "rtcpSocket");

        try
        {
            MultiplexedSocket channelRtpSocket
                = rtpSocket.getSocket(
                rtpChannel.getDatagramFilter(false /* RTP */));
            MultiplexedSocket channelRtcpSocket
                = rtcpSocket.getSocket(
                rtpChannel.getDatagramFilter(true /* RTCP */));

            return new DefaultTCPStreamConnector(
                channelRtpSocket,
                channelRtcpSocket,
                rtcpmux);
        }
        catch (SocketException se) // never thrown
        {
            logger.error("An unexpected exception occurred.", se );
            return null;
        }
    }

    /**
     * Extracts the {@link IceSocketWrapper} for a specific {@link Component}.
     * The way this is implemented depends on whether the component socket is
     * configured or not.
     * @param component the {@link Component} to get a socket from.
     * @return the socket.
     */
    private IceSocketWrapper getSocketForComponent(Component component)
    {
        if (useComponentSocket)
        {
            return component.getSocketWrapper();
        }
        else
        {
            CandidatePair selectedPair = component.getSelectedPair();

            return
                (selectedPair == null) ? null : selectedPair
                    .getIceSocketWrapper();
        }
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

            logger.info(Logger.Category.STATISTICS,
                        "ice_state_change," + getLoggingId()
                        + " old_state=" + oldState
                        + ",new_state=" + newState);

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
            {
                interrupted = true;
            }
            else if (t instanceof ThreadDeath)
            {
                throw (ThreadDeath) t;
            }
        }
        finally
        {
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
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
            getChannels().forEach(
                channel -> channel.touch(Channel.ActivityType.TRANSPORT));
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

        getChannels().forEach(Channel::transportConnected);
    }

    /**
     * The name of the property which controls whether health checks failures
     * should be permanent. If this is set to true and the bridge fails its
     * health check once, it will not go back to the healthy state.
     */
    private static final String PERMANENT_FAILURE_PNAME
        = Health.class.getName() + ".PERMANENT_FAILURE";

    private static BundleContext bundleContext = null;
    private static boolean permanentFailureMode = false;
    /**
     * @return the {@link Transport} (e.g. UDP or TCP) of the selected pair
     * of this {@link IceUdpTransportManager}. If the transport manager is
     * currently not connected, returns {@code null}.
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
                            logger.debug("Unable to parse: " + aSetupStr, e);
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
                        // previous remoteFingerprints. For the sake of clarity,
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
        {
            iceStream.setRemoteUfrag(ufrag);
        }

        String password = transport.getPassword();

        if (password != null)
        {
            iceStream.setRemotePassword(password);
        }
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
                connectThread = new Thread(() -> {
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
                        logger.log(Level.WARNING,
                                   Logger.Category.STATISTICS,
                                   "ice_failed," + getLoggingId()
                                   + " state=" + state);
                    }
                });

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
                    {
                        break;
                    }
                }
            }
        }

        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }

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

    /**
     * @return a string which identifies this {@link IceUdpTransportManager}
     * for the purposes of logging. The string is a comma-separated list of
     * "key=value" pairs.
     */
    private String getLoggingId()
    {
        // Only use the ID string for channelForDtls, because what we care
        // about is the info for any of the channels, and channelForDtls
        // always contains a channel when we have one.
        return Channel.getLoggingId(channelForDtls);
    }

    /**
     * {@inheritDoc}
     */
    public TransportCCEngine getTransportCCEngine()
    {
        return transportCCEngine;
    }
}
