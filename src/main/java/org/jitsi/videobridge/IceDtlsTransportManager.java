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
import org.jitsi.videobridge.util.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/**
 * @author Brian Baldino
 * @author Boris Grozev
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
    private List<Runnable> dtlsConnectedSubscribers = new ArrayList<>();
    private final PacketInfoQueue outgoingPacketQueue;
    private Endpoint endpoint = null;

    private SocketSenderNode packetSender = new SocketSenderNode();
    private Node incomingPipelineRoot = createIncomingPipeline();
    private Node outgoingDtlsPipelineRoot = createOutgoingDtlsPipeline();
    private Node outgoingSrtpPipelineRoot = createOutgoingSrtpPipeline();
    protected boolean dtlsHandshakeComplete = false;
    private boolean iceConnectedProcessed = false;

    public IceDtlsTransportManager(String id, Conference conference)
            throws IOException
    {
        super(conference, true, id);
        this.logger = Logger.getLogger(classLogger, conference.getLogger());

        outgoingPacketQueue
                = new PacketInfoQueue(
                        "TM-outgoing-" + id,
                        TaskPools.IO_POOL,
                        this::handleOutgoingPacket);
    }

    /**
     * Reads the DTLS fingerprints from, the transport extension before
     * passing it over to the ICE transport manager.
     * @param transportPacketExtension
     */
    @Override
    public void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transportPacketExtension)
    {
        // TODO(boris): read the Setup attribute and support acting like the
        // DTLS server.
        List<DtlsFingerprintPacketExtension> fingerprintExtensions
                = transportPacketExtension.getChildExtensionsOfType(
                        DtlsFingerprintPacketExtension.class);

        Map<String, String> remoteFingerprints = new HashMap<>();
        fingerprintExtensions.forEach(fingerprintExtension -> {
            if (fingerprintExtension.getHash() != null
                    && fingerprintExtension.getFingerprint() != null) {
                remoteFingerprints.put(
                        fingerprintExtension.getHash(),
                        fingerprintExtension.getFingerprint());
            }
            else
            {
                logger.warn(
                        "Ignoring empty DtlsFingerprint extension: "
                                + transportPacketExtension.toXML());
            }
        });

        // Don't pass an empty list to the stack in order to avoid wiping
        // certificates that were contained in a previous request.
        if (!remoteFingerprints.isEmpty())
        {
            dtlsStack.setRemoteFingerprints(remoteFingerprints);
        }

        super.startConnectivityEstablishment(transportPacketExtension);
    }

    public void setEndpoint(Endpoint endpoint)
    {
        //TODO(brian): i think eventually we'll have the endpoint create its
        // transport manager, which case we can just pass it in via the ctor
        // (assuming we want to expose the entire endpoint.  maybe there's a
        // more limited interface we can expose to the transportmanager?)
        this.endpoint = endpoint;
    }

    @Override
    public boolean isConnected()
    {
        // TODO: do we consider this TM connected when ICE completes, or when
        // ICE and DTLS both complete?
        return super.isConnected();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        super.describe(pe);

        // Describe dtls
        DtlsFingerprintPacketExtension fingerprintPE
                = pe.getFirstChildOfType(DtlsFingerprintPacketExtension.class);
        if (fingerprintPE == null)
        {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            pe.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(dtlsStack.getLocalFingerprint());
        fingerprintPE.setHash(dtlsStack.getLocalFingerprintHashFunction());

        // TODO: don't we only support ACTIVE right now?
        fingerprintPE.setSetup("ACTPASS");
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

    private Node createIncomingPipeline()
    {
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
            packetInfos.forEach(endpoint::srtpPacketReceived);
            return Collections.emptyList();
        });
        srtpPath.setPath(srtpPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(srtpPath);

        builder.node(dtlsSrtpDemuxer);
        return builder.build();
    }

    private Node createOutgoingDtlsPipeline()
    {
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

    public void sendDtlsData(PacketInfo packet)
    {
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
    private void installIncomingPacketReader(DatagramSocket socket)
    {
        //TODO(brian): this does a bit more than just read from the iceSocket
        // (does a bit of processing for each packet) but I think it's little
        // enough (it'll only be a bit of the DTLS path) that running it in the
        // IO pool is fine
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
                    incomingPipelineRoot
                            .processPackets(Collections.singletonList(pktInfo));
                }
                catch (SocketClosedException e)
                {
                    logger.info(getLoggingId() +
                            "Socket closed, stopping reader.");
                    break;
                }
                catch (IOException e)
                {
                    logger.warn(getLoggingId() +
                            "Stopping reader: ", e);
                    break;
                }
            }
        });
    }

    @Override
    protected void onIceConnected()
    {
        if (iceConnectedProcessed)
        {
            return;
        }
        iceConnectedProcessed = true;

        DatagramSocket socket = iceStream.getComponents().get(0).getSocket();

        endpoint.setOutgoingSrtpPacketHandler(
                packets -> packets.forEach(outgoingPacketQueue::add));

        // Socket reader thread. Read from the underlying iceSocket and pass
        // to the incoming module chain.
        installIncomingPacketReader(socket);

        dtlsStack.onHandshakeComplete((tlsContext) -> {
            dtlsHandshakeComplete = true;
            logger.info(getLoggingId() +
                    " DTLS handshake complete. Got SRTP profile " +
                        dtlsStack.getChosenSrtpProtectionProfile());
            //TODO: emit this as part of the dtls handshake complete event?
            endpoint.setSrtpInformation(
                    dtlsStack.getChosenSrtpProtectionProfile(), tlsContext);
            dtlsConnectedSubscribers.forEach(Runnable::run);
            return Unit.INSTANCE;
        });

        packetSender.socket = socket;
        logger.info(getLoggingId() + " Starting DTLS.");
        TaskPools.IO_POOL.submit(() -> {
            try
            {
                dtlsStack.connect();
            }
            catch (Exception e)
            {
                logger.error(getLoggingId() +
                        "Error during DTLS negotiation: " + e.toString() +
                        ", closing this transport manager");
                close();
            }
        });
    }

    class SocketSenderNode extends Node
    {
        public DatagramSocket socket = null;
        SocketSenderNode()
        {
            super("Socket sender");
        }

        @Override
        protected void doProcessPackets(@NotNull List<PacketInfo> pkts)
        {
            if (socket != null)
            {
                pkts.forEach(pktInfo -> {
                    try
                    {
                        socket.send(
                            new DatagramPacket(
                                    pktInfo.getPacket().getBuffer().array(),
                                    0,
                                    pktInfo.getPacket().getBuffer().limit()));
                    }
                    catch (IOException e)
                    {
                        logger.error(getLoggingId() +
                                "Error sending packet: " + e.toString());
                        throw new RuntimeException(e);
                    }
                });
            }

        }
    }
}
