/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.socket.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.dtls.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.transform.node.outgoing.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.json.simple.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.*;

/**
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class DtlsTransport extends IceTransport
{
    /**
     * The {@link Logger} used by the {@link DtlsTransport} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
            = Logger.getLogger(DtlsTransport.class);

    /**
     * A predicate which is true for DTLS packets. See
     * https://tools.ietf.org/html/rfc7983#section-7
     */
    private static final Predicate<Packet> DTLS_PREDICATE
        = PacketExtensionsKt::looksLikeDtls;

    /**
     * A predicate which is true for all non-DTLS packets. See
     * https://tools.ietf.org/html/rfc7983#section-7
     */
    private static final Predicate<Packet> NON_DTLS_PREDICATE
            = DTLS_PREDICATE.negate();

    public static final PacketDelayStats packetDelayStats = new PacketDelayStats();
    /**
     * An average of all of the individual bridge jitter values calculated by the
     * {@link DtlsTransport#bridgeJitterStats} instance variables below
     */
    public static final DoubleAverage overallAverageBridgeJitter = new DoubleAverage("overall_bridge_jitter");

    /**
     * Count the number of dropped packets and exceptions.
     */
    static final CountingErrorHandler queueErrorCounter
            = new CountingErrorHandler();

    private final Logger logger;
    private final DtlsStack dtlsStack;
    private final ProtocolReceiver dtlsReceiver;
    private final ProtocolSender dtlsSender;
    private List<Runnable> dtlsConnectedSubscribers = new ArrayList<>();
    private final PacketInfoQueue outgoingPacketQueue;
    private final Endpoint endpoint;

    private final SocketSenderNode packetSender = new SocketSenderNode();
    private final Node incomingPipelineRoot;
    private final Node outgoingDtlsPipelineRoot;
    private final Node outgoingSrtpPipelineRoot;
    private boolean dtlsHandshakeComplete = false;
    /**
     * Measures the jitter introduced by the bridge itself (i.e. jitter calculated between
     * packets based on the time they were received by the bridge and the time they
     * are sent).  This jitter value is calculated independently, per packet, by every
     * individual {@link DtlsTransport} and their jitter values are averaged together
     * in this static member.
     */
    private final BridgeJitterStats bridgeJitterStats = new BridgeJitterStats();

    /**
     * Initializes a new {@link DtlsTransport} instance for a specific endpoint.
     * @param endpoint the endpoint with which this {@link DtlsTransport} is
     *                 associated.
     */
    public DtlsTransport(Endpoint endpoint)
            throws IOException
    {
        super(endpoint, true);
        this.endpoint = endpoint;

        this.logger
                = Logger.getLogger(
                        classLogger,
                        endpoint.getConference().getLogger());

        outgoingPacketQueue
                = new PacketInfoQueue(
                        getClass().getSimpleName() + "-outgoing-packet-queue",
                        TaskPools.IO_POOL,
                        this::handleOutgoingPacket,
                        1024);
        outgoingPacketQueue.setErrorHandler(queueErrorCounter);

        dtlsStack = new DtlsStack(endpoint.getID());
        dtlsReceiver = new ProtocolReceiver(dtlsStack);
        dtlsSender = new ProtocolSender(dtlsStack);

        dtlsStack.onHandshakeComplete((chosenSrtpProfile, tlsRole, keyingMaterial) -> {
            dtlsHandshakeComplete = true;
            logger.info(logPrefix +
                    "DTLS handshake complete. Got SRTP profile " +
                    chosenSrtpProfile);
            endpoint.setSrtpInformation(chosenSrtpProfile, tlsRole, keyingMaterial);
            dtlsConnectedSubscribers.forEach(Runnable::run);
            return Unit.INSTANCE;
        });

        incomingPipelineRoot = createIncomingPipeline();
        outgoingDtlsPipelineRoot = createOutgoingDtlsPipeline();
        outgoingSrtpPipelineRoot = createOutgoingSrtpPipeline();
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
                    && fingerprintExtension.getFingerprint() != null)
            {
                remoteFingerprints.put(
                        fingerprintExtension.getHash(),
                        fingerprintExtension.getFingerprint());
            }
            else
            {
                logger.info(logPrefix +
                        "Ignoring empty DtlsFingerprint extension: "
                                + transportPacketExtension.toXML());
            }
        });
        if (dtlsStack.getRole() == null && !fingerprintExtensions.isEmpty())
        {
            String setup = fingerprintExtensions.get(0).getSetup();
            if ("active".equalsIgnoreCase(setup))
            {
                logger.info(logPrefix +
                    "The remote side is acting as DTLS client, we'll act as server");
                dtlsStack.actAsServer();
            }
            else if ("passive".equalsIgnoreCase(setup))
            {
                logger.info(logPrefix +
                    "The remote side is acting as DTLS server, we'll act as client");
                dtlsStack.actAsClient();
            }
            else if (!StringUtils.isNullOrEmpty(setup))
            {
                logger.error(logPrefix +
                    "The remote side sent an unrecognized DTLS setup value: " +
                        setup);
            }
        }

        // Don't pass an empty list to the stack in order to avoid wiping
        // certificates that were contained in a previous request.
        if (!remoteFingerprints.isEmpty())
        {
            dtlsStack.setRemoteFingerprints(remoteFingerprints);

            String hash = remoteFingerprints.entrySet().iterator().next().getKey();
            if (dtlsStack.getRole() == null
                && hash != null && hash.equalsIgnoreCase("sha-1"))
            {
                // hack(george) Jigasi sends a sha-1 dtls fingerprint without a
                // setup attribute and it assumes a server role for the bridge.

                logger.info(logPrefix +
                    "Assume that the remote side is Jigasi, we'll act as server");
                dtlsStack.actAsServer();
            }
        }

        super.startConnectivityEstablishment(transportPacketExtension);
    }

    /**
     * Returns {@code true} if this {@link DtlsTransport} is connected. It is
     * considered connected if the underlying ICE connection has been
     * established and the DTLS session has been established.
     * @return
     */
    @Override
    public boolean isConnected()
    {
        return super.isConnected() && dtlsHandshakeComplete;
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

    /**
     * Installs a handler to be executed when the DTLS handshake
     * is completed (or immediately if the DTLS handshake has
     * already completed).  Multiple handlers can be installed
     * and will be run in the order they are added.
     * @param handler the handler to be executed
     */
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

    /**
     * Creates the packet pipeline to handle packets read from the ice4j socket.
     * @return the root {@link Node} of the created pipeline
     */
    private Node createIncomingPipeline()
    {
        // do we need a builder if we're using a single node?
        PipelineBuilder builder = new PipelineBuilder();

        DemuxerNode dtlsSrtpDemuxer = new ExclusivePathDemuxer("DTLS/SRTP");
        // DTLS path
        ConditionalPacketPath dtlsPath
                = new ConditionalPacketPath("DTLS path");
        dtlsPath.setPredicate(DTLS_PREDICATE);
        PipelineBuilder dtlsPipelineBuilder = new PipelineBuilder();
        dtlsPipelineBuilder.node(dtlsReceiver);
        dtlsPipelineBuilder.simpleNode(
                "sctp app packet handler",
                packetInfo -> {
                    endpoint.dtlsAppPacketReceived(packetInfo);
                    return null;
        });
        dtlsPath.setPath(dtlsPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(dtlsPath);

        // SRTP path
        ConditionalPacketPath srtpPath = new ConditionalPacketPath("SRTP path");
        // We pass anything non-DTLS to the SRTP stack. This is fine, as STUN
        // packets have already been filtered out in ice4j, and we don't expect
        // anything else. It might be nice to log a warning if we see anything
        // outside the RTP range (see RFC7983), but adding a separate packet
        // path here might be expensive.
        srtpPath.setPredicate(NON_DTLS_PREDICATE);
        PipelineBuilder srtpPipelineBuilder = new PipelineBuilder();
        srtpPipelineBuilder.simpleNode(
                "SRTP path",
                packetInfo -> {
                    endpoint.srtpPacketReceived(packetInfo);
                    return null;
        });
        srtpPath.setPath(srtpPipelineBuilder.build());
        dtlsSrtpDemuxer.addPacketPath(srtpPath);

        builder.node(dtlsSrtpDemuxer);
        return builder.build();
    }

    /**
     * Creates the packet pipeline to handle outgoing DTLS packets.
     * @return the root {@link Node} of the outgoing DTLS pipeline.
     */
    private Node createOutgoingDtlsPipeline()
    {
        PipelineBuilder builder = new PipelineBuilder();
        builder.node(dtlsSender);
        builder.node(packetSender);
        return builder.build();
    }

    /**
     * Creates the packet pipeline to handle outgoing SRTP packets.
     * @return the root {@link Node} of the outgoing SRTP pipeline.
     */
    private Node createOutgoingSrtpPipeline()
    {
        PipelineBuilder builder = new PipelineBuilder();
        builder.node(packetSender);
        return builder.build();
    }

    /**
     * Sends a DTLS packet through the outgoing DTLS pipeline.
     */
    public void sendDtlsData(PacketInfo packetInfo)
    {
        outgoingDtlsPipelineRoot.processPacket(packetInfo);
    }

    /**
     * Handles a packet after it has passed through the Transceiver.
     */
    private boolean handleOutgoingPacket(PacketInfo packetInfo)
    {
        outgoingSrtpPipelineRoot.processPacket(packetInfo);
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

            // We need this buffer to be 1500 bytes because we don't know how
            // big the received packet will be. But we don't want to allocate
            // large buffers for all packets.
            byte[] receiveBuf = ByteBufferPool.getBuffer(1500);
            DatagramPacket p = new DatagramPacket(receiveBuf, 0, 1500);

            while (!closed)
            {
                try
                {
                    socket.receive(p);
                    int len = p.getLength();
                    byte[] buf
                        = ByteBufferPool.getBuffer(
                                len +
                                RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                                RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET);
                    System.arraycopy(
                            receiveBuf, p.getOffset(),
                            buf, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                            len);
                    Packet pkt
                        = new UnparsedPacket(
                                buf,
                                RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                                len);
                    PacketInfo pktInfo = new PacketInfo(pkt);
                    pktInfo.setReceivedTime(System.currentTimeMillis());
                    incomingPipelineRoot.processPacket(pktInfo);

                    p.setData(receiveBuf, 0, receiveBuf.length);
                }
                catch (SocketClosedException e)
                {
                    logger.info(logPrefix + "Socket closed, stopping reader.");
                    break;
                }
                catch (IOException e)
                {
                    logger.warn(logPrefix + "Stopping reader: ", e);
                    break;
                }
            }
        });
    }

    @Override
    protected void onIceConnected()
    {
        updateIceConnectedStats();
        DatagramSocket socket = iceComponent.getSocket();

        endpoint.setOutgoingSrtpPacketHandler(outgoingPacketQueue::add);

        // Socket reader thread. Read from the underlying iceSocket and pass
        // to the incoming module chain.
        installIncomingPacketReader(socket);

        packetSender.socket = socket;
        logger.info(logPrefix + "Starting DTLS.");
        TaskPools.IO_POOL.submit(() -> {
            try
            {
                if (dtlsStack.getRole() == null)
                {
                    logger.warn(logPrefix +
                            "Starting the DTLS stack before it knows its role");
                }
                dtlsStack.start();
            }
            catch (Throwable e)
            {
                logger.error(logPrefix +
                        "Error during DTLS negotiation: " + e.toString() +
                        ", closing this transport manager");
                close();
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onIceFailed()
    {
        endpoint.getConference().getVideobridge().getStatistics()
                .totalIceFailed.incrementAndGet();
        endpoint.getConference().getStatistics().hasIceFailedEndpoint = true;
    }

    /**
     * Bumps the counters of the number of time ICE succeeded in the
     * {@link Videobridge} statistics.
     */
    private void updateIceConnectedStats()
    {
        endpoint.getConference().getStatistics().hasIceSucceededEndpoint = true;

        Videobridge.Statistics stats
                = endpoint.getConference().getVideobridge().getStatistics();
        stats.totalIceSucceeded.incrementAndGet();

        CandidatePair selectedPair = iceComponent.getSelectedPair();
        RemoteCandidate remoteCandidate =
                selectedPair == null ? null : selectedPair.getRemoteCandidate();

        if (remoteCandidate == null)
        {
            return;
        }


        if (remoteCandidate.getTransport() == Transport.TCP
            || remoteCandidate.getTransport() == Transport.SSLTCP)
        {
            stats.totalIceSucceededTcp.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JSONObject getDebugState()
    {
        JSONObject debugState = super.getDebugState();
        debugState.put("bridge_jitter", bridgeJitterStats.getJitter());
        debugState.put("dtlsStack", dtlsStack.getNodeStats().toJson());
        //debugState.put("dtlsReceiver"
        //debugState.put("dtlsSender"

        debugState.put(
                "outgoingPacketQueue",
                outgoingPacketQueue.getDebugState());
        debugState.put("packetSender", packetSender.getNodeStats().toJson());

        NodeSetVisitor nodeSetVisitor = new NodeSetVisitor();
        nodeSetVisitor.visit(incomingPipelineRoot);

        JSONObject incomingPipelineState = new JSONObject();
        debugState.put("incomingPipelineRoot", incomingPipelineState);
        for (Node node : nodeSetVisitor.getNodeSet())
        {
            NodeStatsBlock block = node.getNodeStats();
            incomingPipelineState.put(block.getName(), block.toJson());
        }

        nodeSetVisitor = new NodeSetVisitor();
        nodeSetVisitor.reverseVisit(outgoingDtlsPipelineRoot);
        nodeSetVisitor.reverseVisit(outgoingSrtpPipelineRoot);

        JSONObject outgoingPipelineState = new JSONObject();
        debugState.put("outgoingPipeline", outgoingPipelineState);
        for (Node node : nodeSetVisitor.getNodeSet())
        {
            NodeStatsBlock block = node.getNodeStats();
            outgoingPipelineState.put(block.getName(), block.toJson());
        }

        return debugState;
    }

    /**
     * A terminating {@link Node}, which sends the packets through a
     * datagram socket.
     */
    private class SocketSenderNode extends ConsumerNode
    {
        public DatagramSocket socket = null;

        /**
         * Initializes a new {@link SocketSenderNode}.
         */
        SocketSenderNode()
        {
            super("Socket sender");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void consume(@NotNull PacketInfo packetInfo)
        {
            packetDelayStats.addPacket(packetInfo);
            bridgeJitterStats.packetSent(packetInfo);
            overallAverageBridgeJitter.addValue(bridgeJitterStats.getJitter());
            if (socket != null)
            {
                try
                {
                    socket.send(
                        new DatagramPacket(
                                packetInfo.getPacket().getBuffer(),
                                packetInfo.getPacket().getOffset(),
                                packetInfo.getPacket().getLength()));
                    ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
                }
                catch (IOException e)
                {
                    logger.error(logPrefix +
                            "Error sending packet: " + e.toString());
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
