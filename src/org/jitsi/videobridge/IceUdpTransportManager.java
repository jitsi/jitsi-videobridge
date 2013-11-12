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
import org.jitsi.service.neomedia.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 */
public class IceUdpTransportManager
    extends TransportManager
{
    /**
     * The ICE <tt>Component</tt> IDs in their common order used, for example,
     * by <tt>DefaultStreamConnector</tt>, <tt>MediaStreamTarget</tt>.
     */
    private static final int[] COMPONENT_IDS
        = new int[] { Component.RTP, Component.RTCP };

    /**
     * The <tt>Agent</tt> which implements the ICE protocol and which is used
     * by this instance to implement the Jingle ICE-UDP transport.
     */
    private final Agent iceAgent;

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

        if (iceAgent != null)
            iceAgent.free();
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
                    content
                        .getConference()
                            .getVideobridge()
                                .getComponent()
                                    .getBundleContext(),
                    NetworkAddressManagerService.class);
        Agent iceAgent = nams.createIceAgent();

        iceAgent.setControlling(channel.isInitiator());

        // createIceStream
        PortTracker portTracker
            = JitsiTransportManager.getPortTracker(content.getMediaType());
        IceMediaStream iceStream
            = nams.createIceStream(
                    portTracker.getPort(),
                    /* streamName */ content.getName(),
                    iceAgent);

        // Attempt to minimize subsequent bind retries.
        try
        {
            portTracker.setNextPort(
                    1
                        + iceStream
                            .getComponent(Component.RTCP)
                                .getLocalCandidates()
                                    .get(0)
                                        .getTransportAddress()
                                            .getPort());
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }

        return iceAgent;
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
                        streamConnectorSockets[1 /* RTCP */]);
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
        candidatePE.setFoundation(Integer.parseInt(candidate.getFoundation()));
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
        candidateID.append(channel.getID());

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
                            || (streamConnector.getControlSocket()
                                    != streamConnectorSockets[1 /* RTCP */])))
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
     * {@link #COMPONENT_IDS} if {@link #iceAgent} has completed.
     *
     * @return an array of <tt>DatagramSocket</tt>s which represents the sockets
     * to be used by the <tt>StreamConnector</tt> of this instance in the order
     * of {@link #COMPONENT_IDS} if {@link #iceAgent} has completed; otherwise,
     * <tt>null</tt>
     */
    private DatagramSocket[] getStreamConnectorSockets()
    {
        IceMediaStream stream
            = iceAgent.getStream(getChannel().getContent().getName());

        if (stream != null)
        {
            DatagramSocket[] streamConnectorSockets
                = new DatagramSocket[COMPONENT_IDS.length];
            int streamConnectorSocketCount = 0;

            for (int i = 0; i < COMPONENT_IDS.length; i++)
            {
                Component component = stream.getComponent(COMPONENT_IDS[i]);

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
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
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
        boolean startConnectivityEstablishment = false;

        {
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

            String media = getChannel().getContent().getName();
            IceMediaStream stream = iceAgent.getStream(media);

            // Different stream may have different ufrag/password
            String ufrag = transport.getUfrag();

            if (ufrag != null)
                stream.setRemoteUfrag(ufrag);

            String password = transport.getPassword();

            if (password != null)
                stream.setRemotePassword(password);

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
                            Integer.toString(candidate.getFoundation()),
                            candidate.getPriority(),
                            relatedCandidate);

                if (iceAgentStateIsRunning)
                {
                    component.addUpdateRemoteCandidates(remoteCandidate);
                }
                else
                {
                    component.addRemoteCandidate(remoteCandidate);
                    startConnectivityEstablishment = true;
                }
            }
        }

        if (iceAgentStateIsRunning)
        {
            // update all components of all streams
            for (IceMediaStream stream : iceAgent.getStreams())
            {
                for (Component component : stream.getComponents())
                    component.updateRemoteCandidates();
            }
        }
        else if (startConnectivityEstablishment)
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
                        startConnectivityEstablishment = false;
                        break;
                    }
                }
                if (!startConnectivityEstablishment)
                    break;
            }
            if (startConnectivityEstablishment)
                iceAgent.startConnectivityEstablishment();
        }

        return iceAgentStateIsRunning || startConnectivityEstablishment;
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
