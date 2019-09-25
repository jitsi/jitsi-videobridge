/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
import java.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.transport.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.CandidateType;
import org.json.simple.*;
import org.osgi.framework.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class IceTransport
{
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
     * Whether this <tt>TransportManager</tt> has been closed.
     */
    protected boolean closed = false;

    /**
     * The ICE {@link Agent}.
     */
    protected Agent iceAgent;

    /**
     * Whether ICE connectivity has been established.
     */
    protected boolean iceConnected = false;

    /**
     * The <tt>IceMediaStream</tt> of {@link #iceAgent} associated with the
     * <tt>Channel</tt> of this instance.
     */
    protected final IceMediaStream iceStream;

    /**
     * The single {@link Component} that we have
     * (since we use bundle and rtcp-mux).
     */
    protected final Component iceComponent;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the properties of the <tt>CandidatePair</tt>s of
     * {@link #iceStream}.
     */
    private final PropertyChangeListener iceStreamPairChangeListener
        = this::iceStreamPairChange;

    /**
     * The listener we register with the {@link Agent} to listen to ICE state
     * change events.
     */
    private final PropertyChangeListener iceStateChangeListener
            = this::iceStateChange;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    protected final Logger logger;

    /**
     * A string to add to log messages to identify the instance.
     */
    protected final String logPrefix;

    /**
     * A flag which is raised if ICE has run and failed.
     */
    private boolean iceFailed = false;

    /**
     * The OSGi bundle context.
     */
    private final BundleContext bundleContext;

    /**
     * The ID of the endpoint.
     */
    private final String endpointId;

    /**
     * The ID of the conference.
     */
    private final String conferenceId;

    /**
     * Initializes a new <tt>IceTransport</tt> instance.
     *
     * @param endpoint the {@link Endpoint} associated with this
     * {@link IceTransport}.
     * @param controlling {@code true} if the new instance will initialized to
     * serve as a controlling ICE agent; otherwise, {@code false}
     */
    IceTransport(Endpoint endpoint, boolean controlling, Logger parentLogger)
        throws IOException
    {
        Conference conference = endpoint.getConference();
        this.bundleContext = conference.getBundleContext();
        this.endpointId = endpoint.getID();
        this.conferenceId = conference.getID();
        this.logger = parentLogger.createChildLogger(getClass().getName());

        // We've seen some instances where the configuration service is not
        // yet initialized. These are now fixed, but just in case this happens
        // again we break early (otherwise we may initialize some of the static
        // fields, and it will not be re-initialized when the configuration
        // service is available).
        ConfigurationService cfg
                = Objects.requireNonNull(
                        conference.getVideobridge().getConfigurationService(),
                        "No configuration service.");
        String streamName = "stream-" + endpoint.getID();
        iceAgent = createIceAgent(controlling, streamName, cfg);
        iceStream = iceAgent.getStream(streamName);
        iceComponent = iceStream.getComponent(Component.RTP);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);

        //TODO(brian): this should be replaced by passing a log context
        // when creating the child logger, but we don't have this
        // value yet when creating the child logger, so we need to
        // change the logger to allow adding context after creation,
        // then we can get rid of the need for logPrefix here
        this.logPrefix
                = "[local_ufrag=" + iceAgent.getLocalUfrag() + "] ";

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportCreated(this));
        }

        iceAgent.addStateChangeListener(iceStateChangeListener);
    }

    /**
     * Adds to {@link #iceAgent} the
     * {@link org.ice4j.ice.harvest.CandidateHarvester} instances managed by
     * jitsi-videobridge (the TCP and SinglePort harvesters), and configures the
     * use of the dynamic host harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     */
    private void configureHarvesters(Agent iceAgent, ConfigurationService cfg)
    {
        if (cfg != null)
        {
            useComponentSocket
                    = cfg.getBoolean(
                            USE_COMPONENT_SOCKET_PNAME,
                            useComponentSocket);
        }
        if (logger.isDebugEnabled())
        {
            logger.debug("Using component socket: " + useComponentSocket);
        }

        iceUfragPrefix = cfg.getString(ICE_UFRAG_PREFIX_PNAME, null);
        String strategyName = cfg.getString(KEEP_ALIVE_STRATEGY_PNAME);
        KeepAliveStrategy strategy
                = KeepAliveStrategy.fromString(strategyName);
        if (strategyName != null && strategy == null)
        {
            logger.warn("Invalid keep alive strategy name: "
                    + strategyName);
        }
        else if (strategy != null)
        {
            keepAliveStrategy = strategy;
        }

        // TODO CandidateHarvesters may take (non-trivial) time to initialize so
        // initialize them as soon as possible, don't wa it to initialize them
        // after a Channel is requested.
        // XXX Unfortunately, TcpHarvester binds to specific local addresses
        // while Jetty binds to all/any local addresses and, consequently, the
        // order of the binding is important at the time of this writing. That's
        // why TcpHarvester is left to initialize as late as possible right now.
        Harvesters.initializeStaticConfiguration(cfg);

        if (Harvesters.tcpHarvester != null)
        {
            iceAgent.addCandidateHarvester(Harvesters.tcpHarvester);
        }
        if (Harvesters.singlePortHarvesters != null)
        {
            for (CandidateHarvester harvester : Harvesters.singlePortHarvesters)
            {
                iceAgent.addCandidateHarvester(harvester);
            }
        }

        iceAgent.setUseHostHarvester(false);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close()
    {
        if (!closed)
        {
            // Set this early to prevent double closing when the last channel
            // is removed.
            closed = true;

            if (iceStream != null)
            {
                iceStream.removePairStateChangeListener(
                        iceStreamPairChangeListener);
            }
            if (iceAgent != null)
            {
                iceAgent.removeStateChangeListener(iceStateChangeListener);
                iceAgent.free();
                iceAgent = null;
            }

            // TODO do we have any sockets left to close, or does Component
            // take care of that?
        }
    }

    /**
     * Initializes a new <tt>Agent</tt> instance which implements the ICE
     * protocol and which is to be used by this instance to implement the Jingle
     * ICE-UDP transport.
     *
     * @param controlling
     * @param streamName
     * @return a new <tt>Agent</tt> instance which implements the ICE protocol
     * and which is to be used by this instance to implement the Jingle ICE-UDP
     * transport
     * @throws IOException if initializing a new <tt>Agent</tt> instance for the
     * purposes of this <tt>TransportManager</tt> fails
     */
    private Agent createIceAgent(
            boolean controlling, String streamName, ConfigurationService cfg)
            throws IOException
    {
        Agent iceAgent = new Agent(iceUfragPrefix, logger);

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        configureHarvesters(iceAgent, cfg);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);

        IceMediaStream iceStream
                = iceAgent.createMediaStream(streamName);

        // We're not using dynamic ports.
        iceAgent.createComponent(
                iceStream, Transport.UDP,
                -1, -1, -1,
                keepAliveStrategy,
                useComponentSocket);

        return iceAgent;
    }

    /**
     * @return the URL to advertise for COLIBRI WebSocket connections for this
     * transport manager.
     */
    private String getColibriWsUrl()
    {
        ColibriWebSocketService colibriWebSocketService
            = ServiceUtils2.getService(
                    bundleContext, ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
        {
            return colibriWebSocketService.getColibriWebSocketUrl(
                conferenceId,
                endpointId,
                iceAgent.getLocalPassword());
        }

        return null;
    }

    /**
     * Gets the ICE password.
     */
    String getIcePassword()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalPassword();
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
            //TODO(brian): touch activity in new scheme here
            // TODO we might not necessarily want to keep all channels alive by
            // the ICE connection.
            // Boris: I think we should do it for all channels, because it's
            // limited to ActivityType.TRANSPORT
//            getChannels().forEach(
//                channel -> channel.touch(Channel.ActivityType.TRANSPORT));
        }
    }

    /**
     * Returns {@code true} if this {@link IceTransport} has connected. It is
     * considered connected once the ICE connection is extablished.
     */
    public boolean isConnected()
    {
        return iceConnected;
    }

    /**
     * {@inheritDoc}
     * @param transportPacketExtension
     */
    public void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transportPacketExtension)
    {
        if (iceAgent.getState().isEstablished())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix + "Connection already established.");
            }
            return;
        }

        // Set the remote ufrag/password
        if (transportPacketExtension.getUfrag() != null)
        {
            iceStream.setRemoteUfrag(transportPacketExtension.getUfrag());
        }
        if (transportPacketExtension.getPassword() != null)
        {
            iceStream.setRemotePassword(transportPacketExtension.getPassword());
        }

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning
                = IceProcessingState.RUNNING.equals(iceAgent.getState());

        List<CandidatePacketExtension> remoteCandidates
                = transportPacketExtension.getChildExtensionsOfType(
                        CandidatePacketExtension.class);
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty()) {
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix +
                        "Ignoring transport extension with no candidates, "
                        + "the Agent is already running.");
            }
            return;
        }

        int remoteCandidateCount
                = addRemoteCandidates(remoteCandidates, iceAgentStateIsRunning);

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
                iceComponent.updateRemoteCandidates();
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
            if (iceComponent.getRemoteCandidateCount() >= 1)
            {
                logger.info(logPrefix +
                        "Starting the agent with remote candidates.");
                iceAgent.startConnectivityEstablishment();
            }
        }
        else if (iceStream.getRemoteUfrag() != null
                && iceStream.getRemotePassword() != null)
        {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.info(logPrefix +
                    "Starting the Agent without remote candidates. ");
            iceAgent.startConnectivityEstablishment();
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix +
                        " Not starting ICE, no ufrag and pwd yet. " +
                        transportPacketExtension.toXML());
            }
        }

    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
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

            // XXX IceTransport harvests host candidates only and the
            // ICE Components utilize the UDP protocol/transport only at the
            // time of this writing. The ice4j library will, of course, check
            // the theoretical reachability between the local and the remote
            // candidates. However, we would like (1) to not mess with a
            // possibly running iceAgent and (2) to return a consistent return
            // value.
            if (!TransportUtils.canReach(component, remoteCandidate))
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
     * Describes this {@link IceTransport} in a {@code transport} XML extension.
     */
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        pe.setPassword(iceAgent.getLocalPassword());
        pe.setUfrag(iceAgent.getLocalUfrag());
        List<LocalCandidate> localCandidates = iceComponent.getLocalCandidates();
        if (localCandidates != null)
        {
            localCandidates.forEach(
                    localCandidate -> describe(localCandidate, pe));
        }
        pe.addChildExtension(new RtcpmuxPacketExtension());

        String colibriWsUrl = getColibriWsUrl();
        if (colibriWsUrl != null)
        {
            WebSocketPacketExtension wsPacketExtension
                    = new WebSocketPacketExtension(colibriWsUrl);
            pe.addChildExtension(wsPacketExtension);
        }
    }

    /**
     * Describes a specific {@link LocalCandidate} in a {@code transport} XML
     * extension.
     * @param candidate the candidate.
     * @param pe the XML extension.
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
     * Generates an ID for a specific local ICE candidate.
     * @param candidate the candidate.
     */
    private String generateCandidateID(LocalCandidate candidate)
    {
        StringBuilder candidateID = new StringBuilder();

        candidateID.append(Long.toHexString(hashCode()));

        Agent iceAgent
                = candidate.getParentComponent().getParentStream().getParentAgent();

        candidateID.append(Long.toHexString(iceAgent.hashCode()));
        candidateID.append(Long.toHexString(iceAgent.getGeneration()));
        candidateID.append(Long.toHexString(candidate.hashCode()));

        return candidateID.toString();
    }

    /**
     * Handles a change of the ICE processing of our ICE {@link Agent}.
     */
    private void iceStateChange(PropertyChangeEvent ev)
    {
        IceProcessingState oldState = (IceProcessingState) ev.getOldValue();
        IceProcessingState newState = (IceProcessingState) ev.getNewValue();

        logger.info(logPrefix + "ICE state changed old=" +
                oldState + " new=" + newState);

        // We should be using newState.isEstablished() here, but we see
        // transitions from RUNNING to COMPLETED, which should not happen and
        // when they happen the connection is not successful. So we handle that
        // case separately below.
        if (IceProcessingState.COMPLETED.equals(newState))
        {
            if (!iceConnected)
            {
                iceConnected = true;
                onIceConnected();
            }
        }
        else if (IceProcessingState.FAILED.equals(newState)
            || (IceProcessingState.RUNNING.equals(oldState)
                    && IceProcessingState.TERMINATED.equals(newState)))
        {
            if (!iceFailed)
            {
                iceFailed = true;
                onIceFailed();
            }
        }
    }

    /**
     * Called once if ICE completes successfully.
     */
    protected void onIceConnected()
    {
    }

    /**
     * Called once if the ICE agent fails.
     */
    protected void onIceFailed()
    {
        logger.warn(logPrefix + "ICE failed!");
    }

    /**
     * @return {@code true} if ICE has run and failed.
     */
    boolean hasIceFailed()
    {
        return iceFailed;
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.ChannelBundle</tt>
     * to the values of the respective properties of this instance. Thus, the
     * specified <tt>iq</tt> may be thought of as containing a description of
     * this instance.
     *
     * @param iq the <tt>ColibriConferenceIQ.Channel</tt> on which to set the
     * values of the properties of this instance
     */
    public void describe(ColibriConferenceIQ.ChannelBundle iq)
    {
        IceUdpTransportPacketExtension pe = iq.getTransport();
        if (pe == null)
        {
            pe = new IceUdpTransportPacketExtension();
            iq.setTransport(pe);
        }

        describe(pe);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("useComponentSocket", useComponentSocket);
        debugState.put("keepAliveStrategy", keepAliveStrategy.toString());
        debugState.put("closed", closed);
        debugState.put("iceConnected", iceConnected);
        debugState.put("iceFailed", iceFailed);

        final Agent iceAgent = this.iceAgent;
        if (iceAgent != null)
        {
            // Actual agent's ICE control role might differ from initial role
            // due to ICE control role conflict resolution during connection
            // establishment.
            debugState.put("controlling", iceAgent.isControlling());
        }
        return debugState;
    }
}
