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
import org.bouncycastle.crypto.tls.*;
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
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author bbaldino
 */
public class IceDtlsTransportManager
    extends IceUdpTransportManager
{
    private static final Logger logger
            = Logger.getLogger(IceDtlsTransportManager.class);
    private static final String ICE_STREAM_NAME = "ice-stream-name";
    //TODO: we use this for a few different things (dtls connect, socket read, socket write).  do we need it?
    private ExecutorService executor = Executors.newCachedThreadPool(new NameableThreadFactory("Transport manager threadpool"));
    private DtlsStack dtlsStack = new DtlsClientStack();
    private DtlsReceiverNode dtlsReceiver = new DtlsReceiverNode();
    //TODO: temp store dtls transport because newsctpconnection grabs it
    DTLSTransport dtlsTransport;
    LinkedBlockingQueue<PacketInfo> sctpAppPackets = new LinkedBlockingQueue<>();
    private Transceiver transceiver = null;
    private DtlsSenderNode dtlsSender = new DtlsSenderNode();
    class SocketSenderNode extends Node {
        public MultiplexingDatagramSocket socket = null;
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

    public IceDtlsTransportManager(Conference conference)
            throws IOException
    {
        super(conference, true, 1, ICE_STREAM_NAME, null);
        iceAgent.addStateChangeListener(this::iceAgentStateChange);
        logger.info("BRIAN: finished IceDtlsTransportManager ctor");
    }

    //TODO: need to take another look and make sure we're properly replicating all the behavior of this
    // method in IceUdpTransportManager
    @Override
    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transport)
    {
        if (iceAgent.getState().isEstablished()) {
            logger.info("BRIAN: local ufrag " + iceAgent.getLocalUfrag() +
                    " is already established, not restarting");
            return;
        }
        logger.info("BRIAN: starting connectivity establishment with extension: " + transport.toXML());
        // Get the remote fingerprints and set them in the DTLS stack so we
        // have them to do the DTLS handshake later
        List<DtlsFingerprintPacketExtension> dfpes
                = transport.getChildExtensionsOfType(
                DtlsFingerprintPacketExtension.class);
        logger.info("BRIAN: have " + dfpes.size() + " remote fingerprints");

        Map<String, String> remoteFingerprints = new HashMap<>();
        dfpes.forEach(dfpe -> {
            if (dfpe.getHash() != null && dfpe.getFingerprint() != null) {
                logger.info("Adding fingerprint " + dfpe.getHash() + " -> " + dfpe.getFingerprint());
                remoteFingerprints.put(dfpe.getHash(), dfpe.getFingerprint());
            } else {
                logger.info("Ignoring empty transport extension");
            }
        });
        if (remoteFingerprints.isEmpty()) {
            logger.info("Empty transport extension, not starting connectivity yet");
            return;
        }

        // Set the remote ufrag/password
        if (transport.getUfrag() != null) {
            iceAgent.getStream(ICE_STREAM_NAME).setRemoteUfrag(transport.getUfrag());
        }
        if (transport.getPassword() != null) {
            iceAgent.getStream(ICE_STREAM_NAME).setRemotePassword(transport.getPassword());
        }

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning
                = IceProcessingState.RUNNING.equals(iceAgent.getState());

        List<CandidatePacketExtension> candidates
                = transport.getChildExtensionsOfType(
                CandidatePacketExtension.class);
        logger.info("BRIAN: got " + candidates.size() + " candidates " + candidates);
        if (iceAgentStateIsRunning && candidates.isEmpty()) {
            logger.info("ICE agent is already running and this extension contained no candidates, returning");
            return;
        }

        int remoteCandidateCount = addRemoteCandidates(candidates, iceAgentStateIsRunning);

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
        else if (iceAgent.getStream(ICE_STREAM_NAME).getRemoteUfrag() != null
                && iceAgent.getStream(ICE_STREAM_NAME).getRemotePassword() != null)
        {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.info("Starting ICE agent without remote candidates.");
            iceAgent.startConnectivityEstablishment();
        }
//        logger.info("BRIAN: starting connectivity establishment");
//        iceAgent.startConnectivityEstablishment();
//        logger.info("BRIAN: call to startConnectivityEstablishment returned");
    }

    private Transceiver getTransceiver() {
        return this.transceiver;
//        List<Channel> channels = getChannels();
//        for (Channel channel : channels) {
//            if (channel instanceof RtpChannel)
//            {
//                RtpChannel rtpChannel = (RtpChannel) channel;
//                if (rtpChannel.getEndpoint() != null && rtpChannel.getEndpoint().transceiver != null) {
//                    return rtpChannel.getEndpoint().transceiver;
//                }
//            }
//        }
//        return null;
    }

    // Almost the same as the one in IceUdpTransportManager, but we don't use the iceStream member and
    // always assume rtcpmux
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
                    = iceAgent.getStream(ICE_STREAM_NAME).getComponent(candidate.getComponent());
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

    public void setTransceiver(Transceiver transceiver) {
        this.transceiver = transceiver;
    }

    @Override
    public boolean isConnected()
    {
        return iceAgent.getState().isEstablished();
    }

    @Override
    public SrtpControl getSrtpControl(Channel channel)
    {
        //TODO
        return null;
    }

    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        // Get socket wrapper for rtp/rtcp
        // Create DefaultStreamConnector
        //TODO
        return null;
    }

    @Override
    public MediaStreamTarget getStreamTarget(Channel channel) {
        //TODO
        return null;
    }

    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        pe.setPassword(iceAgent.getLocalPassword());
        pe.setUfrag(iceAgent.getLocalUfrag());
        iceAgent.getStream(ICE_STREAM_NAME).getComponents().forEach(component -> {
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
//        fingerprintPE.setFingerprint(transceiver.getLocalFingerprint());
        fingerprintPE.setFingerprint(dtlsStack.getLocalFingerprint());
//        fingerprintPE.setHash(transceiver.getLocalFingerprintHashFunction());
        fingerprintPE.setHash(dtlsStack.getLocalFingerprintHashFunction());
        fingerprintPE.setSetup("ACTPASS");
    }

    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    private Node createIncomingPipeline() {
        PipelineBuilder builder = new PipelineBuilder();

        DemuxerNode dtlsSrtpDemuxer = new DemuxerNode();
        // DTLS path
        PacketPath dtlsPath = new PacketPath();
        dtlsPath.setPredicate((packet) -> {
            int b = packet.getBuffer().get(0) & 0xFF;
            return (b >= 20 && b <= 63);
        });
        PipelineBuilder dtlsPipelineBuilder = new PipelineBuilder();
        dtlsPipelineBuilder.node(dtlsReceiver);
        dtlsPipelineBuilder.simpleNode("sctp app packet handler", packets -> {
            sctpAppPackets.addAll(packets);

            return Collections.emptyList();
        });
        dtlsPath.setPath(dtlsPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(dtlsPath);

        // SRTP path
        PacketPath srtpPath = new PacketPath();
        srtpPath.setPredicate(packet -> {
            int b = packet.getBuffer().get(0) & 0xFF;
            return (b < 20 || b > 63);
        });
        PipelineBuilder srtpPipelineBuilder = new PipelineBuilder();
        srtpPipelineBuilder.simpleNode("SRTP path", packetInfos -> {
            // Every srtp packet will go to every transceiver.  The transceivers are responsible
            // for filtering out the payload types they don't want
            packetInfos.forEach( pktInfo -> {
                Transceiver transceiver = getTransceiver();
                if (transceiver == null) {
                    logger.error("Null transceiver in SRTP path for transport manager " + hashCode());
                } else {
                    transceiver.handleIncomingPacket(pktInfo);
                }
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

    // Start a thread for each transceiver.  Each thread will read from the transceiver's outgoing queue
    // and send that data on the shared socket
    private void installTransceiverOutgoingPacketSenders(MultiplexingDatagramSocket s) {
        new Thread(() -> {
            Transceiver transceiver = getTransceiver();
            while (true) {
                try
                {
                    while (transceiver == null) {
                        transceiver = getTransceiver();
                    }
                    PacketInfo pktInfo = transceiver.getOutgoingQueue().take();
                    Packet pkt = pktInfo.getPacket();
//                        logger.info("outgoing writer, packet has " + pktInfo.getMetaData().size() + " metadata: ");
//                        pktInfo.getMetaData().forEach((name, value) -> {
//                            logger.info(name + " -> " + value);
//                        });
                    pktInfo.getMetaData().forEach((name, value) -> {
                        if (name instanceof String && ((String) name).contains("TimeTag"))
                        {
                            Long timestamp = (Long)value;
                            SrtpPacket packet = (SrtpPacket)pktInfo.getPacket();
//                                logger.info("Packet " + packet.getHeader().getSsrc() + " " +
//                                        packet.getHeader().getSequenceNumber() + " took " +
//                                        (System.currentTimeMillis() - timestamp) + "ms from received to sent");
                        }
                    });
//                    System.out.println("BRIAN: transceiver writer thread sending packet " + p.toString());
                    s.send(new DatagramPacket(pkt.getBuffer().array(), pkt.getBuffer().arrayOffset(), pkt.getBuffer().limit()));
                } catch (InterruptedException | IOException e)
                {
                    e.printStackTrace();
                    break;
                }
            }
        }, "Outgoing write thread").start();
    }

    // Start a thread to read from the socket.  Handle DTLS, forward srtp off to the transceiver
    private void installIncomingPacketReader(MultiplexingDatagramSocket s) {
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, 0, 1500);
                try
                {
                    s.receive(p);
                    ByteBuffer packetBuf = ByteBuffer.allocate(p.getLength());
                    packetBuf.put(ByteBuffer.wrap(buf, 0, p.getLength())).flip();
                    Packet pkt = new UnparsedPacket(packetBuf);
                    PacketInfo pktInfo = new PacketInfo(pkt);
                    pktInfo.setReceivedTime(System.currentTimeMillis());
                    incomingPipelineRoot.processPackets(Collections.singletonList(pktInfo));
                } catch (IOException e)
                {
                    e.printStackTrace();
                    break;
                }
            }
        }, "Incoming read thread").start();

    }

    private boolean iceConnectedProcessed = false;

    private void onIceConnected() {
        iceConnected = true;
        if (iceConnectedProcessed) {
            System.out.println("Already processed ice connected, ignoring new event");
            return;
        }
        // The sctp connection start is triggered by this, but otherwise i don't think we need it (the other channel
        // types no longer do anything needed in there)
        getChannels().forEach(Channel::transportConnected);
        iceConnectedProcessed = true;
        MultiplexingDatagramSocket s = iceAgent.getStream(ICE_STREAM_NAME).getComponents().get(0).getSocket();

        // Socket writer thread
        installTransceiverOutgoingPacketSenders(s);

        // Socket reader thread.  Read from the underlying socket and pass to the incoming
        // module chain
        installIncomingPacketReader(s);

        DatagramTransport tlsTransport = new QueueDatagramTransport(
                dtlsReceiver::receive,
                (buf, off, len) -> { dtlsSender.send(buf, off, len); return Unit.INSTANCE; },
                1500
        );
        dtlsStack.onHandshakeComplete((dtlsTransport, tlsContext) -> {
            System.out.println("BRIAN: dtls handshake complete, got srtp profile: " + dtlsStack.getChosenSrtpProtectionProfile());
            dtlsReceiver.setDtlsTransport(dtlsTransport);
            this.dtlsTransport = dtlsTransport;
            Transceiver transceiver = getTransceiver();
            if (transceiver != null) {
                transceiver.setSrtpInformation(dtlsStack.getChosenSrtpProtectionProfile(), tlsContext);
            } else {
                logger.error("Couldn't get transceiver to set srtp information");
            }
            return Unit.INSTANCE;
        });
        packetSender.socket = s;
        System.out.println("BRIAN: transport manager " + this.hashCode() + " starting dtls");
        executor.submit(() -> {
            dtlsStack.connect(new TlsClientImpl(), tlsTransport);
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
        if (newState.isEstablished()) {
            logger.info("BRIAN: local ufrag " + iceAgent.getLocalUfrag() + " ICE connected, need to start dtls");
            onIceConnected();
        } else if (IceProcessingState.FAILED.equals(newState)) {
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
        super.close();
        executor.shutdown();
        while (!executor.isTerminated()) {
            try
            {
                logger.info("Still waiting for " + getClass() + " to shutdown");
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e)
            {
            }
        }
    }
}
