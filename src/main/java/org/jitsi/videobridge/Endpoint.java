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

import com.google.common.collect.*;
import kotlin.*;
import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.bandwidthestimation.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.osgi.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.rest.root.colibri.debug.*;
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
import org.json.simple.*;

import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
public class Endpoint
    extends AbstractEndpoint implements PotentialPacketHandler,
        EncodingsManager.EncodingsUpdateListener
{
    /**
     * Track how long it takes for all RTP and RTCP packets to make their way through the bridge.
     * Since {@link Endpoint} is the 'last place' that is aware of {@link PacketInfo} in the outgoing
     * chain, we track this stats here.  Since they're static, these members will track the delay
     * for packets going out to all endpoints.
     */
    private static final PacketDelayStats rtpPacketDelayStats = new PacketDelayStats();
    private static final PacketDelayStats rtcpPacketDelayStats = new PacketDelayStats();

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
    static final CountingErrorHandler queueErrorCounter
        = new CountingErrorHandler();

    /**
     * Measures the jitter introduced by the bridge itself (i.e. jitter calculated between
     * packets based on the time they were received by the bridge and the time they
     * are sent).  This jitter value is calculated independently, per packet, by every
     * individual {@link Endpoint} and their jitter values are averaged together
     * in this static member.
     */
    private final BridgeJitterStats bridgeJitterStats = new BridgeJitterStats();

    /**
     * The {@link SctpManager} instance we'll use to manage the SCTP connection
     */
    private SctpManager sctpManager;

    /**
     * The time at which this endpoint was created (in millis since epoch)
     */
    private final Instant creationTime;

    /**
     * How long we'll give an endpoint to either successfully establish
     * an ICE connection or fail before we expire it.
     */
    //TODO: make this configurable
    private static final Duration EP_TIMEOUT = Duration.ofMinutes(2);

    /**
     * TODO Brian
     */
    private final SctpHandler sctpHandler = new SctpHandler();

    /**
     * TODO Brian
     */
    private DataChannelStack dataChannelStack;

    /**
     * TODO Brian
     */
    private final DataChannelHandler dataChannelHandler
            = new DataChannelHandler();

    /**
     * The instance which manages the Colibri messaging (over a data channel
     * or web sockets).
     */
    @NotNull
    private final EndpointMessageTransport messageTransport;

    /**
     * The diagnostic context of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The bitrate controller.
     */
    private final BitrateController bitrateController;

    /**
     * TODO Brian
     */
    private final BandwidthProbing bandwidthProbing;

    @NotNull
    private final IceTransport iceTransport;

    /**
     * This {@link Endpoint}'s DTLS transport.
     */
    @NotNull
    private final DtlsTransport dtlsTransport;

    /**
     * The {@link Transceiver} which handles receiving and sending of (S)RTP.
     */
    private final Transceiver transceiver;

    /**
     * The set of {@link ChannelShim}s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed. The set of channels shims allows to determine if endpoint
     * can accept audio or video.
     */
    private final Set<ChannelShim> channelShims = ConcurrentHashMap.newKeySet();

    /**
     * Whether this endpoint should accept audio packets. We set this according
     * to whether the endpoint has an audio Colibri channel whose direction
     * allows sending.
     */
    private volatile boolean acceptAudio = false;

    /**
     * Whether this endpoint should accept video packets. We set this according
     * to whether the endpoint has a video Colibri channel whose direction
     * allows sending.
     */
    private volatile boolean acceptVideo = false;

    /**
     * The clock used by this endpoint
     */
    private final Clock clock;

    /**
     * Whether or not the bridge should be the peer which opens the data channel
     * (as opposed to letting the far peer/client open it).
     */
    private static final boolean OPEN_DATA_LOCALLY = false;

    /**
     * The executor which runs bandwidth probing.
     *
     * TODO (brian): align the recurringRunnable stuff with whatever we end up
     * doing with all the other executors.
     */
    private static final RecurringRunnableExecutor recurringRunnableExecutor =
            new RecurringRunnableExecutor(Endpoint.class.getSimpleName());

    /**
     * The queue we put outgoing SRTP packets onto so they can be sent
     * out via the {@link IceTransport} on an IO thread.
     */
    private final PacketInfoQueue outgoingSrtpPacketQueue;

    /**
     * The {@link SctpSocket} for this endpoint, if an SCTP connection was
     * negotiated.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Optional<SctpServerSocket> sctpSocket = Optional.empty();

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
    public Endpoint(
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
        transceiver = new Transceiver(
            id,
            TaskPools.CPU_POOL,
            TaskPools.CPU_POOL,
            TaskPools.SCHEDULED_POOL,
            diagnosticContext,
            logger,
            clock
        );
        transceiver.setIncomingPacketHandler(
            new ConsumerNode("receiver chain handler")
            {
                @Override
                protected void consume(@NotNull PacketInfo packetInfo)
                {
                    handleIncomingPacket(packetInfo);
                }

                @Override
                public void trace(@NotNull Function0<Unit> f)
                {
                    f.invoke();
                }
            });
        bitrateController = new BitrateController(this, diagnosticContext, logger);

        outgoingSrtpPacketQueue = new PacketInfoQueue(
            getClass().getSimpleName() + "-outgoing-packet-queue",
            TaskPools.IO_POOL,
            this::doSendSrtp,
            TransportConfig.Config.queueSize()
        );
        outgoingSrtpPacketQueue.setErrorHandler(queueErrorCounter);

        messageTransport = new EndpointMessageTransport(
            this,
            () -> getConference().getVideobridge().getStatistics(),
            getConference(),
            logger
        );

        diagnosticContext.put("endpoint_id", id);
        bandwidthProbing
            = new BandwidthProbing(Endpoint.this.transceiver::sendProbing);
        bandwidthProbing.setDiagnosticContext(diagnosticContext);
        bandwidthProbing.setBitrateController(bitrateController);
        transceiver.setAudioLevelListener(conference.getAudioLevelListener());
        transceiver.onBandwidthEstimateChanged(newValueBps ->
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Estimated bandwidth is now " + newValueBps + " bps.");
            }
            bitrateController.bandwidthChanged((long)newValueBps.getBps());
        });
        transceiver.onBandwidthEstimateChanged(bandwidthProbing);
        conference.encodingsManager.subscribe(this);

        bandwidthProbing.enabled = true;
        recurringRunnableExecutor.registerRecurringRunnable(bandwidthProbing);

        iceTransport = new IceTransport(getID(), iceControlling, logger);
        setupIceTransport();
        dtlsTransport = new DtlsTransport(logger);
        setupDtlsTransport();

        if (conference.includeInStatistics())
        {
            conference.getVideobridge().getStatistics()
                .totalEndpoints.incrementAndGet();
        }
    }

    public Endpoint(
        String id,
        Conference conference,
        Logger parentLogger,
        boolean iceControlling)
    {
        this(id, conference, parentLogger, iceControlling, Clock.systemUTC());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EndpointMessageTransport getMessageTransport()
    {
        return messageTransport;
    }

    private void setupIceTransport()
    {
        iceTransport.incomingDataHandler = new IceTransport.IncomingDataHandler() {
            @Override
            public void dataReceived(@NotNull byte[] data, int offset, int length, @NotNull Instant receivedTime) {
                // DTLS data will be handled by the DtlsTransport, but SRTP data can go
                // straight to the transceiver
                if (PacketUtils.looksLikeDtls(data, offset, length))
                {
                    // DTLS transport is responsible for making its own copy, because it will manage its own
                    // buffers
                    dtlsTransport.dtlsDataReceived(data, offset, length);
                }
                else
                {
                    byte[] copy = ByteBufferPool.getBuffer(
                        length +
                            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                            RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET
                    );
                    System.arraycopy(data, offset, copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length);
                    Packet pkt = new UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length);
                    PacketInfo pktInfo = new PacketInfo(pkt);
                    pktInfo.setReceivedTime(receivedTime.toEpochMilli());
                    transceiver.handleIncomingPacket(pktInfo);
                }
            }
        };
        iceTransport.eventHandler = new IceTransport.EventHandler() {
            @Override
            public void connected() {
                logger.info("ICE connected");
                getConference().getStatistics().hasIceSucceededEndpoint = true;
                getConference().getVideobridge().getStatistics().totalIceSucceeded.incrementAndGet();
                transceiver.setOutgoingPacketHandler(outgoingSrtpPacketQueue::add);
                TaskPools.IO_POOL.submit(iceTransport::startReadingData);
                TaskPools.IO_POOL.submit(dtlsTransport::startDtlsHandshake);
            }

            @Override
            public void failed() {
                getConference().getStatistics().hasIceFailedEndpoint = true;
                getConference().getVideobridge().getStatistics().totalIceFailed.incrementAndGet();
            }

            @Override
            public void consentUpdated(@NotNull Instant time) {
                getTransceiver().getPacketIOActivity().setLastIceActivityInstant(time);
            }
        };
    }

    private void setupDtlsTransport()
    {
        dtlsTransport.incomingDataHandler = this::dtlsAppPacketReceived;
        dtlsTransport.outgoingDataHandler = iceTransport::send;
        dtlsTransport.eventHandler = (chosenSrtpProtectionProfile, tlsRole, keyingMaterial) ->
        {
            logger.info("DTLS handshake complete");
            transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial);
            //TODO(brian): the old code would work even if the sctp connection was created after
            // the handshake had completed, but this won't (since this is a one-time event).  do
            // we need to worry about that case?
            sctpSocket.ifPresent(socket -> acceptSctpConnection(socket));
            scheduleEndpointMessageTransportTimeout();
        };
    }

    private boolean doSendSrtp(PacketInfo packetInfo)
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
     * Notifies this {@code Endpoint} that the list of {@code Endpoint}s ordered
     * by speech activity (i.e. the dominant speaker history) has changed.
     */
    void speechActivityEndpointsChanged(List<String> endpoints)
    {
        bitrateController.endpointOrderingChanged(endpoints);
    }

    /**
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     */
    @Override
    public void sendMessage(String msg)
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
    public void setLastN(Integer lastN)
    {
        bitrateController.setLastN(lastN);
    }

    /**
     * Gets the LastN value for this endpoint.
     */
    public int getLastN()
    {
        return bitrateController.getLastN();
    }

    /**
     * Sets the local SSRC for this endpoint.
     * @param mediaType
     * @param ssrc
     */
    public void setLocalSsrc(MediaType mediaType, long ssrc)
    {
        transceiver.setLocalSsrc(mediaType, ssrc);
    }

    /**
     * Checks if this endpoint's DTLS transport is connected.
     * @return
     */
    private boolean isTransportConnected()
    {
        return iceTransport.isConnected() && dtlsTransport.isConnected();
    }

    public double getRtt()
    {
        return getTransceiver().getTransceiverStats().getEndpointConnectionStats().getRtt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wants(PacketInfo packetInfo)
    {
        if (!isTransportConnected())
        {
            return false;
        }

        Packet packet = Objects.requireNonNull(packetInfo.getPacket(), "packet");

        if (packet instanceof RtpPacket)
        {
            if (packet instanceof VideoRtpPacket)
            {
                return acceptVideo
                        && bitrateController.accept(packetInfo);
            }
            if (packet instanceof AudioRtpPacket)
            {
                return acceptAudio;
            }
        }
        else if (packet instanceof RtcpPacket)
        {
            if (packet instanceof RtcpSrPacket)
            {
                // TODO: For SRs we're only interested in the ntp/rtp timestamp
                //  association, so we could only accept srs from the main ssrc
                return bitrateController.accept((RtcpSrPacket) packet);
            }
            else if (packet instanceof RtcpFbPliPacket ||
                     packet instanceof RtcpFbFirPacket)
            {
                // We assume that we are only given PLIs/FIRs destined for this
                // endpoint. This is because Conference has to find the target
                // endpoint (this endpoint) anyway, and we would essentially be
                // performing the same check twice.
                return true;
            }
            else
            {
                logger.warn("Ignoring an rtcp packet of type"
                    + packet.getClass().getSimpleName());
                return false;
            }
        }

        logger.warn("Ignoring an unknown packet type:"
            + packet.getClass().getSimpleName());
        return false;
    }

    /**
     * TODO Brian
     */
    @Override
    public void send(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        if (packet instanceof VideoRtpPacket)
        {
            boolean accepted = bitrateController.transformRtp(packetInfo);
            if (!accepted)
            {
                logger.warn(
                    "Dropping a packet which was supposed to be accepted:"
                        + packet);
                return;
            }

            // The original packet was transformed in place.
            transceiver.sendPacket(packetInfo);

            return;
        }
        else if (packet instanceof RtcpSrPacket)
        {
            // Allow the BC to update the timestamp (in place).
            RtcpSrPacket rtcpSrPacket = (RtcpSrPacket) packet;
            bitrateController.transformRtcp(rtcpSrPacket);

            if (logger.isTraceEnabled())
            {
                logger.trace(
                    "relaying an sr from ssrc="
                        + rtcpSrPacket.getSenderSsrc()
                        + ", timestamp="
                        + rtcpSrPacket.getSenderInfo().getRtpTimestamp());
            }
        }

        transceiver.sendPacket(packetInfo);
    }

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     */
    private void dtlsAppPacketReceived(byte[] data, int off, int len)
    {
        //TODO(brian): change sctp handler to take buf, off, len
        sctpHandler.consume(new PacketInfo(new UnparsedPacket(data, off, len)));
    }

    /**
     * Adds a payload type to this endpoint.
     */
    @Override
    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
        bitrateController.addPayloadType(payloadType);
    }

    @Override
    public void addRtpExtension(RtpExtension rtpExtension)
    {
        transceiver.addRtpExtension(rtpExtension);
    }

    @Override
    public void setSenderVideoConstraints(ImmutableMap<String, VideoConstraints> newVideoConstraints)
    {
        ImmutableMap<String, VideoConstraints>
            oldVideoConstraints = bitrateController.getVideoConstraints();

        bitrateController.setVideoConstraints(newVideoConstraints);

        Set<String> removedEndpoints
            = new HashSet<>(oldVideoConstraints.keySet());
        removedEndpoints.removeAll(newVideoConstraints.keySet());

        // Endpoints that "this" no longer cares about what it receives.
        for (String id : removedEndpoints)
        {
            AbstractEndpoint senderEndpoint = getConference().getEndpoint(id);
            if (senderEndpoint != null)
            {
                senderEndpoint.removeReceiver(getID());
            }
        }

        // Added or updated.
        for (Map.Entry<String, VideoConstraints>
            videoConstraintsEntry : newVideoConstraints.entrySet())
        {
            AbstractEndpoint senderEndpoint
                = getConference().getEndpoint(videoConstraintsEntry.getKey());

            if (senderEndpoint != null)
            {
                senderEndpoint.addReceiver(
                    getID(), videoConstraintsEntry.getValue());
            }
        }
    }

    @Override
    protected void maxReceiverVideoConstraintsChanged(VideoConstraints maxVideoConstraints)
    {
        // Note that it's up to the client to respect these constraints.
        String senderVideoConstraintsMessage
                = new SenderVideoConstraintsMessage(maxVideoConstraints).toJson();

        if (logger.isDebugEnabled())
        {
            logger.debug("Sender constraints changed: " + senderVideoConstraintsMessage);
        }

        sendMessage(senderVideoConstraintsMessage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant getLastIncomingActivity()
    {
        PacketIOActivity packetIOActivity
                = this.transceiver.getPacketIOActivity();
        return packetIOActivity.getLastIncomingActivityInstant();
    }

    /**
     * Previously, an endpoint expired when all of its channels did.  Channels
     * now only exist in their 'shim' form for backwards compatibility, so to
     * find out whether or not the endpoint expired, we'll check the activity
     * timestamps from the transceiver and use the largest of the expire times
     * set in the channel shims.
     */
    @Override
    public boolean shouldExpire()
    {
        boolean iceFailed = iceTransport.hasFailed();
        if (iceFailed)
        {
            logger.warn("Allowing to expire because ICE failed.");
            return true;
        }

        Duration maxExpireTimeFromChannelShims = channelShims.stream()
                .map(ChannelShim::getExpire)
                .map(Duration::ofSeconds)
                .max(Comparator.comparing(Function.identity()))
                .orElse(Duration.ofSeconds(0));

        Instant lastActivity = getLastIncomingActivity();
        Instant now = clock.instant();
        if (lastActivity == ClockUtils.NEVER)
        {
            Duration timeSinceCreation = Duration.between(creationTime, now);
            if (timeSinceCreation.compareTo(EP_TIMEOUT) > 0) {
                logger.info("Endpoint's ICE connection has neither failed nor connected " +
                    "after " + timeSinceCreation + ", expiring");
                return true;
            }
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
            return false;
        }

        if (Duration.between(lastActivity, now).compareTo(maxExpireTimeFromChannelShims) > 0)
        {
            logger.info("Allowing to expire because of no activity in over " +
                    maxExpireTimeFromChannelShims);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expire()
    {
        if (super.isExpired())
        {
            return;
        }
        super.expire();

        try
        {
            final ChannelShim[] channelShims = this.channelShims.toArray(new ChannelShim[0]);
            this.channelShims.clear();

            for (ChannelShim channelShim : channelShims)
            {
                if (!channelShim.isExpired())
                {
                    channelShim.setExpire(0);
                }
            }

            updateStatsOnExpire();
            this.transceiver.stop();
            if (logger.isDebugEnabled() && getConference().includeInStatistics())
            {
                logger.debug(transceiver.getNodeStats().prettyPrint(0));
                logger.debug(bitrateController.getDebugState().toJSONString());
                logger.debug(iceTransport.getDebugState().toJSONString());
                logger.debug(dtlsTransport.getDebugState().toJSONString());
            }

            transceiver.teardown();

            AbstractEndpointMessageTransport messageTransport
                    = getMessageTransport();
            if (messageTransport != null)
            {
                messageTransport.close();
            }
            if (sctpManager != null)
            {
                sctpManager.closeConnection();
            }
        }
        catch (Exception e)
        {
            logger.error("Exception while expiring: ", e);
        }
        bandwidthProbing.enabled = false;
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing);
        getConference().encodingsManager.unsubscribe(this);

        dtlsTransport.stop();
        iceTransport.stop();

        outgoingSrtpPacketQueue.close();

        logger.info("Expired.");
    }

    /**
     * Updates the conference statistics with value from this endpoint. Since
     * the values are cumulative this should execute only once when the endpoint
     * expires.
     */
    private void updateStatsOnExpire()
    {
        Conference.Statistics conferenceStats = getConference().getStatistics();
        TransceiverStats transceiverStats = transceiver.getTransceiverStats();
        PacketStreamStats.Snapshot incomingStats
                = transceiverStats.getIncomingPacketStreamStats();
        PacketStreamStats.Snapshot outgoingStats
                = transceiverStats.getOutgoingPacketStreamStats();
        BandwidthEstimator.StatisticsSnapshot bweStats
                = transceiverStats.getBandwidthEstimatorStats();

        conferenceStats.totalBytesReceived.addAndGet(
                incomingStats.getBytes());
        conferenceStats.totalPacketsReceived.addAndGet(
                incomingStats.getPackets());
        conferenceStats.totalBytesSent.addAndGet(
                outgoingStats.getBytes());
        conferenceStats.totalPacketsSent.addAndGet(
                outgoingStats.getPackets());

        Number lossLimitedMs = bweStats.getNumber("lossLimitedMs");
        Number lossDegradedMs = bweStats.getNumber("lossDegradedMs");
        Number lossFreeMs = bweStats.getNumber("lossFreeMs");

        if (lossLimitedMs != null && lossDegradedMs != null && lossFreeMs != null)
        {
            Videobridge.Statistics videobridgeStats
                = getConference().getVideobridge().getStatistics();

            long participantMs = lossFreeMs.longValue() +
                lossDegradedMs.longValue() +
                lossLimitedMs.longValue();

            videobridgeStats.totalLossControlledParticipantMs
                .addAndGet(participantMs);
            videobridgeStats.totalLossLimitedParticipantMs
                .addAndGet(lossLimitedMs.longValue());
            videobridgeStats.totalLossDegradedParticipantMs
                .addAndGet(lossDegradedMs.longValue());
        }

        if (iceTransport.isConnected() && !dtlsTransport.isConnected())
        {
            logger.info("Expiring an endpoint with ICE, but no DTLS.");
            conferenceStats.dtlsFailedEndpoints.incrementAndGet();
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
            DataChannelPacket dcp
                    = new DataChannelPacket(data, 0, data.length, sid, (int)ppid);
            // Post the rest of the task here because the current context is
            // holding a lock inside the SctpSocket which can cause a deadlock
            // if two endpoints are trying to send datachannel messages to one
            // another (with stats broadcasting it can happen often)
            TaskPools.IO_POOL.execute(
                    () -> dataChannelHandler.consume(new PacketInfo(dcp)));
        };
        socket.listen();
        sctpSocket = Optional.of(socket);
    }

    private void acceptSctpConnection(SctpServerSocket sctpServerSocket)
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
    private void scheduleEndpointMessageTransportTimeout()
    {
        TaskPools.SCHEDULED_POOL.schedule(() -> {
            if (!isExpired()) {
                AbstractEndpointMessageTransport t = getMessageTransport();
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
    private String getIcePassword()
    {
        return iceTransport.getIcePassword();
    }

    /**
     * Sends a message to this {@link Endpoint} in order to notify it that the
     * list/set of {@code lastN} has changed.
     *
     * @param forwardedEndpoints the collection of forwarded endpoints.
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     * @param conferenceEndpoints the collection of all endpoints in the
     * conference.
     */
    public void sendLastNEndpointsChangeEvent(
        Collection<String> forwardedEndpoints,
        Collection<String> endpointsEnteringLastN,
        Collection<String> conferenceEndpoints)
    {
        // We want endpointsEnteringLastN to always to reported. Consequently,
        // we will pretend that all lastNEndpoints are entering if no explicit
        // endpointsEnteringLastN is specified.
        // XXX do we really want that?
        if (endpointsEnteringLastN == null)
        {
            endpointsEnteringLastN = forwardedEndpoints;
        }

        String msg = new ForwardedEndpointMessage(
            forwardedEndpoints, endpointsEnteringLastN, conferenceEndpoints).toJson();

        sendMessage(msg);
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
                = transportInfo.getChildExtensionsOfType(
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
                logger.info("Ignoring empty DtlsFingerprint extension: "
                        + transportInfo.toXML());
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
     * A node which can be placed in the pipeline to cache SCTP packets until
     * the SCTPManager is ready to handle them.
     */
    private static class SctpHandler extends ConsumerNode
    {
        private final Object sctpManagerLock = new Object();
        public SctpManager sctpManager = null;
        public BlockingQueue<PacketInfo> cachedSctpPackets
                = new LinkedBlockingQueue<>();

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
                if (sctpManager == null)
                {
                    cachedSctpPackets.add(packetInfo);
                }
                else
                {
                    sctpManager.handleIncomingSctp(packetInfo);
                }
            }
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

    /**
     * A node which can be placed in the pipeline to cache Data channel packets
     * until the DataChannelStack is ready to handle them.
     */
    private static class DataChannelHandler extends ConsumerNode
    {
        private final Object dataChannelStackLock = new Object();
        public DataChannelStack dataChannelStack = null;
        public BlockingQueue<PacketInfo> cachedDataChannelPackets
                = new LinkedBlockingQueue<>();

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
                        DataChannelPacket dcp
                            = (DataChannelPacket) packetInfo.getPacket();
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
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
    {
        IceUdpTransportPacketExtension iceUdpTransportPacketExtension =
                new IceUdpTransportPacketExtension();
        iceTransport.describe(iceUdpTransportPacketExtension);
        dtlsTransport.describe(iceUdpTransportPacketExtension);
        ColibriWebSocketService colibriWebSocketService = ServiceUtils2.getService(
                getConference().getBundleContext(), ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
        {
            String wsUrl = colibriWebSocketService.getColibriWebSocketUrl(
                    getConference().getID(),
                    getID(),
                    iceTransport.getIcePassword()
            );
            if (wsUrl != null)
            {
                WebSocketPacketExtension wsPacketExtension
                        = new WebSocketPacketExtension(wsUrl);
                iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension);
            }
        }
        logger.debug(() -> "Transport description:\n " + iceUdpTransportPacketExtension.toXML());
        channelBundle.setTransport(iceUdpTransportPacketExtension);
    }


    @Override
    public void requestKeyframe(long mediaSsrc)
    {
        transceiver.requestKeyFrame(mediaSsrc);
    }

    @Override
    public void requestKeyframe()
    {
        transceiver.requestKeyFrame();
    }

    /**
     * Sets the media sources.
     * @param mediaSources
     */
    private void setMediaSources(MediaSourceDesc[] mediaSources)
    {
        if (transceiver.setMediaSources(mediaSources))
        {
            getConference().endpointSourcesChanged(this);
        }
    }

    /**
     * Gets the media sources for this endpoint.
     * @return
     */
    @Override
    public MediaSourceDesc[] getMediaSources()
    {
        return transceiver.getMediaSources();
    }

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

        if (!sources.isEmpty() || !sourceGroups.isEmpty()) {
            MediaSourceDesc[] mediaSources =
                MediaSourceFactory.createMediaSources(
                    sources,
                    sourceGroups);
            setMediaSources(mediaSources);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receivesSsrc(long ssrc)
    {
        return transceiver.receivesSsrc(ssrc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addReceiveSsrc(long ssrc, MediaType mediaType)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Adding receive ssrc " + ssrc + " of type " + mediaType);
        }
        transceiver.addReceiveSsrc(ssrc, mediaType);
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     * @param packetInfo the packet.
     */
    private void handleIncomingPacket(PacketInfo packetInfo)
    {
        packetInfo.setEndpointId(getID());
        getConference().handleIncomingPacket(packetInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNewSsrcAssociation(
            String endpointId,
            long primarySsrc,
            long secondarySsrc,
            SsrcAssociationType type)
    {
        if (endpointId.equalsIgnoreCase(getID()))
        {
            transceiver.addSsrcAssociation(new LocalSsrcAssociation(primarySsrc, secondarySsrc, type));
        }
        else
        {
            transceiver.addSsrcAssociation(new RemoteSsrcAssociation(primarySsrc, secondarySsrc, type));
        }
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
    public void updateMediaDirection(MediaType type, String direction) {
        switch (direction) {
            case "sendrecv":
            case "sendonly":
            case "recvonly":
            case "inactive": {
                for (ChannelShim channelShim : channelShims) {
                    if (channelShim.getMediaType() == type) {
                        channelShim.setDirection(direction);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Media direction unknown: " + direction);
        }
    }

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

    public void updateForceMute()
    {
        boolean audioForcedMuted = false;
        for (ChannelShim channelShim : channelShims)
        {
            if (!channelShim.allowIncomingMedia())
            {
                switch (channelShim.getMediaType())
                {
                    case AUDIO:
                        audioForcedMuted = true;
                        break;
                    case VIDEO:
                        logger.warn(() -> "Tried to mute the incoming video stream, but that is not currently supported");
                        break;
                }
            }
        }
        transceiver.forceMuteAudio(audioForcedMuted);
    }

    /**
     * @return this {@link Endpoint}'s transceiver.
     */
    public Transceiver getTransceiver()
    {
        return transceiver;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = super.getDebugState();

        //debugState.put("sctpManager", sctpManager.getDebugState());
        debugState.put("bitrateController", bitrateController.getDebugState());
        debugState.put("bandwidthProbing", bandwidthProbing.getDebugState());
        debugState.put("iceTransport", iceTransport.getDebugState());
        debugState.put("dtlsTransport", dtlsTransport.getDebugState());
        debugState.put("transceiver", transceiver.getNodeStats().toJson());
        debugState.put("acceptAudio", acceptAudio);
        debugState.put("acceptVideo", acceptVideo);
        debugState.put("messageTransport", messageTransport.getDebugState());

        return debugState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFeature(EndpointDebugFeatures feature, boolean enabled) {

        switch (feature)
        {
            case PCAP_DUMP:
                transceiver.setFeature(Features.TRANSCEIVER_PCAP_DUMP, enabled);
                break;
        }
    }

    @Override
    public boolean isSendingAudio()
    {
        // The endpoint is sending audio if we (the transceiver) are receiving
        // audio.
        return transceiver.isReceivingAudio();
    }

    @Override
    public boolean isSendingVideo()
    {
        // The endpoint is sending video if we (the transceiver) are receiving
        // video.
        return transceiver.isReceivingVideo();
    }
}
