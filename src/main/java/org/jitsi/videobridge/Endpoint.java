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
import org.jitsi.nlj.srtp.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.transform.node.outgoing.*;
import org.jitsi.nlj.util.LocalSsrcAssociation;
import org.jitsi.nlj.util.RemoteSsrcAssociation;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.jitsi_modified.sctp4j.*;
import org.jitsi_modified.service.neomedia.rtp.*;
import org.json.simple.*;

import java.io.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

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
    extends AbstractEndpoint implements PotentialPacketHandler
{
    /**
     * The name of the <tt>Endpoint</tt> property <tt>pinnedEndpoint</tt> which
     * specifies the JID of the currently pinned <tt>Endpoint</tt> of this
     * <tt>Endpoint</tt>.
     */
    public static final String PINNED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".pinnedEndpoints";

    /**
     * The name of the <tt>Endpoint</tt> property <tt>selectedEndpoint</tt>
     * which specifies the JID of the currently selected <tt>Endpoint</tt> of
     * this <tt>Endpoint</tt>.
     */
    public static final String SELECTED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".selectedEndpoints";

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
            = Logger.getLogger(Endpoint.class);

    /**
     * The logger for the instance.
     */
    private final Logger logger;

    /**
     * The set of IDs of the pinned endpoints of this {@code Endpoint}.
     */
    private Set<String> pinnedEndpoints = new HashSet<>();

    /**
     * The set of currently selected <tt>Endpoint</tt>s at this
     * <tt>Endpoint</tt>.
     */
    private Set<String> selectedEndpoints = new HashSet<>();

    /**
     * The {@link SctpManager} instance we'll use to manage the SCTP connection
     */
    private SctpManager sctpManager;

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
     * TODO Brian
     */
    private CompletableFuture<Boolean> onTransportManagerSet
            = new CompletableFuture<>();

    /**
     * A count of how many endpoints have 'selected' this endpoint
     */
    private AtomicInteger selectedCount = new AtomicInteger(0);

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
     * This {@link Endpoint}'s transport manager.
     * Since it contains an ICE Agent we don't want to initialize it
     * prematurely, or more than once.
     *
     */
    private DtlsTransport transportManager;

    /**
     * The exception thrown from the attempt to initialize the transport manager,
     * if any.
     */
    private IOException transportManagerException = null;

    /**
     * Synchronizes access to {@link #transportManager}.
     */
    private final Object transportManagerSyncRoot = new Object();

    /**
     * The {@link Transceiver} which handles receiving and sending of (S)RTP.
     */
    private final Transceiver transceiver;

    /**
     * The list of {@link ChannelShim}s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed.
     */
    final List<ChannelShim> channelShims = new LinkedList<>();

    /**
     * Whether this endpoint should accept audio packets. We set this according
     * to whether the endpoint has an audio Colibri channel.
     */
    private boolean acceptAudio = false;

    /**
     * Whether this endpoint should accept video packets. We set this according
     * to whether the endpoint has a video Colibri channel.
     */
    private boolean acceptVideo = false;

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
     * @param conference
     */
    public Endpoint(String id, Conference conference)
    {
        super(conference, id);

        logger = Logger.getLogger(classLogger, conference.getLogger());
        diagnosticContext = conference.newDiagnosticContext();
        transceiver
                = new Transceiver(
                id,
                TaskPools.CPU_POOL,
                TaskPools.CPU_POOL,
                TaskPools.SCHEDULED_POOL,
                diagnosticContext,
                logger);
        transceiver.setIncomingPacketHandler(
                new ConsumerNode("receiver chain handler")
                {
                    @Override
                    protected void consume(@NotNull PacketInfo packetInfo)
                    {
                        handleIncomingPacket(packetInfo);
                    }
                });
        bitrateController = new BitrateController(this, diagnosticContext);

        messageTransport = new EndpointMessageTransport(this);

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
                logger.debug(logPrefix +
                        "Estimated bandwidth is now " + newValueBps + " bps.");
            }
            bitrateController.bandwidthChanged(newValueBps);
        });
        transceiver.onBandwidthEstimateChanged(bandwidthProbing);
        conference.encodingsManager.subscribe(this);

        bandwidthProbing.enabled = true;
        recurringRunnableExecutor.registerRecurringRunnable(bandwidthProbing);

        if (conference.includeInStatistics())
        {
            conference.getVideobridge().getStatistics()
                    .totalEndpoints.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EndpointMessageTransport getMessageTransport()
    {
        return messageTransport;
    }

    /**
     * Sets the list of pinned endpoints for this endpoint.
     * @param newPinnedEndpoints the set of pinned endpoints.
     */
    void pinnedEndpointsChanged(Set<String> newPinnedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldPinnedEndpoints = this.pinnedEndpoints;
        if (!oldPinnedEndpoints.equals(newPinnedEndpoints))
        {
            this.pinnedEndpoints = newPinnedEndpoints;

            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix + "Pinned "
                    + Arrays.toString(pinnedEndpoints.toArray()));
            }

            bitrateController.setPinnedEndpointIds(pinnedEndpoints);

            firePropertyChange(PINNED_ENDPOINTS_PROPERTY_NAME,
                oldPinnedEndpoints, pinnedEndpoints);
        }
    }

    /**
     * Sets the list of selected endpoints for this endpoint.
     * @param newSelectedEndpoints the set of selected endpoints.
     */
    void selectedEndpointsChanged(Set<String> newSelectedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldSelectedEndpoints = this.selectedEndpoints;
        if (!oldSelectedEndpoints.equals(newSelectedEndpoints))
        {
            this.selectedEndpoints = newSelectedEndpoints;

            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix + "Selected "
                    + Arrays.toString(selectedEndpoints.toArray()));
            }

            bitrateController.setSelectedEndpointIds(
                    Collections.unmodifiableSet(selectedEndpoints));

            firePropertyChange(SELECTED_ENDPOINTS_PROPERTY_NAME,
                oldSelectedEndpoints, selectedEndpoints);
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
     * Checks if this endpoint's transport manager is connected.
     * @return
     */
    private boolean isTransportConnected()
    {
        try
        {
            return getTransportManager().isConnected();
        }
        catch (IOException ioe)
        {
            logger.warn("Could not get transport manager: ", ioe);
            return false;
        }
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
                        && bitrateController.accept((VideoRtpPacket) packet);
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
                logger.warn(logPrefix
                    + "Ignoring an rtcp packet of type"
                    + packet.getClass().getSimpleName());
                return false;
            }
        }

        logger.warn(logPrefix
            + "Ignoring an unknown packet type:"
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
            //TODO(brian): we lose all information in PacketInfo here,
            // unfortunately, because the BitrateController can return more
            // than/less than what was passed in (and in different order) so
            // we can't just reassign a transformed packet back into its
            // proper PacketInfo. Need to change those classes to work with
            // the new packet types
            VideoRtpPacket[] extras = bitrateController.transformRtp(packetInfo);
            if (extras == null)
            {
                logger.warn(
                    "Dropping a packet which was supposed to be accepted:"
                        + packet);
                return;
            }

            // The original packet was transformed in place.
            // TODO: should we send this *after* the extras?
            transceiver.sendPacket(packetInfo);

            for (VideoRtpPacket videoRtpPacket : extras)
            {
                transceiver.sendPacket(new PacketInfo(videoRtpPacket));
            }
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
    public long getLastActivity()
    {
        PacketIOActivity packetIOActivity
                = this.transceiver.getPacketIOActivity();
        return packetIOActivity.getLastOverallActivityTimestampMs();
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
        boolean iceFailed
                = transportManager != null && transportManager.hasIceFailed();
        if (iceFailed)
        {
            logger.warn(logPrefix + "Allowing to expire because ICE failed.");
            return true;
        }

        PacketIOActivity packetIOActivity
                = this.transceiver.getPacketIOActivity();

        int maxExpireTimeSecsFromChannelShims = channelShims.stream()
                .map(ChannelShim::getExpire)
                .mapToInt(exp -> exp)
                .max()
                .orElse(0);

        long lastActivity
                = packetIOActivity.getLastOverallActivityTimestampMs();
        if (lastActivity <= 0)
        {
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
            return false;
        }

        long now = System.currentTimeMillis();
        if (Duration.ofMillis(now - lastActivity).getSeconds()
                > maxExpireTimeSecsFromChannelShims)
        {
            logger.info(logPrefix +
                    "Allowing to expire because of no activity in over " +
                    maxExpireTimeSecsFromChannelShims + " seconds.");
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
            logger.error(logPrefix + "Exception while expiring: ", e);
        }
        bandwidthProbing.enabled = false;
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing);

        if (transportManager != null)
        {
            transportManager.close();
        }

        logger.info(logPrefix + "Expired.");
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
        BandwidthEstimator.Statistics bweStats
                = transceiverStats.getBandwidthEstimatorStats();

        conferenceStats.totalBytesReceived.addAndGet(
                incomingStats.getBytes());
        conferenceStats.totalPacketsReceived.addAndGet(
                incomingStats.getPackets());
        conferenceStats.totalBytesSent.addAndGet(
                outgoingStats.getBytes());
        conferenceStats.totalPacketsSent.addAndGet(
                outgoingStats.getPackets());

        bweStats.update(System.currentTimeMillis());

        Videobridge.Statistics videobridgeStats
                = getConference().getVideobridge().getStatistics();

        long lossLimitedMs = bweStats.getLossLimitedMs();
        long lossDegradedMs = bweStats.getLossDegradedMs();
        long participantMs = bweStats.getLossFreeMs()
                + lossDegradedMs + lossLimitedMs;

        videobridgeStats.totalLossControlledParticipantMs
                .addAndGet(participantMs);
        videobridgeStats.totalLossLimitedParticipantMs
                .addAndGet(lossLimitedMs);
        videobridgeStats.totalLossDegradedParticipantMs
                .addAndGet(lossDegradedMs);
    }

    /**
     * TODO Brian
     */
    public void createSctpConnection()
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix + "Creating SCTP manager.");
        }
        // Create the SctpManager and provide it a method for sending SCTP data
        this.sctpManager = new SctpManager(
                (data, offset, length) -> {
                    PacketInfo packet
                        = new PacketInfo(new UnparsedPacket(data, offset, length));
                    transportManager.sendDtlsData(packet);
                    return 0;
                }
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
                logger.info(logPrefix +
                    "SCTP connection is ready, creating the Data channel stack");
                dataChannelStack
                    = new DataChannelStack(
                        (data, sid, ppid)
                                -> socket.send(data, true, sid, ppid));
                dataChannelStack.onDataChannelStackEvents(dataChannel ->
                {
                    logger.info(
                            logPrefix + "Remote side opened a data channel.");
                    Endpoint.this.messageTransport.setDataChannel(dataChannel);
                });
                dataChannelHandler.setDataChannelStack(dataChannelStack);
                if (OPEN_DATA_LOCALLY)
                {
                    logger.info(logPrefix + "Will open the data channel.");
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
                    logger.info(logPrefix +
                        "Will wait for the remote side to open the data channel.");
                }
            }

            @Override
            public void onDisconnected()
            {
                logger.info(logPrefix + "SCTP connection is disconnected.");
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
        // We don't want to block the calling thread on the
        // onTransportManagerSet future completing to add the
        // onDtlsHandshakeComplete handler, so we'll asynchronously run the
        // code which adds the onDtlsHandshakeComplete handler from the IO pool.
        onTransportManagerSet.thenRunAsync(() -> {
            transportManager.onDtlsHandshakeComplete(() -> {
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
                        logger.debug(logPrefix +
                                "SCTP socket " + socket.hashCode() +
                                " accepted connection.");
                    }
                });
            });
        }, TaskPools.IO_POOL);
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
            logger.warn(logPrefix +
                    "Incoming web socket request with an invalid password." +
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
        return transportManager == null
                ? null : transportManager.getIcePassword();
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
                logger.debug(logPrefix +
                        "Is now selected, sending message: " + selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error(logPrefix +
                        "Error sending SelectedUpdate message: " + e);
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
                logger.debug(logPrefix +
                        "Is no longer selected, sending message: " +
                        selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error(logPrefix +
                        "Error sending SelectedUpdate message: " + e);
            }
        }
    }

    /**
     * Gets this {@link Endpoint}'s transport manager, initializing it if it
     * wasn't already initialized. If there was a previous unsuccessful attempt
     * to initialize it, re-throws the same exception.
     *
     * @return this {@link Endpoint}'s transport manager.
     *
     * @throws IOException if the transport manager fails to initialize.
     */
    public DtlsTransport getTransportManager()
        throws IOException
    {
        boolean transportManagerCreated = false;
        synchronized (transportManagerSyncRoot)
        {
            if (transportManager == null)
            {
                if (transportManagerException != null)
                {
                    // We've already tried and failed to initialize the TM.
                    throw transportManagerException;
                }
                else
                {
                    try
                    {
                        transportManager = new DtlsTransport(this);
                        transportManagerCreated = true;
                    }
                    catch (IOException ioe)
                    {
                        throw transportManagerException = ioe;
                    }
                }
            }
        }

        if (transportManagerCreated)
        {
            onTransportManagerSet.complete(true);
        }
        return transportManager;
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
     * @throws IOException if the endpoint's transport manager failed to
     * initialize.
     */
    public void setTransportInfo(IceUdpTransportPacketExtension transportInfo)
            throws IOException
    {
        getTransportManager().startConnectivityEstablishment(transportInfo);
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
     * @throws IOException if the transport manager fails to initialize.
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
            throws IOException
    {
        getTransportManager().describe(channelBundle);
    }


    @Override
    public void requestKeyframe(long mediaSsrc)
    {
        transceiver.requestKeyFrame(mediaSsrc);
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
        ChannelShim videoChannel = getChannelOfMediaType(MediaType.VIDEO);
        if (videoChannel != null)
        {
            MediaStreamTrackDesc[] tracks =
                    MediaStreamTrackFactory.createMediaStreamTracks(
                            videoChannel.getSources(),
                            videoChannel.getSourceGroups());
            setMediaStreamTracks(tracks);
        }
    }

    /**
     * Gets this {@link AbstractEndpoint}'s channel of media type
     * {@code mediaType} (although it's not strictly enforced, endpoints have
     * at most one channel with a given media type).
     *
     * @param mediaType the media type of the channel.
     *
     * @return the channel.
     */
    private ChannelShim getChannelOfMediaType(MediaType mediaType)
    {
        return
                channelShims.stream()
                        .filter(c -> c.getMediaType().equals(mediaType))
                        .findAny().orElse(null);

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
            logger.debug(logPrefix + "Adding receive ssrc " + ssrc + " of type " + mediaType);
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
     * Adds a channel to this enpoint.
     * @param channelShim
     */
    public void addChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
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
            channelShims.add(channelShim);
        }
    }

    /**
     * Removes a specific {@link ChannelShim} from this endpoint.
     * @param channelShim
     */
    public void removeChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
        {
            switch (channelShim.getMediaType())
            {
                case AUDIO:
                    acceptAudio = false;
                    break;
                case VIDEO:
                    acceptVideo = false;
                    break;
            }

            channelShims.remove(channelShim);
            if (channelShims.isEmpty())
            {
                expire();
            }
        }
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

        debugState.put("selectedEndpoints", selectedEndpoints.toString());
        debugState.put("pinnedEndpoints", pinnedEndpoints.toString());
        debugState.put("selectedCount", selectedCount.get());
        //debugState.put("sctpManager", sctpManager.getDebugState());
        //debugState.put("messageTransport", messageTransport.getDebugState());
        debugState.put("bitrateController", bitrateController.getDebugState());
        debugState.put("bandwidthProbing", bandwidthProbing.getDebugState());
        DtlsTransport transportManager = this.transportManager;
        debugState.put(
            "transportManager",
            transportManager == null ? null : transportManager.getDebugState());
        debugState.put("transceiver", transceiver.getNodeStats().toJson());
        debugState.put("acceptAudio", acceptAudio);
        debugState.put("acceptVideo", acceptVideo);

        return debugState;
    }
}
