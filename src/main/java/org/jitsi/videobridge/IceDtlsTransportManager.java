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
    private static final ExecutorService transceiverExecutor = Executors.newSingleThreadExecutor();
    private static final Logger logger
            = Logger.getLogger(IceDtlsTransportManager.class);
    private static final String ICE_STREAM_NAME = "ice-stream-name";
    //TODO: made public so we can grab it in sctp connection, fix that.
    /*private*/ DtlsStack dtlsStack = new DtlsClientStack();
    private DtlsReceiverNode dtlsReceiver = new DtlsReceiverNode();
    //TODO: temp store dtls transport because newsctpconnection grabs it
    DTLSTransport dtlsTransport;
    LinkedBlockingQueue<PacketInfo> sctpAppPackets = new LinkedBlockingQueue<>();
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
        iceAgent = createIceAgent(true);
        iceAgent.addStateChangeListener(this::iceAgentStateChange);
        logger.info("BRIAN: finished IceDtlsTransportManager ctor");
    }

    @Override
    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transport)
    {
        logger.info("BRIAN: starting connectivity establishment with extension: " + transport);
        // Get the remote fingerprints and set them in the DTLS stack so we
        // have them to do the DTLS handshake later
        List<DtlsFingerprintPacketExtension> dfpes
                = transport.getChildExtensionsOfType(
                DtlsFingerprintPacketExtension.class);
        logger.info("BRIAN: have " + dfpes.size() + " remote fingerprints");

            Map<String, String> remoteFingerprints = new HashMap<>();
            dfpes.forEach(dfpe -> {
                logger.info("Adding fingerprint " + dfpe.getHash() + " -> " + dfpe.getFingerprint());
                remoteFingerprints.put(dfpe.getHash(), dfpe.getFingerprint());
            });
//            getTransceivers().forEach(transceiver -> {
//                transceiver.setRemoteFingerprints(remoteFingerprints);
//            });
//            transceiver.setRemoteFingerprints(remoteFingerprints);

        // Set the remote ufrag/password
        if (transport.getUfrag() != null) {
            iceAgent.getStream(ICE_STREAM_NAME).setRemoteUfrag(transport.getUfrag());
        }
        if (transport.getPassword() != null) {
            iceAgent.getStream(ICE_STREAM_NAME).setRemotePassword(transport.getPassword());
        }

        List<CandidatePacketExtension> candidates
                = transport.getChildExtensionsOfType(
                CandidatePacketExtension.class);
        logger.info("BRIAN: got candidates " + candidates);

        logger.info("BRIAN: starting connectivity establishment");
        iceAgent.startConnectivityEstablishment();
        logger.info("BRIAN: call to startConnectivityEstablishment returned");
    }

    private List<Transceiver> getTransceivers() {
        List<Transceiver> transceivers = new ArrayList<>();
        getChannels().forEach(channel -> {
            if (channel instanceof RtpChannel)
            {
                RtpChannel rtpChannel = (RtpChannel) channel;
                transceivers.add(rtpChannel.getEndpoint().transceiver);
            }
        });
        return transceivers;
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
            getTransceivers().forEach(transceiver -> {
               packetInfos.forEach( pktInfo -> {
                   // TODO(brian): we need to copy the packet for each transceiver for now, because
                   //  although each transceiver filters out the rtp they don't care about (audio
                   //  vs video), rtcp will be handled by both.  In the future, a single transceiver
                   //  should be shared by the channels and we can filter ALL rtcp out in
                   //  a single path in the transceiver itself and let the receiver just worry about
                   //  rtp
                   PacketInfo copy = pktInfo.clone();
                   transceiver.handleIncomingPacket(copy);
               });
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
        getTransceivers().forEach(transceiver -> {
            new Thread(() -> {
                while (true) {
                    try
                    {
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
        });
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
                    pktInfo.getMetaData().put("TimeTag-IceDtlsTransportManagerReceive", System.currentTimeMillis());
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
            getTransceivers().forEach(transceiver -> {
                transceiver.setSrtpInformation(dtlsStack.getChosenSrtpProtectionProfile(), tlsContext);
            });
            return Unit.INSTANCE;
        });
        packetSender.socket = s;
        System.out.println("BRIAN: transport manager " + this.hashCode() + " starting dtls");
        dtlsStack.connect(new TlsClientImpl(), tlsTransport);
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
            logger.info("BRIAN: ICE connected, need to start dtls");
            onIceConnected();
        }
    }

    private Agent createIceAgent(boolean isControlling)
            throws IOException
    {
        Agent iceAgent = new Agent();

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
//        configureHarvesters(iceAgent, rtcpmux);
        iceAgent.setControlling(isControlling);
        iceAgent.setPerformConsentFreshness(true);

        int portBase = portTracker.getPort();

        IceMediaStream iceStream = iceAgent.createMediaStream(ICE_STREAM_NAME);

        iceAgent.createComponent(
                iceStream, Transport.UDP,
                portBase, portBase, portBase + 100,
                KeepAliveStrategy.SELECTED_ONLY);

        return iceAgent;
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
}
