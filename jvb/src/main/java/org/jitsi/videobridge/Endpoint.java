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

import kotlin.*;
import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.utils.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.cc.allocation.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.rest.root.debug.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.transport.dtls.*;
import org.jitsi.videobridge.transport.ice.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.websocket.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.*;
import java.util.stream.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 * @author George Politis
 */
public abstract class Endpoint
    extends AbstractEndpoint implements PotentialPacketHandler,
        EncodingsManager.EncodingsUpdateListener
{
    /**
     * Track how long it takes for all RTP and RTCP packets to make their way through the bridge.
     * Since {@link Endpoint} is the 'last place' that is aware of {@link PacketInfo} in the outgoing
     * chain, we track this stats here.  Since they're static, these members will track the delay
     * for packets going out to all endpoints.
     */
    protected static final PacketDelayStats rtpPacketDelayStats = new PacketDelayStats();
    protected static final PacketDelayStats rtcpPacketDelayStats = new PacketDelayStats();

    /**
     * An average of all of the individual bridge jitter values calculated by the
     * {@link Endpoint#bridgeJitterStats} instance variables below
     */
    public static final DoubleAverage overallAverageBridgeJitter = new DoubleAverage("overall_bridge_jitter");

    public static OrderedJsonObject getPacketDelayStats()
    {
        OrderedJsonObject packetDelayStats = new OrderedJsonObject();
        packetDelayStats.put("rtp", Endpoint.rtpPacketDelayStats.toJson());
        packetDelayStats.put("rtcp", Endpoint.rtcpPacketDelayStats.toJson());

        return packetDelayStats;
    }

    /**
     * Count the number of dropped packets and exceptions.
     */
    static final CountingErrorHandler queueErrorCounter = new CountingErrorHandler();

    /**
     * Measures the jitter introduced by the bridge itself (i.e. jitter calculated between
     * packets based on the time they were received by the bridge and the time they
     * are sent).  This jitter value is calculated independently, per packet, by every
     * individual {@link Endpoint} and their jitter values are averaged together
     * in this static member.
     */
    protected final BridgeJitterStats bridgeJitterStats = new BridgeJitterStats();

    /**
     * The {@link SctpManager} instance we'll use to manage the SCTP connection
     */
    protected SctpManager sctpManager;

    /**
     * The time at which this endpoint was created (in millis since epoch)
     */
    protected final Instant creationTime;

    /**
     * How long we'll give an endpoint to either successfully establish
     * an ICE connection or fail before we expire it.
     */
    //TODO: make this configurable
    protected static final Duration EP_TIMEOUT = Duration.ofMinutes(2);

    /**
     * TODO Brian
     */
    protected final SctpHandler sctpHandler = new SctpHandler();

    /**
     * TODO Brian
     */
    protected DataChannelStack dataChannelStack;

    /**
     * TODO Brian
     */
    protected final DataChannelHandler dataChannelHandler = new DataChannelHandler();

    /**
     * The instance which manages the Colibri messaging (over a data channel
     * or web sockets).
     */
    @NotNull
    protected final EndpointMessageTransport messageTransport;

    /**
     * The diagnostic context of this instance.
     */
    protected final DiagnosticContext diagnosticContext;

    /**
     * The bitrate controller.
     */
    protected final BitrateController<AbstractEndpoint> bitrateController;

    @NotNull
    protected final IceTransport iceTransport;

    /**
     * This {@link Endpoint}'s DTLS transport.
     */
    @NotNull
    protected final DtlsTransport dtlsTransport;

    /**
     * The set of {@link ChannelShim}s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed. The set of channels shims allows to determine if endpoint
     * can accept audio or video.
     */
    protected final Set<ChannelShim> channelShims = ConcurrentHashMap.newKeySet();

    /**
     * Whether this endpoint should accept audio packets. We set this according
     * to whether the endpoint has an audio Colibri channel whose direction
     * allows sending.
     */
    protected volatile boolean acceptAudio = false;

    /**
     * Whether this endpoint should accept video packets. We set this according
     * to whether the endpoint has a video Colibri channel whose direction
     * allows sending.
     */
    protected volatile boolean acceptVideo = false;

    /**
     * The clock used by this endpoint
     */
    protected final Clock clock;

    /**
     * Whether or not the bridge should be the peer which opens the data channel
     * (as opposed to letting the far peer/client open it).
     */
    protected static final boolean OPEN_DATA_LOCALLY = false;

    /**
     * The executor which runs bandwidth probing.
     *
     * TODO (brian): align the recurringRunnable stuff with whatever we end up
     * doing with all the other executors.
     */
    protected static final RecurringRunnableExecutor recurringRunnableExecutor
            = new RecurringRunnableExecutor(Endpoint.class.getSimpleName());

    /**
     * The queue we put outgoing SRTP packets onto so they can be sent
     * out via the {@link IceTransport} on an IO thread.
     */
    protected final PacketInfoQueue outgoingSrtpPacketQueue;

    /**
     * The {@link SctpSocket} for this endpoint, if an SCTP connection was
     * negotiated.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected Optional<SctpServerSocket> sctpSocket = Optional.empty();

    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     * @param conference conference this endpoint belongs to
     * @param iceControlling {@code true} if the ICE agent of this endpoint's
     * transport will be initialized to serve as a controlling ICE agent;
     * otherwise - {@code false}
     */
    protected Endpoint(
        String id,
        Conference conference,
        Logger parentLogger,
        boolean iceControlling,
        Clock clock)
    {
        super(conference, id, parentLogger);

        this.clock = clock;

        creationTime = clock.instant();
        diagnosticContext = conference.newDiagnosticContext();
        BitrateController.EventHandler bcEventHandler = new BitrateController.EventHandler()
        {
            @Override
            public void allocationChanged(@NotNull BandwidthAllocation allocation)
            {
                // Intentional no-op.
            }

            @Override
            public void forwardedEndpointsChanged(@NotNull Set<String> forwardedEndpoints)
            {
                sendForwardedEndpointsMessage(forwardedEndpoints);
            }

            @Override
            public void effectiveVideoConstraintsChanged(
                    @NotNull Map<String, VideoConstraints> oldEffectiveConstraints,
                    @NotNull Map<String, VideoConstraints> newEffectiveConstraints)
            {
                Endpoint.this.effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints);
            }

            @Override
            public void keyframeNeeded(String endpointId, long ssrc)
            {
                getConference().requestKeyframe(endpointId, ssrc);
            }
        };
        bitrateController = new BitrateController<>(
                bcEventHandler,
                this::getOrderedEndpoints,
                diagnosticContext, logger);

        outgoingSrtpPacketQueue = new PacketInfoQueue(
            getClass().getSimpleName() + "-outgoing-packet-queue",
            TaskPools.IO_POOL,
            this::doSendSrtp,
            TransportConfig.getQueueSize()
        );
        outgoingSrtpPacketQueue.setErrorHandler(queueErrorCounter);

        messageTransport = new EndpointMessageTransport(
            this,
            () -> getConference().getVideobridge().getStatistics(),
            getConference(),
            logger
        );

        diagnosticContext.put("endpoint_id", id);

        iceTransport = new IceTransport(getId(), iceControlling, logger);
        dtlsTransport = new DtlsTransport(logger);

        conference.getVideobridge().getStatistics().totalEndpoints.incrementAndGet();
    }

    protected Endpoint(
        String id,
        Conference conference,
        Logger parentLogger,
        boolean iceControlling)
    {
        this(id, conference, parentLogger, iceControlling, Clock.systemUTC());
    }

    /**
     * Gets the endpoints in the conference in LastN order, with this {@link Endpoint} removed.
     */
    protected List<AbstractEndpoint> getOrderedEndpoints()
    {
        List<AbstractEndpoint> allOrderedEndpoints = new LinkedList<>(getConference().getOrderedEndpoints());
        allOrderedEndpoints.remove(this);
        return allOrderedEndpoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EndpointMessageTransport getMessageTransport()
    {
        return messageTransport;
    }

    protected abstract void setupIceTransport();

    protected abstract void setupDtlsTransport();

    protected boolean doSendSrtp(PacketInfo packetInfo)
    {
        if (PacketExtensionsKt.looksLikeRtp(packetInfo.getPacket()))
        {
            rtpPacketDelayStats.addPacket(packetInfo);
            bridgeJitterStats.packetSent(packetInfo);
        }
        else if (PacketExtensionsKt.looksLikeRtcp(packetInfo.getPacket()))
        {
            rtcpPacketDelayStats.addPacket(packetInfo);
        }
        packetInfo.sent();
        iceTransport.send(
            packetInfo.getPacket().buffer,
            packetInfo.getPacket().offset,
            packetInfo.getPacket().length
        );
        ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        return true;
    }

    /**
     * Notifies this {@code Endpoint} that the ordered list of {@code Endpoint}s changed.
     */
    abstract void lastNEndpointsChanged();

    /**
     * {@inheritDoc}
     */
    public abstract void setLastN(Integer lastN);

    /**
     * Gets the LastN value for this endpoint.
     */
    public abstract int getLastN();

    /**
     * Sets the local SSRC for this endpoint.
     * @param mediaType
     * @param ssrc
     */
    public abstract void setLocalSsrc(MediaType mediaType, long ssrc);

    public abstract double getRtt();

    public abstract void endpointMessageTransportConnected();

    protected abstract void effectiveVideoConstraintsChanged(
        Map<String, VideoConstraints> oldEffectiveConstraints,
        Map<String, VideoConstraints> newEffectiveConstraints);

    public abstract void createSctpConnection();

    public abstract boolean acceptWebSocket(String password);

    protected abstract String getIcePassword();
    protected abstract void sendForwardedEndpointsMessage(Collection<String> forwardedEndpoints);

    public abstract void setTransportInfo(IceUdpTransportPacketExtension transportInfo);

    /**
     * Sets the media sources.
     * @param mediaSources
     */
    protected abstract void setMediaSources(MediaSourceDesc[] mediaSources);

    abstract Instant getMostRecentChannelCreatedTime();

    public abstract void addChannel(ChannelShim channelShim);

    public abstract void removeChannel(ChannelShim channelShim);

    public abstract void updateMediaDirection(MediaType type, String direction);

    public abstract Transceiver getTransceiver();

    public abstract void updateAcceptedMediaTypes();

    public abstract void updateForceMute();

    public abstract int numForwardedEndpoints();

    /**
     * Enables/disables the given feature, if the endpoint implementation supports it.
     *
     * @param feature the feature to enable or disable.
     * @param enabled the state of the feature.
     */
    public abstract void setFeature(EndpointDebugFeatures feature, boolean enabled);

    public abstract boolean isFeatureEnabled(EndpointDebugFeatures feature);

    public abstract boolean isOversending();

    abstract void setSelectedEndpoints(List<String> selectedEndpoints);

    abstract void setMaxFrameHeight(int maxFrameHeight);

    abstract void setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage message);

    /**
     * A node which can be placed in the pipeline to cache Data channel packets
     * until the DataChannelStack is ready to handle them.
     */
    protected static class DataChannelHandler extends ConsumerNode
    {
        protected final Object dataChannelStackLock = new Object();
        public DataChannelStack dataChannelStack = null;
        public BlockingQueue<PacketInfo> cachedDataChannelPackets = new LinkedBlockingQueue<>();

        /**
         * Initializes a new {@link DataChannelHandler} instance.
         */
        public DataChannelHandler()
        {
            super("Data channel handler");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void consume(PacketInfo packetInfo)
        {
            synchronized (dataChannelStackLock)
            {
                if (packetInfo.getPacket() instanceof DataChannelPacket)
                {
                    if (dataChannelStack == null)
                    {
                        cachedDataChannelPackets.add(packetInfo);
                    }
                    else
                    {
                        DataChannelPacket dcp = (DataChannelPacket) packetInfo.getPacket();
                        dataChannelStack.onIncomingDataChannelPacket(
                                ByteBuffer.wrap(dcp.getBuffer()), dcp.sid, dcp.ppid);
                    }
                }
            }
        }

        /**
         * Sets the data channel stack
         */
        public void setDataChannelStack(DataChannelStack dataChannelStack)
        {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.submit(() -> {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via
                // #doProcessPackets and processing packets at the same time in
                // another thread, which would be a problem.
                synchronized (dataChannelStackLock)
                {
                    this.dataChannelStack = dataChannelStack;
                    cachedDataChannelPackets.forEach(packetInfo -> {
                        DataChannelPacket dcp
                                = (DataChannelPacket)packetInfo.getPacket();
                        //TODO(brian): have datachannelstack accept
                        // DataChannelPackets?
                        dataChannelStack.onIncomingDataChannelPacket(
                                ByteBuffer.wrap(dcp.getBuffer()), dcp.sid, dcp.ppid);
                    });
                }
            });
        }

        @Override
        public void trace(@NotNull Function0<Unit> f)
        {
            f.invoke();
        }
    }

    /**
     * A node which can be placed in the pipeline to cache SCTP packets until
     * the SCTPManager is ready to handle them.
     */
    protected static class SctpHandler extends ConsumerNode
    {
        protected final Object sctpManagerLock = new Object();
        public SctpManager sctpManager = null;
        public BlockingQueue<PacketInfo> cachedSctpPackets = new LinkedBlockingQueue<>(100);
        protected AtomicLong numCachedSctpPackets = new AtomicLong();

        /**
         * Initializes a new {@link SctpHandler} instance.
         */
        public SctpHandler()
        {
            super("SCTP handler");
        }

        @Override
        protected void consume(@NotNull PacketInfo packetInfo)
        {
            synchronized (sctpManagerLock)
            {
                if (SctpConfig.config.enabled())
                {
                    if (sctpManager == null)
                    {
                        numCachedSctpPackets.incrementAndGet();
                        cachedSctpPackets.add(packetInfo);
                    }
                    else
                    {
                        sctpManager.handleIncomingSctp(packetInfo);
                    }
                }
            }
        }

        @Override
        public NodeStatsBlock getNodeStats()
        {
            NodeStatsBlock nodeStats = super.getNodeStats();
            nodeStats.addNumber("num_cached_packets", numCachedSctpPackets.get());
            return nodeStats;
        }

        /**
         * Sets the SCTP manager of this endpoint.
         */
        public void setSctpManager(SctpManager sctpManager)
        {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.submit(() -> {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via
                // #doProcessPackets and processing packets at the same time in
                // another thread, which would be a problem.
                synchronized (sctpManagerLock)
                {
                    this.sctpManager = sctpManager;
                    cachedSctpPackets.forEach(sctpManager::handleIncomingSctp);
                    cachedSctpPackets.clear();
                }
            });
        }

        @Override
        public void trace(@NotNull Function0<Unit> f)
        {
            f.invoke();
        }
    }
}
