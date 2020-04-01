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

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.dtls.DtlsClient;
import org.jitsi.nlj.dtls.DtlsRole;
import org.jitsi.nlj.dtls.DtlsServer;
import org.jitsi.nlj.dtls.DtlsStack;
import org.jitsi.nlj.stats.BridgeJitterStats;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.stats.PacketDelayStats;
import org.jitsi.nlj.transform.NodeSetVisitor;
import org.jitsi.nlj.transform.PipelineBuilder;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.ProtocolReceiver;
import org.jitsi.nlj.transform.node.outgoing.ProtocolSender;
import org.jitsi.nlj.util.OrderedJsonObject;
import org.jitsi.nlj.util.PacketInfoQueue;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.rtp.extensions.PacketExtensionsKt;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.CountingErrorHandler;
import org.jitsi.videobridge.stats.DoubleAverage;
import org.jitsi.videobridge.util.ByteBufferPool;
import org.jitsi.videobridge.util.TaskPools;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.json.simple.JSONObject;

import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.jitsi.videobridge.TransportConfig.Config;

/**
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class DtlsTransport
{
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

    private static final PacketDelayStats rtpPacketDelayStats = new PacketDelayStats();
    private static final PacketDelayStats rtcpPacketDelayStats = new PacketDelayStats();

    public static OrderedJsonObject getPacketDelayStats()
    {
        OrderedJsonObject packetDelayStats = new OrderedJsonObject();
        packetDelayStats.put("rtp", DtlsTransport.rtpPacketDelayStats.toJson());
        packetDelayStats.put("rtcp", DtlsTransport.rtcpPacketDelayStats.toJson());

        return packetDelayStats;
    }

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
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final SocketSenderNode packetSender = new SocketSenderNode();
    private final Node incomingPipelineRoot;
    private final Node outgoingDtlsPipelineRoot;
    private final Node outgoingSrtpPipelineRoot;
    private boolean dtlsHandshakeComplete = false;

    private final PacketStats packetStats = new PacketStats();
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
    public DtlsTransport(Endpoint endpoint, Logger parentLogger)
    {
        logger = parentLogger.createChildLogger(getClass().getName());
        this.endpoint = endpoint;

        outgoingPacketQueue
                = new PacketInfoQueue(
                        getClass().getSimpleName() + "-outgoing-packet-queue",
                        TaskPools.IO_POOL,
                        this::handleOutgoingSrtpData,
                        Config.queueSize());
        outgoingPacketQueue.setErrorHandler(queueErrorCounter);

        dtlsStack = new DtlsStack(logger);
        dtlsReceiver = new ProtocolReceiver(dtlsStack);
        dtlsSender = new ProtocolSender(dtlsStack);

        dtlsStack.onHandshakeComplete((chosenSrtpProfile, tlsRole, keyingMaterial) -> {
            dtlsHandshakeComplete = true;
            logger.info("DTLS handshake complete. Got SRTP profile " +
                    chosenSrtpProfile);
            endpoint.setSrtpInformation(chosenSrtpProfile, tlsRole, keyingMaterial);
            dtlsConnectedSubscribers.forEach(Runnable::run);
            return Unit.INSTANCE;
        });

        incomingPipelineRoot = createIncomingPipeline();
        outgoingDtlsPipelineRoot = createOutgoingDtlsPipeline();
        outgoingSrtpPipelineRoot = createOutgoingSrtpPipeline();
    }

    void setSetupAttribute(String setupAttr)
    {
        if ("active".equalsIgnoreCase(setupAttr))
        {
            logger.info("The remote side is acting as DTLS client, we'll act as server");
            dtlsStack.actAsServer();
        }
        else if ("passive".equalsIgnoreCase(setupAttr))
        {
            logger.info("The remote side is acting as DTLS server, we'll act as client");
            dtlsStack.actAsClient();
        }
        else if (!StringUtils.isNullOrEmpty(setupAttr))
        {
            logger.error("The remote side sent an unrecognized DTLS setup value: " +
                    setupAttr);
        }
    }

    void setRemoteFingerprints(Map<String, String> remoteFingerprints)
    {
        // Don't pass an empty list to the stack in order to avoid wiping
        // certificates that were contained in a previous request.
        if (!remoteFingerprints.isEmpty())
        {
            dtlsStack.setRemoteFingerprints(remoteFingerprints);

            final boolean hasSha1Hash = remoteFingerprints
                    .keySet()
                    .stream()
                    .anyMatch(hash -> hash.equalsIgnoreCase("sha-1"));

            if (dtlsStack.getRole() == null
                    && hasSha1Hash)
            {
                // hack(george) Jigasi sends a sha-1 dtls fingerprint without a
                // setup attribute and it assumes a server role for the bridge.

                logger.info("Assume that the remote side is Jigasi, we'll act as server");
                dtlsStack.actAsServer();
            }
        }
    }

    public void startDtlsHandshake()
    {
        logger.info("Starting DTLS.");
        try
        {
            if (dtlsStack.getRole() == null)
            {
                logger.warn("Starting the DTLS stack before it knows its role");
            }
            dtlsStack.start();
        }
        catch (Throwable e)
        {
            logger.error("Error during DTLS negotiation: " + e.toString() +
                    ", closing this transport manager");
        }
    }

    /**
     * Returns {@code true} if this {@link DtlsTransport} is connected. It is
     * considered connected if the underlying ICE connection has been
     * established and the DTLS session has been established.
     * @return true if the DTLS handshake has completed, false otherwise
     */
    public boolean isConnected()
    {
        return dtlsHandshakeComplete;
    }

    public void stop()
    {
        if (running.compareAndSet(true, false))
        {
            dtlsStack.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void describe(IceUdpTransportPacketExtension pe)
    {
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

        final DtlsRole role = dtlsStack.getRole();
        final String setupRole;
        if (role == null)
        {
            // We've not chosen a role yet, so we can be either
            setupRole = "actpass";
        }
        else if (role instanceof DtlsServer)
        {
            setupRole = "passive";
        }
        else if (role instanceof DtlsClient)
        {
            setupRole = "active";
        }
        else
        {
            throw new IllegalStateException("Can not describe role " + role);
        }
        fingerprintPE.setSetup(setupRole);
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
        ConsumerNode sctpHandler = new ConsumerNode("sctp app packet handler")
        {
            @Override
            protected void consume(@NotNull PacketInfo packetInfo)
            {
                endpoint.dtlsAppPacketReceived(packetInfo);
            }

            @Override
            public void trace(@NotNull Function0<Unit> f)
            {
                f.invoke();
            }
        };
        dtlsPipelineBuilder.node(sctpHandler);
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
        ConsumerNode srtpHandler = new ConsumerNode("SRTP path")
        {
            @Override
            protected void consume(@NotNull PacketInfo packetInfo)
            {
                endpoint.srtpPacketReceived(packetInfo);
            }

            @Override
            public void trace(@NotNull Function0<Unit> f)
            {
                f.invoke();
            }
        };
        srtpPipelineBuilder.node(srtpHandler);
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
     *
     * TODO(brian): once SRTP data is no longer going through this class,
     * we can rename this to 'send' (and probably take a type other than
     * PacketInfo)
     */
    public void sendDtlsData(PacketInfo packetInfo)
    {
        packetStats.numOutgoingDtlsPackets++;
        outgoingDtlsPipelineRoot.processPacket(packetInfo);
    }

    /**
     * Handles a packet after it has passed through the Transceiver.
     * Note that the packet is added to a queue and further processed
     * in a different context.
     *
     * TODO(brian): this method will go away entirely once SRTP
     * is no longer going through this class
     */
    public void sendSrtpData(PacketInfo packetInfo)
    {
        outgoingPacketQueue.add(packetInfo);
    }

    /**
     * Handle an outgoing SRTP packet in the context of the queue handler
     *
     * TODO(brian): this method will go away entirely once SRTP
     * is no longer going through this class
     */
    private boolean handleOutgoingSrtpData(PacketInfo packetInfo)
    {
        packetStats.numOutgoingSrtpPackets++;
        outgoingSrtpPipelineRoot.processPacket(packetInfo);
        return true;
    }

    /**
     * Notify this [DtlsTransport] that data has been received (from a lower transport layer).
     * @param data the buffer
     * @param off the offset
     * @param len the length
     *
     * TODO(brian): once this class is no longer handling SRTP data, we won't have to
     *            leave the extra space at the beginning and end of the copied buffer
     *            (and may not have to copy at all?)
     */
    public void dataReceived(byte[] data, int off, int len)
    {
        packetStats.numReceivedPackets++;
        byte[] copy = ByteBufferPool.getBuffer(
                len +
                        RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                        RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET
        );
        System.arraycopy(data, off, copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, len);
        Packet pkt = new UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, len);
        PacketInfo pktInfo = new PacketInfo(pkt);
        pktInfo.setReceivedTime(System.currentTimeMillis());
        incomingPipelineRoot.processPacket(pktInfo);
    }

    public void setSender(Consumer<DatagramPacket> sender)
    {
        packetSender.sender = sender;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("bridge_jitter", bridgeJitterStats.getJitter());
        debugState.put("dtlsStack", dtlsStack.getNodeStats().toJson());

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
        public Consumer<DatagramPacket> sender = null;

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
            Packet packet = packetInfo.getPacket();

            if (PacketExtensionsKt.looksLikeRtp(packet))
            {
                rtpPacketDelayStats.addPacket(packetInfo);
            }
            else if (PacketExtensionsKt.looksLikeRtcp(packet))
            {
                rtcpPacketDelayStats.addPacket(packetInfo);
            }

            bridgeJitterStats.packetSent(packetInfo);
            overallAverageBridgeJitter.addValue(bridgeJitterStats.getJitter());
            if (sender != null)
            {
                packetInfo.sent();
                sender.accept(
                        new DatagramPacket(
                                packet.getBuffer(),
                                packet.getOffset(),
                                packet.getLength()));
                packetStats.numSentPackets++;
            }
            else
            {
                logger.warn("Sender is null, dropping outgoing data");
                packetStats.numOutgoingPacketsDroppedNoSender++;
            }
            ByteBufferPool.returnBuffer(packet.getBuffer());
        }

        @Override
        public void trace(@NotNull Function0<Unit> f)
        {
            f.invoke();
        }
    }

    private static class PacketStats
    {
        int numReceivedPackets = 0;
        int numOutgoingSrtpPackets = 0;
        int numOutgoingDtlsPackets = 0;
        int numSentPackets = 0;
        int numOutgoingPacketsDroppedNoSender = 0;

        OrderedJsonObject toJson()
        {
            OrderedJsonObject json = new OrderedJsonObject();
            json.put("num_received_packets", numReceivedPackets);
            json.put("num_outgoing_srtp_packets", numOutgoingSrtpPackets);
            json.put("num_outgoing_dtls_packets", numOutgoingDtlsPackets);
            json.put("num_sent_packets", numSentPackets);
            json.put("num_outgoing_packets_dropped_no_sender", numOutgoingPacketsDroppedNoSender);
            return json;
        }
    }
}
