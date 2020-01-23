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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.bandwidthestimation.*;
import org.jitsi.nlj.srtp.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.root.colibri.debug.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.jitsi_modified.sctp4j.*;
import org.json.simple.*;

import java.beans.*;
import java.io.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

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
        PropertyChangeListener,
        EncodingsManager.EncodingsUpdateListener
{
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
    private final EndpointMessageTransport messageTransport;

    /**
     * A count of how many endpoints have 'selected' this endpoint
     */
    private final AtomicInteger selectedCount = new AtomicInteger(0);

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
     * TODO (brian): align the recurringrunnable stuff with whatever we end up
     * doing with all the other executors.
     */
    private static final RecurringRunnableExecutor recurringRunnableExecutor =
            new RecurringRunnableExecutor(Endpoint.class.getSimpleName());

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
        throws IOException
    {
        super(conference, id, parentLogger);

        this.clock = clock;

        creationTime = clock.instant();
        super.addPropertyChangeListener(this);
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
            });
        bitrateController = new BitrateController(this, diagnosticContext, logger);

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

        dtlsTransport = new DtlsTransport(this, iceControlling, logger);

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
        throws IOException
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


    @Override
    public void propertyChange(PropertyChangeEvent evt)
    {
        if (SELECTED_ENDPOINTS_PROPERTY_NAME.equals(evt.getPropertyName()))
        {
            bitrateController.setSelectedEndpointIds((Set<String>) evt.getNewValue());
        }
        else if (PINNED_ENDPOINTS_PROPERTY_NAME.equals(evt.getPropertyName()))
        {
            bitrateController.setPinnedEndpointIds((Set<String>) evt.getNewValue());
        }
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
     * @throws IOException
     */
    @Override
    public void sendMessage(String msg)
        throws IOException
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.sendMessage(msg);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
    public void setMaxReceiveFrameHeightPx(int maxReceiveFrameHeightPx)
    {
        super.setMaxReceiveFrameHeightPx(maxReceiveFrameHeightPx);
        bitrateController.setMaxRxFrameHeightPx(maxReceiveFrameHeightPx);
    }

    /**
     * Sets the local SSRC for this endpoint.
     * @param mediaType
     * @param ssrc
     */
    public void setLocalSsrc(MediaType mediaType, long ssrc)
    {
        transceiver.setLocalSsrc(mediaType, ssrc);
        if (MediaType.VIDEO.equals(mediaType))
        {
            bandwidthProbing.senderSsrc = ssrc;
        }
    }

    /**
     * Checks if this endpoint's DTLS transport is connected.
     * @return
     */
    private boolean isTransportConnected()
    {
        return dtlsTransport.isConnected();
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

            if (logger.isDebugEnabled())
            {
                logger.debug(
                    "relaying an sr from ssrc="
                        + rtcpSrPacket.getSenderSsrc()
                        + ", timestamp="
                        + rtcpSrPacket.getSenderInfo().getRtpTimestamp());
            }
        }

        transceiver.sendPacket(packetInfo);
    }

    /**
     * Handle an SRTP packet which has just been received (i.e. not processed
     * by the incoming pipeline)
     * @param srtpPacket
     */
    void srtpPacketReceived(PacketInfo srtpPacket)
    {
        transceiver.handleIncomingPacket(srtpPacket);
    }

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent
     * over DTLS) which has just been received.
     * @param dtlsAppPacket
     */
    void dtlsAppPacketReceived(PacketInfo dtlsAppPacket)
    {
        sctpHandler.consume(dtlsAppPacket);
    }

    /**
     * Set the handler which will send packets ready to go out onto the network
     * @param handler
     */
    public void setOutgoingSrtpPacketHandler(PacketHandler handler)
    {
        transceiver.setOutgoingPacketHandler(handler);
    }

    /**
     * TODO Brian
     */
    public void setSrtpInformation(
            int chosenSrtpProtectionProfile,
            TlsRole tlsRole,
            byte[] keyingMaterial)
    {
        transceiver.setSrtpInformation(
                chosenSrtpProtectionProfile, tlsRole, keyingMaterial);
    }

    /**
     * Adds a payload type to this endpoint.
     */
    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
        bitrateController.addPayloadType(payloadType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant getLastIncomingActivity()
    {
        PacketIOActivity packetIOActivity
                = this.transceiver.getPacketIOActivity();
        return packetIOActivity.getLastOverallIncomingActivity();
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
        boolean iceFailed = dtlsTransport.hasIceFailed();
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
            updateStatsOnExpire();
            this.transceiver.stop();
            if (logger.isDebugEnabled() && getConference().includeInStatistics())
            {
                logger.debug(transceiver.getNodeStats().prettyPrint(0));
                logger.debug(bitrateController.getDebugState().toJSONString());
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

        dtlsTransport.close();

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
    }

    /**
     * TODO Brian
     */
    public void createSctpConnection()
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Creating SCTP manager.");
        }
        // Create the SctpManager and provide it a method for sending SCTP data
        this.sctpManager = new SctpManager(
                (data, offset, length) -> {
                    PacketInfo packet
                        = new PacketInfo(new UnparsedPacket(data, offset, length));
                    dtlsTransport.sendDtlsData(packet);
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

        dtlsTransport.onDtlsHandshakeComplete(() -> {
            // We don't want to block the thread calling
            // onDtlsHandshakeComplete so run the socket acceptance in an IO
            // pool thread
            //TODO(brian): we should have a common 'notifier'/'publisher'
            // interface that has notify/notifyAsync logic so we don't have
            // to worry about this everywhere
            TaskPools.IO_POOL.submit(() -> {
                // FIXME: This runs forever once the socket is closed (
                // accept never returns true).
                int attempts = 0;
                while (!socket.accept())
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
                    logger.debug("SCTP socket " + socket.hashCode() +
                            " accepted connection.");
                }
            });
        });
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
        if (icePassword == null || !icePassword.equals(password))
        {
            logger.warn("Incoming web socket request with an invalid password." +
                    "Expected: " + icePassword + ", received " + password);
            return false;
        }

        return true;
    }

    /**
     * Notifies this {@link Endpoint} that a specific {@link ColibriWebSocket}
     * instance associated with it has connected.
     * @param ws the {@link ColibriWebSocket} which has connected.
     */
    public void onWebSocketConnect(ColibriWebSocket ws)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketConnect(ws);
        }
    }

    /**
     * Notifies this {@link Endpoint} that a specific {@link ColibriWebSocket}
     * instance associated with it has been closed.
     * @param ws the {@link ColibriWebSocket} which has been closed.
     */
    public void onWebSocketClose(
            ColibriWebSocket ws, int statusCode, String reason)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketClose(ws, statusCode, reason);
        }
    }

    /**
     * Notifies this {@link Endpoint} that a message has been received from a
     * specific {@link ColibriWebSocket} instance associated with it.
     * @param ws the {@link ColibriWebSocket} from which a message was received.
     */
    public void onWebSocketText(ColibriWebSocket ws, String message)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketText(ws, message);
        }
    }

    /**
     * @return the password of the ICE Agent associated with this
     * {@link Endpoint}.
     */
    private String getIcePassword()
    {
        return dtlsTransport.getIcePassword();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementSelectedCount()
    {
        int newValue = selectedCount.incrementAndGet();
        if (newValue == 1)
        {
            String selectedUpdate = createSelectedUpdateMessage(true);
            if (logger.isDebugEnabled())
            {
                logger.debug("Is now selected, sending message: " + selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error("Error sending SelectedUpdate message: " + e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementSelectedCount()
    {
        int newValue = selectedCount.decrementAndGet();
        if (newValue == 0)
        {
            String selectedUpdate = createSelectedUpdateMessage(false);
            if (logger.isDebugEnabled())
            {
                logger.debug("Is no longer selected, sending message: " +
                        selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error("Error sending SelectedUpdate message: " + e);
            }
        }
    }

    /**
     * Gets this {@link Endpoint}'s DTLS transport.
     *
     * @return this {@link Endpoint}'s DTLS transport.
     */
    @NotNull
    public DtlsTransport getDtlsTransport()
    {
        return dtlsTransport;
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

        String msg = createLastNEndpointsChangeEvent(
            forwardedEndpoints, endpointsEnteringLastN, conferenceEndpoints);

        try
        {
            sendMessage(msg);
        }
        catch (IOException e)
        {
            logger.error("Failed to send message on data channel.", e);
        }
    }

    /**
     * Sets the remote transport information (ICE candidates, DTLS fingerprints).
     *
     * @param transportInfo the XML extension which contains the remote
     * transport information.
     */
    public void setTransportInfo(IceUdpTransportPacketExtension transportInfo)
    {
        getDtlsTransport().startConnectivityEstablishment(transportInfo);
    }

    /**
     * A node which can be placed in the pipeline to cache SCTP packets until
     * the SCTPManager is ready to handle them.
     */
    private class SctpHandler extends ConsumerNode
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
        protected void consume(PacketInfo packetInfo)
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
    }

    /**
     * A node which can be placed in the pipeline to cache Data channel packets
     * until the DataChannelStack is ready to handle them.
     */
    private class DataChannelHandler extends ConsumerNode
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
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
    {
        getDtlsTransport().describe(channelBundle);
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
     * Sets the media stream tracks.
     * @param mediaStreamTracks
     */
    private void setMediaStreamTracks(MediaStreamTrackDesc[] mediaStreamTracks)
    {
        if (transceiver.setMediaStreamTracks(mediaStreamTracks))
        {
            getConference().endpointTracksChanged(this);
        }
    }

    /**
     * Gets the media stream tracks for this endpoint.
     * @return
     */
    @Override
    public MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return transceiver.getMediaStreamTracks();
    }

    /**
     * Re-creates this endpoint's media stream tracks based on the sources
     * and source groups that have been signaled.
     */
    @Override
    public void recreateMediaStreamTracks()
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
            MediaStreamTrackDesc[] tracks =
                MediaStreamTrackFactory.createMediaStreamTracks(
                    sources,
                    sourceGroups);
            setMediaStreamTracks(tracks);
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
                }
            }
        }
        this.acceptAudio = acceptAudio;
        this.acceptVideo = acceptVideo;
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
    public JSONObject getDebugState()
    {
        JSONObject debugState = super.getDebugState();

        debugState.put("selectedCount", selectedCount.get());
        //debugState.put("sctpManager", sctpManager.getDebugState());
        //debugState.put("messageTransport", messageTransport.getDebugState());
        debugState.put("bitrateController", bitrateController.getDebugState());
        debugState.put("bandwidthProbing", bandwidthProbing.getDebugState());
        debugState.put("dtlsTransport", dtlsTransport.getDebugState());
        debugState.put("transceiver", transceiver.getNodeStats().toJson());
        debugState.put("acceptAudio", acceptAudio);
        debugState.put("acceptVideo", acceptVideo);

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
}
