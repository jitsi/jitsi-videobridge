/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import kotlin.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.socket.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.dtls.*;
import org.jitsi.nlj.transform.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.transform.node.outgoing.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.transport.*;
import org.jitsi.videobridge.util.*;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/**
 * @author bbaldino
 */
public class IceDtlsTransportManager
    extends IceUdpTransportManager
{
    /**
     * The {@link Logger} used by the {@link IceDtlsTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
            = Logger.getLogger(IceDtlsTransportManager.class);
    private final Logger logger;
    private DtlsClientStack dtlsStack = new DtlsClientStack();
    private DtlsReceiver dtlsReceiver = new DtlsReceiver(dtlsStack);
    private DtlsSender dtlsSender = new DtlsSender(dtlsStack);
    //TODO(brian): these 2 subscriber lists should be combined into a sort of 'transportmanagereventhandler' interface
    // but, what about thingds like dtlsConnected which only appliesd to IceDtlsTransportManager?
    private List<Runnable> transportConnectedSubscribers = new ArrayList<>();
    private List<Runnable> dtlsConnectedSubscribers = new ArrayList<>();
    private final PacketInfoQueue outgoingPacketQueue;
    private Endpoint endpoint = null;
    class SocketSenderNode extends Node {
        public DatagramSocket socket = null;
        SocketSenderNode() {
            super("Socket sender");
        }
        @Override
        protected void doProcessPackets(@NotNull List<PacketInfo> pkts)
        {
            if (socket != null) {
                pkts.forEach(pktInfo -> {
                    try
                    {
                        socket.send(new DatagramPacket(pktInfo.getPacket().getBuffer().array(), 0, pktInfo.getPacket().getBuffer().limit()));
                    } catch (IOException e)
                    {
                        System.out.println("BRIAN: error sending outgoing dtls packet: " + e.toString());
                        throw new RuntimeException(e);
                    }
                });
            }

        }
    }
    private SocketSenderNode packetSender = new SocketSenderNode();

    private Node incomingPipelineRoot = createIncomingPipeline();
    private Node outgoingDtlsPipelineRoot = createOutgoingDtlsPipeline();
    private Node outgoingSrtpPipelineRoot = createOutgoingSrtpPipeline();
    protected boolean dtlsHandshakeComplete = false;

    public IceDtlsTransportManager(String id, Conference conference)
            throws IOException
    {
        super(conference, true, id);
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
        iceAgent.addStateChangeListener(this::iceAgentStateChange);

        outgoingPacketQueue = new PacketInfoQueue(id, TaskPools.IO_POOL, this::handleOutgoingPacket);
        logger.info("BRIAN: finished IceDtlsTransportManager ctor");
    }

    //TODO: need to take another look and make sure we're properly replicating all the behavior of this
    // method in IceUdpTransportManager
    @Override
    public void startConnectivityEstablishment(IceUdpTransportPacketExtension remoteTransportInformation)
    {
        if (iceAgent.getState().isEstablished()) {
            logger.info(id + " with local ufrag " + iceAgent.getLocalUfrag() +
                    " is already established, not restarting");
            return;
        }
        logger.info(id + " with local ufrag " + iceAgent.getLocalUfrag() +
                " starting connectivity establishment with extension: " + remoteTransportInformation.toXML());

        // Get the remote fingerprints and set them in the DTLS stack so we
        // can verify the remove certificate later.
        // TODO(boris): read the Setup attribute and support acting like the
        // DTLS server.
        List<DtlsFingerprintPacketExtension> fingerprintExtensions
                = remoteTransportInformation.getChildExtensionsOfType(DtlsFingerprintPacketExtension.class);

        Map<String, String> remoteFingerprints = new HashMap<>();
        fingerprintExtensions.forEach(fingerprintExtension -> {
            if (fingerprintExtension.getHash() != null && fingerprintExtension.getFingerprint() != null) {
                logger.debug("Adding fingerprint " + fingerprintExtension.getHash() +
                        " -> " + fingerprintExtension.getFingerprint());
                remoteFingerprints.put(fingerprintExtension.getHash(), fingerprintExtension.getFingerprint());
            } else {
                logger.debug("Ignoring empty DtlsFingerprint extension");
            }
        });

        if (remoteFingerprints.isEmpty()) {
            // Don't pass an empty list to the stack in order to avoid wiping
            // certificates that were contained in a previous request.
            logger.debug(id + " with local ufrag " + iceAgent.getLocalUfrag() +
                    " empty transport extension");
        }
        else
        {
            dtlsStack.setRemoteFingerprints(remoteFingerprints);
        }

        // Set the remote ufrag/password
        if (remoteTransportInformation.getUfrag() != null)
        {
            iceStream.setRemoteUfrag(remoteTransportInformation.getUfrag());
        }
        if (remoteTransportInformation.getPassword() != null)
        {
            iceStream.setRemotePassword(remoteTransportInformation.getPassword());
        }

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning
                = IceProcessingState.RUNNING.equals(iceAgent.getState());

        List<CandidatePacketExtension> remoteCandidates
                = remoteTransportInformation.getChildExtensionsOfType(CandidatePacketExtension.class);
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty()) {
            logger.info("ICE agent is already running and this extension contained no new candidates, returning");
            return;
        }

        int remoteCandidateCount = addRemoteCandidates(remoteCandidates, iceAgentStateIsRunning);

        if (iceAgentStateIsRunning) {
            if (remoteCandidateCount == 0) {
                // XXX Effectively, the check above but realizing that all
                // candidates were ignored:
                // iceAgentStateIsRunning && candidates.isEmpty().
            } else {
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
     *
     * @param candidates
     * @param iceAgentStateIsRunning
     * @return the number of network reachable remote candidates contained in the given list of candidates
     *
     * NOTE(brian): Almost the same as the one that was in IceUdpTransportManager, but we don't use the iceStream
     * member (we get the stream dynamically) and we always assume rtcpmux
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

            // XXX IceUdpTransportManager harvests host candidates only and the
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

    public void setEndpoint(Endpoint endpoint)
    {
        //TODO(brian): i think eventually we'll have the endpoint create its transport manager,
        // which case we can just pass it in via the ctor (assuming we want to expose the entire
        // endpoint.  maybe there's a more limited interface we can expose to the transportmanager?)
        this.endpoint = endpoint;
    }

    @Override
    public boolean isConnected()
    {
        return iceAgent.getState().isEstablished();
    }

    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        pe.setPassword(iceAgent.getLocalPassword());
        pe.setUfrag(iceAgent.getLocalUfrag());
        iceStream.getComponents().forEach(component -> {
            List<LocalCandidate> localCandidates = component.getLocalCandidates();
            if (localCandidates != null) {
                localCandidates.forEach(localCandidate -> {
                    describe(localCandidate, pe);
                });
            }
        });
        pe.addChildExtension(new RtcpmuxPacketExtension());

        // Describe dtls
        DtlsFingerprintPacketExtension fingerprintPE
                = pe.getFirstChildOfType(
                DtlsFingerprintPacketExtension.class);
        if (fingerprintPE == null) {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            pe.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(dtlsStack.getLocalFingerprint());
        fingerprintPE.setHash(dtlsStack.getLocalFingerprintHashFunction());
        fingerprintPE.setSetup("ACTPASS");
        //TODO(brian): need to include the Colibiri websocket url when describing
        // (see IceUdpTransportManager#describe and #getColibriWsUrl)
        //TODO(brian): also in IceUdpTransportManager#describe, look at #generateCandidateID, do
        // we need this?
    }

    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    @Override
    public void onTransportConnected(Runnable handler)
    {
        if (isConnected()) {
            handler.run();
        } else {
            transportConnectedSubscribers.add(handler);
        }
    }

    public void onDtlsHandshakeComplete(Runnable handler)
    {
        if (dtlsHandshakeComplete)
        {
            handler.run();
        }
        else
        {
            dtlsConnectedSubscribers.add(handler);
        }
    }

    private Node createIncomingPipeline() {
        PipelineBuilder builder = new PipelineBuilder();

        DemuxerNode dtlsSrtpDemuxer = new DemuxerNode("DTLS/SRTP");
        // DTLS path
        ConditionalPacketPath dtlsPath = new ConditionalPacketPath();
        dtlsPath.setName("DTLS path");
        dtlsPath.setPredicate((packet) -> {
            int b = packet.getBuffer().get(0) & 0xFF;
            return (b >= 20 && b <= 63);
        });
        PipelineBuilder dtlsPipelineBuilder = new PipelineBuilder();
        dtlsPipelineBuilder.node(dtlsReceiver);
        dtlsPipelineBuilder.simpleNode("sctp app packet handler", packets -> {
            packets.forEach(endpoint::dtlsAppPacketReceived);

            return Collections.emptyList();
        });
        dtlsPath.setPath(dtlsPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(dtlsPath);

        // SRTP path
        ConditionalPacketPath srtpPath = new ConditionalPacketPath();
        srtpPath.setName("SRTP path");
        srtpPath.setPredicate(packet -> {
            int b = packet.getBuffer().get(0) & 0xFF;
            return (b < 20 || b > 63);
        });
        PipelineBuilder srtpPipelineBuilder = new PipelineBuilder();
        srtpPipelineBuilder.simpleNode("SRTP path", packetInfos -> {
            packetInfos.forEach( pktInfo -> {
                endpoint.srtpPacketReceived(pktInfo);
            });
            return Collections.emptyList();
        });
        srtpPath.setPath(srtpPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(srtpPath);

        builder.node(dtlsSrtpDemuxer);
        return builder.build();
    }

    private Node createOutgoingDtlsPipeline() {
        PipelineBuilder builder = new PipelineBuilder();
        builder.node(dtlsSender);
        builder.node(packetSender);
        return builder.build();
    }

    private Node createOutgoingSrtpPipeline()
    {
        PipelineBuilder builder = new PipelineBuilder();
        builder.node(packetSender);
        return builder.build();
    }

    public void sendDtlsData(PacketInfo packet) {
        outgoingDtlsPipelineRoot.processPackets(Collections.singletonList(packet));
    }

    private boolean handleOutgoingPacket(PacketInfo packetInfo)
    {
        outgoingSrtpPipelineRoot.processPackets(Collections.singletonList(packetInfo));
        return true;
    }

    /**
     * Read packets from the given socket and process them in the incoming pipeline
     * @param socket the socket to read from
     */
    private void installIncomingPacketReader(DatagramSocket socket) {
        //TODO(brian): this does a bit more than just read from the iceSocket (does a bit of processing
        // for each packet) but I think it's little enough (it'll only be a bit of the DTLS path)
        // that running it in the IO pool is fine
        TaskPools.IO_POOL.submit(() -> {
            byte[] buf = new byte[1500];
            while (!closed) {
                DatagramPacket p = new DatagramPacket(buf, 0, 1500);
                try
                {
                    socket.receive(p);
                    ByteBuffer packetBuf = ByteBuffer.allocate(p.getLength());
                    packetBuf.put(ByteBuffer.wrap(buf, 0, p.getLength())).flip();
                    Packet pkt = new UnparsedPacket(packetBuf);
                    PacketInfo pktInfo = new PacketInfo(pkt);
                    pktInfo.setReceivedTime(System.currentTimeMillis());
                    incomingPipelineRoot.processPackets(Collections.singletonList(pktInfo));
                }
                catch (SocketClosedException e)
                {
                    logger.info("Socket closed for local ufrag " + iceAgent.getLocalUfrag() + ", stopping reader");
                    break;
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    break;
                }
            }
        });
    }

    private boolean iceConnectedProcessed = false;

    private void onIceConnected() {
        iceConnected = true;
        if (iceConnectedProcessed) {
            return;
        }
        // The sctp connection start is triggered by this, but otherwise i don't think we need it (the other channel
        // types no longer do anything needed in there)
        logger.info("BRIAN: iceConnected for transport manager " + id);
        transportConnectedSubscribers.forEach(Runnable::run);
        iceConnectedProcessed = true;
        DatagramSocket socket = iceStream.getComponents().get(0).getSocket();

        endpoint.setOutgoingSrtpPacketHandler(packets -> packets.forEach(outgoingPacketQueue::add));

        // Socket reader thread.  Read from the underlying iceSocket and pass to the incoming
        // module chain
        installIncomingPacketReader(socket);

        dtlsStack.onHandshakeComplete((tlsContext) -> {
            dtlsHandshakeComplete = true;
            logger.info("TransportManager " + id + " DTLS handshake complete.  Got SRTP profile " +
                    dtlsStack.getChosenSrtpProtectionProfile());
            //TODO: emit this as part of the dtls handshake complete event?
            endpoint.setSrtpInformation(dtlsStack.getChosenSrtpProtectionProfile(), tlsContext);
            dtlsConnectedSubscribers.forEach(Runnable::run);
            return Unit.INSTANCE;
        });

        packetSender.socket = socket;
        logger.info("BRIAN: transport manager " + this.hashCode() + " starting dtls");
        TaskPools.IO_POOL.submit(() -> {
            try {
                dtlsStack.connect();
            }
            catch (Exception e)
            {
                logger.error("Error during dtls negotiation: " + e.toString() + ", closing this transport manager");
                close();
            }
        });
    }

    private void iceAgentStateChange(PropertyChangeEvent ev)
    {
        IceProcessingState oldState = (IceProcessingState) ev.getOldValue();
        IceProcessingState newState = (IceProcessingState) ev.getNewValue();

        logger.info(Logger.Category.STATISTICS,
                "BRIAN: ice_state_change,"
                        + " old_state=" + oldState
                        + ",new_state=" + newState);
        //TODO(brian): this isn't accurate enough to know that we connected.  It's possible we go from RUNNING to
        // TERMINATED and isEstablished returns true for TERMINATED no matter what.  is there something better
        // we can key off of?
//        if (newState.isEstablished()) {
        if (IceProcessingState.COMPLETED.equals(newState))
        {
            logger.info("BRIAN: local ufrag " + iceAgent.getLocalUfrag() + " ICE connected, need to start dtls");
            onIceConnected();
        }
        else if (IceProcessingState.FAILED.equals(newState))
        {
            logger.info("BRIAN: ICE failed, local ufrag " + iceAgent.getLocalUfrag());
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

    @Override
    public synchronized void close()
    {
        logger.info("Closing Transport manager " + id);
        if (iceAgent != null) {
            iceAgent.removeStateChangeListener(this::iceAgentStateChange);
        }
        super.close();
        logger.info("Closed transport manager " + id);
    }
}
