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
import org.jitsi.nlj.*;
import org.jitsi.nlj.dtls.*;
import org.jitsi.nlj.transform.module.*;
import org.jitsi.nlj.transform.module.incoming.*;
import org.jitsi.nlj.transform.module.outgoing.*;
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
    private final ExecutorService executor;
    private static final String ICE_STREAM_NAME = "ice-stream-name";
    public Transceiver transceiver = new Transceiver();

    public IceDtlsTransportManager(Conference conference)
            throws IOException
    {
        super(conference, true, 1, ICE_STREAM_NAME, null);
        executor = Executors.newSingleThreadExecutor();
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
            transceiver.setRemoteFingerprints(remoteFingerprints);

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
        fingerprintPE.setFingerprint(transceiver.getLocalFingerprint());
        fingerprintPE.setHash(transceiver.getLocalFingerprintHashFunction());
        fingerprintPE.setSetup("ACTPASS");
    }

    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    private ModuleChain createIncomingModuleChain() {
        ModuleChain incomingModuleChain = new ModuleChain();

        DemuxerModule dtlsSrtpDemuxer = new DemuxerModule();
        // DTLS path
        PacketPath dtlsPath = new PacketPath();
        dtlsPath.setPredicate((packet) -> {
            int b = packet.getBuffer().get(0) & 0xFF;
            return (b >= 20 && b <= 63);
        });
        ModuleChain dtlsChain = new ModuleChain();
        dtlsChain.addModule(new DtlsReceiverModule());
        dtlsPath.setPath(dtlsChain);
        dtlsSrtpDemuxer.addPacketPath(dtlsPath);

        incomingModuleChain.addModule(dtlsSrtpDemuxer);

        return incomingModuleChain;
    }

    private ModuleChain createOutgoingModuleChain() {
        ModuleChain outgoingModuleChain = new ModuleChain();

        MuxerModule muxer = new MuxerModule();
        outgoingModuleChain.addModule(muxer);

        muxer.attachInput(new DtlsSenderModule());

        return outgoingModuleChain;
    }

    private void onIceConnected() {
        MultiplexingDatagramSocket s = iceAgent.getStream(ICE_STREAM_NAME).getComponents().get(0).getSocket();

        // Socket writer thread
        new Thread(() -> {
            while (true) {
                try
                {
                    Packet p = transceiver.getOutgoingQueue().take();
                    System.out.println("BRIAN: transceiver writer thread got data");
                    s.send(new DatagramPacket(p.getBuffer().array(), 0, p.getBuffer().limit()));
                } catch (InterruptedException | IOException e)
                {
                    e.printStackTrace();
                    break;
                }
            }
        }).start();

        // Socket reader thread.  Read from the underlying socket and pass to the incoming
        // module chain
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, 0, 1500);
                try
                {
                    s.receive(p);
                    ByteBuffer packetBuf = ByteBuffer.allocate(p.getLength());
//                    System.out.println("BRIAN: received packet with length " + p.getLength());
                    packetBuf.put(ByteBuffer.wrap(buf, 0, p.getLength())).flip();
                    Packet pkt = new UnparsedPacket(packetBuf);
//                    System.out.println("BRIAN: put into UnparsedPacket, length is " + pkt.getSize());
                    transceiver.getIncomingQueue().add(pkt);
                } catch (IOException e)
                {
                    e.printStackTrace();
                    break;
                }
            }
        }, "Incoming read thread").start();

        transceiver.connectDtls();
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
