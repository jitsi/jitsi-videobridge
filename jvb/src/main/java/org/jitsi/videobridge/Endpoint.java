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
        setupIceTransport();
        dtlsTransport = new DtlsTransport(logger);
        setupDtlsTransport();

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
    void lastNEndpointsChanged()
    {
        bitrateController.endpointOrderingChanged();
    }

    /**
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     */
    @Override
    public void sendMessage(BridgeChannelMessage msg)
    {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.sendMessage(msg);
        }
    }

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

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     */
    protected void dtlsAppPacketReceived(byte[] data, int off, int len)
    {
        //TODO(brian): change sctp handler to take buf, off, len
        sctpHandler.processPacket(new PacketInfo(new UnparsedPacket(data, off, len)));
    }

    public void endpointMessageTransportConnected()
    {
        sendVideoConstraints(this.maxReceiverVideoConstraints);
    }

    protected void effectiveVideoConstraintsChanged(
        Map<String, VideoConstraints> oldEffectiveConstraints,
        Map<String, VideoConstraints> newEffectiveConstraints)
    {
        Set<String> removedEndpoints = new HashSet<>(oldEffectiveConstraints.keySet());
        removedEndpoints.removeAll(newEffectiveConstraints.keySet());

        // Sources that "this" endpoint no longer receives.
        for (String id : removedEndpoints)
        {
            AbstractEndpoint senderEndpoint = getConference().getEndpoint(id);
            if (senderEndpoint != null)
            {
                senderEndpoint.removeReceiver(getId());
            }
        }

        // Added or updated.
        newEffectiveConstraints.forEach((endpointId, effectiveConstraints) -> {
            AbstractEndpoint senderEndpoint = getConference().getEndpoint(endpointId);
            if (senderEndpoint != null)
            {
                senderEndpoint.addReceiver(getId(), effectiveConstraints);
            }
        });

    }

    @Override
    protected void sendVideoConstraints(@NotNull VideoConstraints maxVideoConstraints)
    {
        // Note that it's up to the client to respect these constraints.
        if (ArrayUtils.isNullOrEmpty(getMediaSources()))
        {
            logger.debug("Suppressing sending a SenderVideoConstraints message, endpoint has no streams.");
        }
        else
        {
            SenderVideoConstraintsMessage senderVideoConstraintsMessage
                    = new SenderVideoConstraintsMessage(maxVideoConstraints.getMaxHeight());

            logger.debug(() -> "Sender constraints changed: " + senderVideoConstraintsMessage.toJson());

            sendMessage(senderVideoConstraintsMessage);
        }
    }

    /**
     * TODO Brian
     */
    public void createSctpConnection()
    {
        logger.debug(() -> "Creating SCTP mananger");
        // Create the SctpManager and provide it a method for sending SCTP data
        this.sctpManager = new SctpManager(
            (data, offset, length) -> {
                dtlsTransport.sendDtlsData(data, offset, length);
                return 0;
            },
            logger
        );
        sctpHandler.setSctpManager(sctpManager);
        // NOTE(brian): as far as I know we always act as the 'server' for sctp
        // connections, but if not we can make which type we use dynamic
        SctpServerSocket socket = sctpManager.createServerSocket();
        socket.eventHandler = new SctpSocket.SctpSocketEventHandler()
        {
            @Override
            public void onReady()
            {
                logger.info("SCTP connection is ready, creating the Data channel stack");
                dataChannelStack
                    = new DataChannelStack(
                        (data, sid, ppid) -> socket.send(data, true, sid, ppid),
                        logger
                    );
                dataChannelStack.onDataChannelStackEvents(dataChannel ->
                {
                    logger.info("Remote side opened a data channel.");
                    Endpoint.this.messageTransport.setDataChannel(dataChannel);
                });
                dataChannelHandler.setDataChannelStack(dataChannelStack);
                if (OPEN_DATA_LOCALLY)
                {
                    logger.info("Will open the data channel.");
                    DataChannel dataChannel
                        = dataChannelStack.createDataChannel(
                            DataChannelProtocolConstants.RELIABLE,
                            0,
                            0,
                            0,
                            "default");
                    Endpoint.this.messageTransport.setDataChannel(dataChannel);
                    dataChannel.open();
                }
                else
                {
                    logger.info("Will wait for the remote side to open the data channel.");
                }
            }

            @Override
            public void onDisconnected()
            {
                logger.info("SCTP connection is disconnected.");
            }
        };
        socket.dataCallback = (data, sid, ssn, tsn, ppid, context, flags) -> {
            // We assume all data coming over SCTP will be datachannel data
            DataChannelPacket dcp = new DataChannelPacket(data, 0, data.length, sid, (int)ppid);
            // Post the rest of the task here because the current context is
            // holding a lock inside the SctpSocket which can cause a deadlock
            // if two endpoints are trying to send datachannel messages to one
            // another (with stats broadcasting it can happen often)
            TaskPools.IO_POOL.execute(() -> dataChannelHandler.consume(new PacketInfo(dcp)));
        };
        socket.listen();
        sctpSocket = Optional.of(socket);
    }

    protected void acceptSctpConnection(SctpServerSocket sctpServerSocket)
    {
        TaskPools.IO_POOL.submit(() -> {
            // We don't want to block the thread calling
            // onDtlsHandshakeComplete so run the socket acceptance in an IO
            // pool thread
            // FIXME: This runs forever once the socket is closed (
            // accept never returns true).
            logger.info("Attempting to establish SCTP socket connection");
            int attempts = 0;
            while (!sctpServerSocket.accept())
            {
                attempts++;
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    break;
                }

                if (attempts > 100)
                {
                    logger.error("Timed out waiting for SCTP connection from remote side");
                    break;
                }
            }
            if (logger.isDebugEnabled())
            {
                logger.debug("SCTP socket " + sctpServerSocket.hashCode() +
                    " accepted connection.");
            }
        });
    }

    /**
     * Schedule a timeout to fire log a message and track a stat if we don't
     * have an endpoint message transport connected within the timeout.
     */
    protected void scheduleEndpointMessageTransportTimeout()
    {
        TaskPools.SCHEDULED_POOL.schedule(() -> {
            if (!isExpired()) {
                EndpointMessageTransport t = getMessageTransport();
                if (t != null)
                {
                    if (!t.isConnected())
                    {
                        logger.error("EndpointMessageTransport still not connected.");
                        getConference()
                            .getVideobridge()
                            .getStatistics()
                            .numEndpointsNoMessageTransportAfterDelay.incrementAndGet();
                    }
                }
            }
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * Checks whether a WebSocket connection with a specific password string
     * should be accepted for this {@link Endpoint}.
     * @param password the
     * @return {@code true} iff the password matches.
     */
    public boolean acceptWebSocket(String password)
    {
        String icePassword = getIcePassword();
        if (!icePassword.equals(password))
        {
            logger.warn("Incoming web socket request with an invalid password. " +
                    "Expected: " + icePassword + ", received " + password);
            return false;
        }

        return true;
    }

    /**
     * @return the password of the ICE Agent associated with this
     * {@link Endpoint}.
     */
    protected String getIcePassword()
    {
        return iceTransport.getIcePassword();
    }

    /**
     * Sends a message to this {@link Endpoint} in order to notify it that the set of endpoints for which the bridge
     * is sending video has changed.
     *
     * @param forwardedEndpoints the collection of forwarded endpoints.
     */
    protected void sendForwardedEndpointsMessage(Collection<String> forwardedEndpoints)
    {
        ForwardedEndpointsMessage msg = new ForwardedEndpointsMessage(forwardedEndpoints);

        TaskPools.IO_POOL.submit(() -> {
            try
            {
                sendMessage(msg);
            }
            catch (Exception e)
            {
                logger.warn("Failed to send a message: ", e);
            }
        });
    }

    /**
     * Sets the remote transport information (ICE candidates, DTLS fingerprints).
     *
     * @param transportInfo the XML extension which contains the remote
     * transport information.
     */
    public void setTransportInfo(IceUdpTransportPacketExtension transportInfo)
    {
        List<DtlsFingerprintPacketExtension> fingerprintExtensions
                = transportInfo.getChildExtensionsOfType(DtlsFingerprintPacketExtension.class);
        Map<String, String> remoteFingerprints = new HashMap<>();
        fingerprintExtensions.forEach(fingerprintExtension -> {
            if (fingerprintExtension.getHash() != null && fingerprintExtension.getFingerprint() != null)
            {
                remoteFingerprints.put(
                        fingerprintExtension.getHash(),
                        fingerprintExtension.getFingerprint());
            }
            else
            {
                logger.info("Ignoring empty DtlsFingerprint extension: " + transportInfo.toXML());
            }
        });
        dtlsTransport.setRemoteFingerprints(remoteFingerprints);
        if (!fingerprintExtensions.isEmpty())
        {
            String setup = fingerprintExtensions.get(0).getSetup();
            dtlsTransport.setSetupAttribute(setup);
        }

        iceTransport.startConnectivityEstablishment(transportInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
    {
        IceUdpTransportPacketExtension iceUdpTransportPacketExtension = new IceUdpTransportPacketExtension();
        iceTransport.describe(iceUdpTransportPacketExtension);
        dtlsTransport.describe(iceUdpTransportPacketExtension);
        ColibriWebSocketService colibriWebSocketService = ColibriWebSocketServiceSupplierKt.singleton().get();
        if (colibriWebSocketService != null)
        {
            String wsUrl = colibriWebSocketService.getColibriWebSocketUrl(
                    getConference().getID(),
                    getId(),
                    iceTransport.getIcePassword()
            );
            if (wsUrl != null)
            {
                WebSocketPacketExtension wsPacketExtension = new WebSocketPacketExtension(wsUrl);
                iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension);
            }
        }
        logger.debug(() -> "Transport description:\n " + iceUdpTransportPacketExtension.toXML());
        channelBundle.setTransport(iceUdpTransportPacketExtension);
    }

    /**
     * Sets the media sources.
     * @param mediaSources
     */
    protected abstract void setMediaSources(MediaSourceDesc[] mediaSources);

    /**
     * Re-creates this endpoint's media sources based on the sources
     * and source groups that have been signaled.
     */
    @Override
    public void recreateMediaSources()
    {
        final Supplier<Stream<ChannelShim>> videoChannels = () -> channelShims
            .stream()
            .filter(c -> MediaType.VIDEO.equals(c.getMediaType()));

        final List<SourcePacketExtension> sources = videoChannels
            .get()
            .map(ChannelShim::getSources)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

        final List<SourceGroupPacketExtension> sourceGroups = videoChannels
            .get()
            .map(ChannelShim::getSourceGroups)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

        if (!sources.isEmpty() || !sourceGroups.isEmpty())
        {
            MediaSourceDesc[] mediaSources =
                MediaSourceFactory.createMediaSources(
                    sources,
                    sourceGroups);
            setMediaSources(mediaSources);
        }
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     * @param packetInfo the packet.
     */
    protected void handleIncomingPacket(PacketInfo packetInfo)
    {
        packetInfo.setEndpointId(getId());
        getConference().handleIncomingPacket(packetInfo);
    }

    /**
     * @return  the timestamp of the most recently created channel shim.
     */
    Instant getMostRecentChannelCreatedTime()
    {
        return channelShims.stream()
            .map(ChannelShim::getCreationTimestamp)
            .max(Comparator.comparing(Function.identity()))
            .orElse(ClockUtils.NEVER);
    }

    /**
     * Adds a channel to this endpoint.
     * @param channelShim
     */
    public void addChannel(ChannelShim channelShim)
    {
        if (channelShims.add(channelShim))
        {
            updateAcceptedMediaTypes();
        }
    }

    /**
     * Removes a specific {@link ChannelShim} from this endpoint.
     * @param channelShim
     */
    public void removeChannel(ChannelShim channelShim)
    {
        if (channelShims.remove(channelShim))
        {
            if (channelShims.isEmpty())
            {
                expire();
            }
            else
            {
                updateAcceptedMediaTypes();
            }
        }
    }

    /**
     * Update media direction of {@link ChannelShim}s associated
     * with this Endpoint.
     *
     * When media direction is set to 'sendrecv' JVB will
     * accept incoming media from endpoint and forward it to
     * other endpoints in a conference. Other endpoint's media
     * will also be forwarded to current endpoint.
     * When media direction is set to 'sendonly' JVB will
     * NOT accept incoming media from this endpoint (not yet implemented), but
     * media from other endpoints will be forwarded to this endpoint.
     * When media direction is set to 'recvonly' JVB will
     * accept incoming media from this endpoint, but will not forward
     * other endpoint's media to this endpoint.
     * When media direction is set to 'inactive' JVB will
     * neither accept incoming media nor forward media from other endpoints.
     *
     * @param type media type.
     * @param direction desired media direction:
     *                       'sendrecv', 'sendonly', 'recvonly', 'inactive'
     */
    @SuppressWarnings("unused") // Used by plugins (Yuri)
    public void updateMediaDirection(MediaType type, String direction)
    {
        switch (direction)
        {
            case "sendrecv":
            case "sendonly":
            case "recvonly":
            case "inactive":
            {
                for (ChannelShim channelShim : channelShims)
                {
                    if (channelShim.getMediaType() == type)
                    {
                        channelShim.setDirection(direction);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Media direction unknown: " + direction);
        }
    }

    public abstract Transceiver getTransceiver();

    /**
     * Update accepted media types based on
     * {@link ChannelShim} permission to receive
     * media
     */
    public void updateAcceptedMediaTypes()
    {
        boolean acceptAudio = false;
        boolean acceptVideo = false;
        for (ChannelShim channelShim : channelShims)
        {
            // The endpoint accepts audio packets (in the sense of accepting
            // packets from other endpoints being forwarded to it) if it has
            // an audio channel whose direction allows sending packets.
            if (channelShim.allowsSendingMedia())
            {
                switch (channelShim.getMediaType())
                {
                    case AUDIO:
                        acceptAudio = true;
                        break;
                    case VIDEO:
                        acceptVideo = true;
                        break;
                    default:
                        break;
                }
            }
        }
        this.acceptAudio = acceptAudio;
        this.acceptVideo = acceptVideo;
    }

    public abstract void updateForceMute();

    /**
     * Returns how many endpoints this Endpoint is currently forwarding video for
     */
    public int numForwardedEndpoints()
    {
        return bitrateController.numForwardedEndpoints();
    }

    /**
     * Enables/disables the given feature, if the endpoint implementation supports it.
     *
     * @param feature the feature to enable or disable.
     * @param enabled the state of the feature.
     */
    public abstract void setFeature(EndpointDebugFeatures feature, boolean enabled);

    public abstract boolean isFeatureEnabled(EndpointDebugFeatures feature);

    public boolean isOversending()
    {
        return bitrateController.isOversending();
    }

    void setSelectedEndpoints(List<String> selectedEndpoints)
    {
        bitrateController.setSelectedEndpoints(selectedEndpoints);
    }

    void setMaxFrameHeight(int maxFrameHeight)
    {
        bitrateController.setMaxFrameHeight(maxFrameHeight);
    }

    void setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage message)
    {
        bitrateController.setBandwidthAllocationSettings(message);
    }

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
