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
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.osgi.framework.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
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
     * The ICE <tt>Component</tt> IDs in their common order used, for example,
     * by <tt>DefaultStreamConnector</tt>, <tt>MediaStreamTarget</tt>.
     */
    private static final int[] MEDIA_COMPONENT_IDS
        = new int[] { Component.RTP, Component.RTCP };

    /**
     * Single component ID used for DATA media type.
     */
    private static final int[] DATA_COMPONENT_IDS
        = new int[] { Component.RTP };

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
     * harvesting should be forced without first trying to auto dectect it.
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
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        logger.debug(s);
    }

    /**
     * The <tt>Agent</tt> which implements the ICE protocol and which is used
     * by this instance to implement the Jingle ICE-UDP transport.
     */
    private final Agent iceAgent;

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
     * The <tt>StreamConnector</tt> that represents the datagram sockets
     * allocated by this instance for the purposes of RTP and RTCP transmission.
     */
    private StreamConnector streamConnector;

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param channel the <tt>Channel</tt> which is initializing the new
     * instance
     */
    public IceUdpTransportManager(Channel channel)
        throws IOException
    {
        super(channel);

        iceAgent = createIceAgent();
        iceAgent.addStateChangeListener(iceAgentStateChangeListener);
        iceStream = iceAgent.getStream(getChannel().getContent().getName());
        iceStream.addPairChangeListener(iceStreamPairChangeListener);
    }

    /**
     * Returns an array of ICE media stream component IDs relevant to the
     * <tt>MediaType</tt> configured in this instance.
     * @return an array of ICE media stream component IDs relevant to the
     *         <tt>MediaType</tt> configured in this instance.
     */
    protected int[] getComponentIDs()
    {
        return getChannel().getContent().getMediaType() == MediaType.DATA
            ? DATA_COMPONENT_IDS : MEDIA_COMPONENT_IDS;
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
     */
    @Override
    protected void channelPropertyChange(PropertyChangeEvent ev)
    {
        super.channelPropertyChange(ev);

        if (Channel.INITIATOR_PROPERTY.equals(ev.getPropertyName())
                && (iceAgent != null))
        {
            Channel channel = (Channel) ev.getSource();

            iceAgent.setControlling(channel.isInitiator());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        super.close();

        if (iceStream != null)
        {
            iceStream.removePairStateChangeListener(
                    iceStreamPairChangeListener);
        }
        if (iceAgent != null)
        {
            iceAgent.removeStateChangeListener(iceAgentStateChangeListener);
            iceAgent.free();
        }
    }

    /**
     * Closes {@link #streamConnector} (if it exists).
     */
    private synchronized void closeStreamConnector()
    {
        if (streamConnector != null)
        {
            streamConnector.close();
            streamConnector = null;
        }
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
    private Agent createIceAgent()
        throws IOException
    {
        Channel channel = getChannel();
        Content content = channel.getContent();
        NetworkAddressManagerService nams
            = ServiceUtils.getService(
                    getBundleContext(), NetworkAddressManagerService.class);
        Agent iceAgent = nams.createIceAgent();

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        appendVideobridgeHarvesters(iceAgent);
        iceAgent.setControlling(channel.isInitiator());
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

        PortTracker portTracker
            = JitsiTransportManager.getPortTracker(content.getMediaType());

        // Audio and Video uses RTP + RTCP components,
        // Data uses single single component.

        IceMediaStream iceStream
            = nams.createIceStream(
                    getComponentIDs().length,
                    portTracker.getPort(),
                    /* streamName */ content.getName(),
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
     * that this {@link TransportManager} is servicing. The method is a
     * convenience which gets the <tt>BundleContext</tt> associated with the
     * XMPP component implementation in which the <tt>Videobridge</tt>
     * associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this
     * <tt>IceUdpTransportManager</tt>
     */
    public BundleContext getBundleContext()
    {
        return getChannel().getBundleContext();
    }

    /**
     * Initializes a new <tt>StreamConnector</tt> instance that represents the
     * datagram sockets allocated and negotiated by {@link #iceAgent} for the
     * purposes of RTCP and RTP transmission.
     *
     * @return a new <tt>StreamConnector</tt> instance that represents the
     * datagram sockets allocated and negotiated by <tt>iceAgent</tt> for the
     * purposes of RTCP and RTP transmission. If <tt>iceAgent</tt> has not
     * completed yet or has failed, <tt>null</tt>.
     */
    private StreamConnector createStreamConnector()
    {
        DatagramSocket[] streamConnectorSockets = getStreamConnectorSockets();

        return
            (streamConnectorSockets == null)
                ? null
                : new DefaultStreamConnector(
                        streamConnectorSockets[0 /* RTP */],
                        /* RTCP(null for DATA media type) */
                        streamConnectorSockets.length > 1
                            ? streamConnectorSockets[1] : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        pe.setPassword(iceAgent.getLocalPassword());
        pe.setUfrag(iceAgent.getLocalUfrag());

        IceMediaStream stream
            = iceAgent.getStream(getChannel().getContent().getName());

        if (stream != null)
        {
            for (org.ice4j.ice.Component component : stream.getComponents())
            {
                List<LocalCandidate> candidates
                    = component.getLocalCandidates();

                if ((candidates != null) && !candidates.isEmpty())
                {
                    for (LocalCandidate candidate : candidates)
                        describe(candidate, pe);
                }
            }
        }
    }

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
     * Generates an ID to be set on a <tt>CandidatePacketExntension</tt> to
     * represent a specific <tt>LocalCandidate</tt>.
     *
     * @param candidate the <tt>LocalCandidate</tt> whose ID is to be generated
     * @return an ID to be set on a <tt>CandidatePacketExtension</tt> to
     * represent the specified <tt>candidate</tt>
     */
    private String generateCandidateID(LocalCandidate candidate)
    {
        StringBuilder candidateID = new StringBuilder();
        Channel channel = getChannel();
        Content content = channel.getContent();
        Conference conference = content.getConference();

        candidateID.append(conference.getID());
        candidateID.append(Long.toHexString(content.hashCode()));
        candidateID.append(Long.toHexString(channel.hashCode()));

        Agent iceAgent
            = candidate.getParentComponent().getParentStream().getParentAgent();

        candidateID.append(Long.toHexString(iceAgent.hashCode()));
        candidateID.append(Long.toHexString(iceAgent.getGeneration()));
        candidateID.append(Long.toHexString(candidate.hashCode()));

        return candidateID.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized StreamConnector getStreamConnector()
    {
        StreamConnector streamConnector = this.streamConnector;

        /*
         * Make sure that the returned StreamConnector is up-to-date with the
         * iceAgent.
         */
        if (streamConnector != null)
        {
            DatagramSocket[] streamConnectorSockets
                = getStreamConnectorSockets();

            if ((streamConnectorSockets != null)
                    && ((streamConnector.getDataSocket()
                                    != streamConnectorSockets[0 /* RTP */])
                    || (streamConnectorSockets.length == 2 /* RTCP */
                            && streamConnector.getControlSocket()
                                    != streamConnectorSockets[1 ])))
            {
                // Recreate the streamConnector.
                closeStreamConnector();
            }
        }

        if (this.streamConnector == null)
            this.streamConnector = createStreamConnector();
        return this.streamConnector;
    }

    /**
     * Gets an array of <tt>DatagramSocket</tt>s which represents the sockets to
     * be used by the <tt>StreamConnector</tt> of this instance in the order of
     * {@link #MEDIA_COMPONENT_IDS} if {@link #iceAgent} has completed.
     *
     * @return an array of <tt>DatagramSocket</tt>s which represents the sockets
     * to be used by the <tt>StreamConnector</tt> of this instance in the order
     * of {@link #MEDIA_COMPONENT_IDS} if {@link #iceAgent} has completed; otherwise,
     * <tt>null</tt>
     */
    private DatagramSocket[] getStreamConnectorSockets()
    {
        IceMediaStream stream
            = iceAgent.getStream(getChannel().getContent().getName());

        if (stream != null)
        {
            int [] componentIds = getComponentIDs();
            DatagramSocket[] streamConnectorSockets
                = new DatagramSocket[componentIds.length];
            int streamConnectorSocketCount = 0;

            for (int i = 0; i < componentIds.length; i++)
            {
                Component component = stream.getComponent(componentIds[i]);

                if (component != null)
                {
                    CandidatePair selectedPair = component.getSelectedPair();

                    if (selectedPair != null)
                    {
                        DatagramSocket streamConnectorSocket
                            = selectedPair
                                .getLocalCandidate()
                                    .getDatagramSocket();

                        if (streamConnectorSocket != null)
                        {
                            streamConnectorSockets[i] = streamConnectorSocket;
                            streamConnectorSocketCount++;
                        }
                    }
                }
            }
            if (streamConnectorSocketCount > 0)
                return streamConnectorSockets;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTarget getStreamTarget()
    {
        IceMediaStream stream
            = iceAgent.getStream(getChannel().getContent().getName());
        MediaStreamTarget streamTarget = null;

        if (stream != null)
        {
            int[] componentIds = getComponentIDs();

            InetSocketAddress[] streamTargetAddresses
                = new InetSocketAddress[componentIds.length];
            int streamTargetAddressCount = 0;

            for (int i = 0; i < componentIds.length; i++)
            {
                Component component = stream.getComponent(componentIds[i]);

                if (component != null)
                {
                    CandidatePair selectedPair = component.getSelectedPair();

                    if (selectedPair != null)
                    {
                        InetSocketAddress streamTargetAddress
                            = selectedPair
                                .getRemoteCandidate()
                                    .getTransportAddress();

                        if (streamTargetAddress != null)
                        {
                            streamTargetAddresses[i] = streamTargetAddress;
                            streamTargetAddressCount++;
                        }
                    }
                }
            }
            if (streamTargetAddressCount > 0)
            {
                if(componentIds.length == 2)
                {
                    streamTarget
                        = new MediaStreamTarget(
                                streamTargetAddresses[0 /* RTP */],
                                streamTargetAddresses[1 /* RTCP */]);
                }
                else
                {
                    streamTarget
                        = new MediaStreamTarget(
                            streamTargetAddresses[0 /* RTP */],
                            null);
                }
            }
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
            Channel channel = getChannel();
            Content content = channel.getContent();
            Conference conference = content.getConference();

            logd(
                    "ICE processing state of " + getClass().getSimpleName()
                        + " #" + Integer.toHexString(hashCode())
                        + " of channel " + channel.getID() + " of content "
                        + content.getName() + " of conference "
                        + conference.getID() + " changed from "
                        + ev.getOldValue() + " to " + ev.getNewValue() + ".");
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
            getChannel().touch();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean startConnectivityEstablishment(
            IceUdpTransportPacketExtension transport)
    {
        /*
         * If ICE is running already, we try to update the checklists with the
         * candidates. Note that this is a best effort.
         */
        boolean iceAgentStateIsRunning
            = IceProcessingState.RUNNING.equals(iceAgent.getState());
        int remoteCandidateCount = 0;

        {
            String media = getChannel().getContent().getName();
            IceMediaStream stream = iceAgent.getStream(media);

            // Different stream may have different ufrag/password
            String ufrag = transport.getUfrag();

            if (ufrag != null)
                stream.setRemoteUfrag(ufrag);

            String password = transport.getPassword();

            if (password != null)
                stream.setRemotePassword(password);

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

                Component component
                    = stream.getComponent(candidate.getComponent());
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
    public void wrapupConnectivityEstablishment()
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
            while (IceProcessingState.RUNNING.equals(iceAgent.getState()))
            {
                try
                {
                    syncRoot.wait(1000);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
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
