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
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.*;
import org.jitsi.health.Result;
import org.jitsi.metrics.*;
import org.jitsi.nlj.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.stats.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.load_management.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.shutdown.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.videobridge.colibri2.Colibri2UtilKt.*;
import static org.jitsi.xmpp.util.ErrorUtilKt.createError;

/**
 * Represents the Jitsi Videobridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 * @author Hristo Terezov
 * @author Boris Grozev
 * @author Brian Baldino
 */
@SuppressWarnings("JavadocReference")
public class Videobridge
{
    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(Videobridge.class.getName());

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    public static final Random RANDOM = new Random();

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their local IDs.
     *
     * TODO: The only remaining uses of this ID are for the HTTP debug interface and the colibri WebSocket conference
     * identifier. This should be replaced with meetingId (while making sure jvb-rtcstats-push doesn't break).
     */
    private final Map<String, Conference> conferencesById = new HashMap<>();

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * meeting IDs.
     */
    private final Map<String, Conference> conferencesByMeetingId = new HashMap<>();

    private final JvbHealthChecker jvbHealthChecker = new JvbHealthChecker();

    /**
     * The clock to use, pluggable for testing purposes.
     *
     * Note that currently most code uses the system clock directly.
     */
    @NotNull
    private final Clock clock;

    /**
     * A class that holds some instance statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Thread that checks expiration for conferences, contents, channels and
     * execute expire procedure for any of them.
     */
    private final VideobridgeExpireThread videobridgeExpireThread;

    /**
     * The {@link JvbLoadManager} instance used for this bridge.
     */
    @NotNull
    private final JvbLoadManager<?> jvbLoadManager;

    @NotNull private final Version version;
    @Nullable private final String releaseId;

    @NotNull private final ShutdownManager shutdownManager;

    static
    {
        org.jitsi.rtp.util.BufferPool.Companion.setGetArray(ByteBufferPool::getBuffer);
        org.jitsi.rtp.util.BufferPool.Companion.setReturnArray(buffer -> {
            ByteBufferPool.returnBuffer(buffer);
            return Unit.INSTANCE;
        });
        org.jitsi.nlj.util.BufferPool.Companion.setGetBuffer(ByteBufferPool::getBuffer);
        org.jitsi.nlj.util.BufferPool.Companion.setReturnBuffer(buffer -> {
            ByteBufferPool.returnBuffer(buffer);
            return Unit.INSTANCE;
        });
    }

    /**
     * Initializes a new <tt>Videobridge</tt> instance.
     */
    public Videobridge(
        @Nullable XmppConnection xmppConnection,
        @NotNull ShutdownServiceImpl shutdownService,
        @NotNull Version version,
        @Nullable String releaseId,
        @NotNull Clock clock)
    {
        this.clock = clock;
        videobridgeExpireThread = new VideobridgeExpireThread(this);
        jvbLoadManager = JvbLoadManager.create(this);
        if (xmppConnection != null)
        {
            xmppConnection.setEventHandler(new XmppConnectionEventHandler());
        }
        this.version = version;
        this.releaseId = releaseId;
        this.shutdownManager = new ShutdownManager(shutdownService, logger);
        jvbHealthChecker.start();
    }

    @NotNull
    public JvbHealthChecker getJvbHealthChecker()
    {
        return jvbHealthChecker;
    }

    /**
     * Generate conference IDs until one is found that isn't in use and create a new {@link Conference}
     * object using that ID
     */
    private @NotNull Conference doCreateConference(
            @Nullable EntityBareJid name,
            String meetingId,
            boolean isRtcStatsEnabled)
    {
        Conference conference = null;
        do
        {
            String id = generateConferenceID();

            synchronized (conferencesById)
            {
                if (meetingId != null && conferencesByMeetingId.containsKey(meetingId))
                {
                    throw new IllegalStateException("Already have a meeting with meetingId " + meetingId);
                }

                if (!conferencesById.containsKey(id))
                {
                    conference = new Conference(this, id, name, meetingId, isRtcStatsEnabled);
                    conferencesById.put(id, conference);
                    statistics.currentConferences.inc();

                    if (meetingId != null)
                    {
                        conferencesByMeetingId.put(meetingId, conference);
                    }
                }
            }
        }
        while (conference == null);

        return conference;
    }

    void localEndpointCreated(boolean visitor)
    {
        statistics.currentLocalEndpoints.inc();
        if (visitor)
        {
            statistics.currentVisitors.inc();
        }
    }

    void localEndpointExpired(boolean visitor)
    {
        long remainingEndpoints = statistics.currentLocalEndpoints.decAndGet();
        if (visitor)
        {
            statistics.currentVisitors.dec();
        }

        if (remainingEndpoints < 0)
        {
            logger.warn("Invalid endpoint count " + remainingEndpoints + ". Disabling endpoint-count based shutdown!");
            return;
        }
        shutdownManager.maybeShutdown(remainingEndpoints);
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @param gid  the "global" id of the conference (or
     *             {@link Conference#GID_NOT_SET} if it is not specified.
     * @param name world readable name of the conference to create.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    private @NotNull Conference createConference(
            @Nullable EntityBareJid name,
            String meetingId,
            boolean isRtcStatsEnabled)
    {
        final Conference conference = doCreateConference(name, meetingId, isRtcStatsEnabled);

        logger.info(() -> "create_conf, id=" + conference.getID() + " meeting_id=" + meetingId);

        return conference;
    }

    /**
     * Expires a specific <tt>Conference</tt> of this <tt>Videobridge</tt> (i.e.
     * if the specified <tt>Conference</tt> is not in the list of
     * <tt>Conference</tt>s of this <tt>Videobridge</tt>, does nothing).
     *
     * @param conference the <tt>Conference</tt> to be expired by this
     * <tt>Videobridge</tt>
     */
    public void expireConference(Conference conference)
    {
        String id = conference.getID();
        String meetingId = conference.getMeetingId();

        synchronized (conferencesById)
        {
            if (conference.equals(conferencesById.get(id)))
            {
                conferencesById.remove(id);
                statistics.currentConferences.dec();

                if (meetingId != null)
                {
                    if (conference.equals(conferencesByMeetingId.get(meetingId)))
                    {
                        conferencesByMeetingId.remove(meetingId);
                    }
                }
                conference.expire();
            }
        }
    }

    /**
     * Generates a new <tt>Conference</tt> ID which is not guaranteed to be
     * unique.
     *
     * @return a new <tt>Conference</tt> ID which is not guaranteed to be unique
     */
    private String generateConferenceID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Gets the statistics of this instance.
     *
     * @return the statistics of this instance.
     */
    public Statistics getStatistics()
    {
        return statistics;
    }

    /**
     * Gets an existing {@link Conference} with a specific ID.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @return an existing <tt>Conference</tt> with the specified ID.
     */
    public Conference getConference(String id)
    {
        synchronized (conferencesById)
        {
            return conferencesById.get(id);
        }
    }

    /**
     * Gets an existing {@link Conference} with a specific meeting ID.
     */
    public Conference getConferenceByMeetingId(@NotNull String meetingId)
    {
        /* Note that conferenceByMeetingId is synchronized on conferencesById. */
        synchronized (conferencesById)
        {
            return conferencesByMeetingId.get(meetingId);
        }
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public Collection<Conference> getConferences()
    {
        synchronized (conferencesById)
        {
            return new HashSet<>(conferencesById.values());
        }
    }

    /**
     * Handles a COLIBRI2 request synchronously.
     * @param conferenceModifyIQ The COLIBRI request.
     * @return The response in the form of an {@link IQ}. It is either an error or a {@link ConferenceModifiedIQ}.
     */
    public IQ handleConferenceModifyIq(ConferenceModifyIQ conferenceModifyIQ)
    {
        Conference conference;
        try
        {
            conference = getOrCreateConference(conferenceModifyIQ);
        }
        catch (ConferenceNotFoundException e)
        {
            return createConferenceNotFoundError(conferenceModifyIQ, conferenceModifyIQ.getMeetingId());
        }
        catch (ConferenceAlreadyExistsException e)
        {
            return createConferenceAlreadyExistsError(conferenceModifyIQ, conferenceModifyIQ.getMeetingId());
        }
        catch (InGracefulShutdownException e)
        {
            return createGracefulShutdownErrorResponse(conferenceModifyIQ);
        }
        catch (XmppStringprepException e)
        {
            return createError(
                    conferenceModifyIQ,
                    StanzaError.Condition.bad_request,
                    "Invalid conference name (not a JID)");
        }

        return conference.handleConferenceModifyIQ(conferenceModifyIQ);
    }

    /**
     * Handles a COLIBRI request asynchronously.
     */
    private void handleColibriRequest(XmppConnection.ColibriRequest request)
    {
        ConferenceModifyIQ iq = request.getRequest();
        String id = request.getRequest().getMeetingId();
        Conference conference;

        try
        {
            conference = getOrCreateConference(request.getRequest());
        }
        catch (ConferenceNotFoundException e)
        {
            request.getCallback().invoke(createConferenceNotFoundError(iq, id));
            return;
        }
        catch (ConferenceAlreadyExistsException e)
        {
            request.getCallback().invoke(createConferenceAlreadyExistsError(iq, id));
            return;
        }
        catch (InGracefulShutdownException e)
        {
            request.getCallback().invoke(createGracefulShutdownErrorResponse(iq));
            return;
        }
        catch (XmppStringprepException e)
        {
            request.getCallback().invoke(
                createError(iq, StanzaError.Condition.bad_request, "Invalid conference name (not a JID)"));
            return;
        }

        // It is now the responsibility of Conference to send a response.
        conference.enqueueColibriRequest(request);
    }

    private @NotNull Conference getOrCreateConference(ConferenceModifyIQ conferenceModifyIQ)
        throws InGracefulShutdownException, XmppStringprepException,
            ConferenceAlreadyExistsException, ConferenceNotFoundException
    {
        String meetingId = conferenceModifyIQ.getMeetingId();

        synchronized(conferencesById)
        {
            Conference conference = getConferenceByMeetingId(meetingId);

            if (conferenceModifyIQ.getCreate())
            {
                if (conference != null)
                {
                    logger.warn("Will not create conference, conference already exists for meetingId=" + meetingId);
                    throw new ConferenceAlreadyExistsException();
                }
                if (isInGracefulShutdown())
                {
                    logger.warn("Will not create conference in shutdown mode.");
                    throw new InGracefulShutdownException();
                }

                String conferenceName = conferenceModifyIQ.getConferenceName();
                return createConference(
                    conferenceName == null ? null : JidCreate.entityBareFrom(conferenceName),
                    meetingId,
                    conferenceModifyIQ.isRtcstatsEnabled()
                );
            }
            else
            {
                if (conference == null)
                {
                    logger.warn("Conference with meeting_id=" + meetingId + " not found.");
                    throw new ConferenceNotFoundException();
                }
                return conference;
            }
        }
    }

    /**
     * Handles a shutdown request.
     */
    public void shutdown(boolean graceful)
    {
        shutdownManager.initiateShutdown(graceful);
        shutdownManager.maybeShutdown(statistics.currentLocalEndpoints.get());
    }

    /**
     * Whether the bridge is in "drain" mode. The bridge will not be assigned to new
     * conferences when in this mode.
     */
    private boolean drainMode = VideobridgeConfig.Companion.getInitialDrainMode();

    /**
     * Handles a drain request.
     */
    public void setDrainMode(boolean enable)
    {
        logger.info("Received drain request. enable=" + enable);
        drainMode = enable;
    }

    /**
     * Query the drain state.
     */
    public boolean getDrainMode()
    {
        return drainMode;
    }

    /**
     * Returns {@code true} if this instance has entered graceful shutdown mode.
     *
     * @return {@code true} if this instance has entered graceful shutdown mode;
     * otherwise, {@code false}
     */
    public boolean isInGracefulShutdown()
    {
        return shutdownManager.getState() == ShutdownState.GRACEFUL_SHUTDOWN;
    }

    public ShutdownState getShutdownState()
    {
        return shutdownManager.getState();
    }

    /**
     * Starts this {@link Videobridge}.
     *
     * NOTE: we have to make this public so Jicofo can call it from its tests.
     */
    public void start()
    {
        UlimitCheck.printUlimits();

        videobridgeExpireThread.start();

        // <force-shutdown>
        ForcefulShutdownIqProvider.registerIQProvider();

        // <graceful-shutdown>
        GracefulShutdownIqProvider.registerIQProvider();

        // <stats>
        new ColibriStatsIqProvider(); // registers itself with Smack

        // ICE-UDP <transport>
        ProviderManager.addExtensionProvider(
                IceUdpTransportPacketExtension.ELEMENT,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(IceUdpTransportPacketExtension.class));

        // RAW-UDP <candidate xmlns=urn:xmpp:jingle:transports:raw-udp:1>
        DefaultPacketExtensionProvider<UdpCandidatePacketExtension> udpCandidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(UdpCandidatePacketExtension.class);
        ProviderManager.addExtensionProvider(
                UdpCandidatePacketExtension.ELEMENT,
                UdpCandidatePacketExtension.NAMESPACE,
                udpCandidatePacketExtensionProvider);

        // ICE-UDP <candidate xmlns=urn:xmpp:jingle:transports:ice-udp:1">
        DefaultPacketExtensionProvider<IceCandidatePacketExtension> iceCandidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(IceCandidatePacketExtension.class);
        ProviderManager.addExtensionProvider(
                IceCandidatePacketExtension.ELEMENT,
                IceCandidatePacketExtension.NAMESPACE,
                iceCandidatePacketExtensionProvider);

        // ICE <rtcp-mux/>
        ProviderManager.addExtensionProvider(
                IceRtcpmuxPacketExtension.ELEMENT,
                IceRtcpmuxPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(IceRtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        ProviderManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(DtlsFingerprintPacketExtension.class));

        // Health-check
        HealthCheckIQProvider.registerIQProvider();

        // Colibri2
        IqProviderUtils.registerProviders();
    }

    /**
     * Stops this {@link Videobridge}.
     *
     * NOTE: we have to make this public so Jicofo can call it from its tests.
     */
    public void stop()
    {
        videobridgeExpireThread.stop();
        jvbLoadManager.stop();
    }

    /**
     * Creates a JSON for debug purposes. If a specific {@code conferenceId}
     * is requested, the result will include all of the state of the conference
     * considered useful for debugging (note that this is a LOT of data).
     * Otherwise (if {@code conferenceId} is {@code null}), the result includes
     * a shallow list of the active conferences and their endpoints.
     *
     * @param conferenceId the ID of a specific conference to include. If not
     * specified, a shallow list of all conferences will be returned.
     * @param endpointId the ID of a specific endpoint in {@code conferenceId}
     * to include. If not specified, all of the conference's endpoints will be
     * included.
     */
    @SuppressWarnings("unchecked")
    public OrderedJsonObject getDebugState(String conferenceId, String endpointId, boolean full)
    {
        OrderedJsonObject debugState = new OrderedJsonObject();
        debugState.put("shutdownState", shutdownManager.getState().toString());
        debugState.put("drain", drainMode);
        debugState.put("time", System.currentTimeMillis());

        debugState.put("load-management", jvbLoadManager.getStats());

        Double jitter = PacketTransitStats.getBridgeJitter();
        if (jitter != null)
        {
            debugState.put("overall_bridge_jitter", jitter);
        }

        JSONObject conferences = new JSONObject();
        debugState.put("conferences", conferences);
        if (StringUtils.isBlank(conferenceId))
        {
            getConferences().forEach(conference ->
                conferences.put(
                        conference.getID(),
                        conference.getDebugState(full, null)));
        }
        else
        {
            // Using getConference will 'touch' it and prevent it from expiring
            Conference conference;
            synchronized (conferences)
            {
                conference = this.conferencesById.get(conferenceId);
            }

            conferences.put(
                    conferenceId,
                    conference == null ? "null" : conference.getDebugState(full, endpointId));
        }

        return debugState;
    }

    /**
     * Gets statistics for the different {@code PacketQueue}s that this bridge
     * uses.
     * TODO: is there a better place for this?
     */
    @SuppressWarnings("unchecked")
    public JSONObject getQueueStats()
    {
        JSONObject queueStats = new JSONObject();

        queueStats.put(
            "srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(Endpoint.queueErrorCounter,
                "Endpoint-outgoing-packet-queue"));
        queueStats.put(
            "relay_srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(Relay.queueErrorCounter,
                "Relay-outgoing-packet-queue"));
        queueStats.put(
            "relay_endpoint_sender_srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RelayEndpointSender.queueErrorCounter,
                "RelayEndpointSender-outgoing-packet-queue"));
        queueStats.put(
            "rtp_receiver_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RtpReceiverImpl.Companion.getQueueErrorCounter(),
                "rtp-receiver-incoming-packet-queue"));
        queueStats.put(
            "rtp_sender_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RtpSenderImpl.Companion.getQueueErrorCounter(),
                "rtp-sender-incoming-packet-queue"));
        queueStats.put(
            "colibri_queue",
            QueueStatistics.Companion.getStatistics().get("colibri-queue")
        );

        queueStats.put(
            AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID,
            getJsonFromQueueStatisticsAndErrorHandler(
                    null,
                    AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID));

        return queueStats;
    }

    private OrderedJsonObject getJsonFromQueueStatisticsAndErrorHandler(
            CountingErrorHandler countingErrorHandler,
            String queueName)
    {
        OrderedJsonObject json = (OrderedJsonObject)QueueStatistics.Companion.getStatistics().get(queueName);
        if (countingErrorHandler != null)
        {
            if (json == null)
            {
                json = new OrderedJsonObject();
                json.put("dropped_packets", countingErrorHandler.getNumPacketsDropped());
            }
            json.put("exceptions", countingErrorHandler.getNumExceptions());
        }

        return json;
    }

    @NotNull
    public Version getVersion()
    {
        return version;
    }

    /**
     * Get the release ID of this videobridge.
     * @return The release ID. Returns null if not in use.
     */
    public @Nullable String getReleaseId()
    {
        return releaseId;
    }

    private class XmppConnectionEventHandler implements XmppConnection.EventHandler
    {
        @Override
        public void colibriRequestReceived(@NotNull XmppConnection.ColibriRequest request)
        {
            handleColibriRequest(request);
        }

        @NotNull
        @Override
        public IQ versionIqReceived(@NotNull org.jivesoftware.smackx.iqversion.packet.Version iq)
        {
            org.jivesoftware.smackx.iqversion.packet.Version versionResult =
                new org.jivesoftware.smackx.iqversion.packet.Version(
                    version.getApplicationName(),
                    version.toString(),
                    System.getProperty("os.name")
                );

            // to, from and packetId are set by the caller.
            // versionResult.setTo(versionRequest.getFrom());
            // versionResult.setFrom(versionRequest.getTo());
            // versionResult.setPacketID(versionRequest.getPacketID());
            versionResult.setType(IQ.Type.result);

            return versionResult;
        }

        @NotNull
        @Override
        public IQ healthCheckIqReceived(@NotNull HealthCheckIQ iq)
        {
            Result result = jvbHealthChecker.getResult();
            if (result.getSuccess())
            {
                return IQ.createResultIQ(iq);
            }
            else
            {
                return IQ.createErrorResponse(
                        iq,
                        StanzaError.from(StanzaError.Condition.internal_server_error, result.getMessage()).build());
            }
        }
    }

    /**
     * Basic statistics/metrics about the videobridge like cumulative/total
     * number of channels created, cumulative/total number of channels failed,
     * etc.
     */
    public static class Statistics
    {
        /**
         * The total number of times our AIMDs have expired the incoming bitrate
         * (and which would otherwise result in video suspension).
         * (see {@link AimdRateControl#incomingBitrateExpirations}).
         */
        public CounterMetric incomingBitrateExpirations = VideobridgeMetricsContainer.getInstance().registerCounter(
                "incoming_bitrate_expirations",
                "Number of times our AIMDs have expired the incoming bitrate.");

        /**
         * The cumulative/total number of conferences in which ALL of the endpoints failed ICE.
         */
        public CounterMetric failedConferences = VideobridgeMetricsContainer.getInstance().registerCounter(
                "failed_conferences",
                "Number of conferences in which ALL of the endpoints failed ICE.");

        /**
         * The cumulative/total number of conferences in which SOME of the endpoints failed ICE.
         */
        public CounterMetric partiallyFailedConferences = VideobridgeMetricsContainer.getInstance().registerCounter(
                "partially_failed_conferences",
                "Number of conferences in which SOME of the endpoints failed ICE.");

        /**
         * The cumulative/total number of conferences completed/expired on this
         * {@link Videobridge}.
         */
        public CounterMetric conferencesCompleted = VideobridgeMetricsContainer.getInstance().registerCounter(
                "conferences_completed",
                "The total number of conferences completed/expired on the Videobridge.");

        /**
         * The cumulative/total number of conferences created on this
         * {@link Videobridge}.
         */
        public CounterMetric conferencesCreated = VideobridgeMetricsContainer.getInstance().registerCounter(
                "conferences_created",
                "The total number of conferences created on the Videobridge.");

        /**
         * The total duration in seconds of all completed conferences on this
         * {@link Videobridge}.
         */
        public AtomicLong totalConferenceSeconds = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-controlled
         * (i.e. the sum of the lengths is seconds) on this {@link Videobridge}.
         */
        public AtomicLong totalLossControlledParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-limited
         * on this {@link Videobridge}.
         */
        public AtomicLong totalLossLimitedParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-degraded
         * on this {@link Videobridge}. We chose the unit to be millis because
         * we expect that a lot of our calls spend very few ms (<500) in the
         * lossDegraded state for example, and they might get cut to 0.
         */
        public AtomicLong totalLossDegradedParticipantMs = new AtomicLong();

        /**
         * The total number of messages received from the data channels of
         * the endpoints of this conference.
         */
        public CounterMetric dataChannelMessagesReceived = VideobridgeMetricsContainer.getInstance().registerCounter(
                "data_channel_messages_received",
                "Number of messages received from the data channels of the endpoints of this conference.");

        /**
         * The total number of messages sent via the data channels of the
         * endpoints of this conference.
         */
        public CounterMetric dataChannelMessagesSent = VideobridgeMetricsContainer.getInstance().registerCounter(
                "data_channel_messages_sent",
                "Number of messages sent via the data channels of the endpoints of this conference.");

        /**
         * The total number of messages received from the data channels of
         * the endpoints of this conference.
         */
        public CounterMetric colibriWebSocketMessagesReceived = VideobridgeMetricsContainer.getInstance()
                .registerCounter("colibri_web_socket_messages_received",
                        "Number of messages received from the data channels of the endpoints of this conference.");

        /**
         * The total number of messages sent via the data channels of the
         * endpoints of this conference.
         */
        public CounterMetric colibriWebSocketMessagesSent = VideobridgeMetricsContainer.getInstance().registerCounter(
                "colibri_web_socket_messages_sent",
                "Number of messages sent via the data channels of the endpoints of this conference.");

        /**
         * The total number of bytes received in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public CounterMetric packetsReceived = VideobridgeMetricsContainer.getInstance().registerCounter(
                "packets_received",
                "Number of RTP packets received in conferences on this videobridge.");

        /**
         * The total number of RTP packets sent in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public CounterMetric packetsSent = VideobridgeMetricsContainer.getInstance().registerCounter(
                "packets_sent",
                "Number of RTP packets sent in conferences on this videobridge.");

        /**
         * The total number of bytes received by relays in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent by relays in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received by relays in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public CounterMetric relayPacketsReceived = VideobridgeMetricsContainer.getInstance().registerCounter(
                "relay_packets_received",
                "Number of RTP packets received by relays in conferences on this videobridge.");

        /**
         * The total number of RTP packets sent by relays in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public CounterMetric relayPacketsSent = VideobridgeMetricsContainer.getInstance().registerCounter(
                "relay_packets_sent",
                "Number of RTP packets sent by relays in conferences on this videobridge.");
        /**
         * The total number of endpoints created.
         */
        public CounterMetric totalEndpoints = VideobridgeMetricsContainer.getInstance().registerCounter(
                "endpoints",
                "The total number of endpoints created.");

        /**
         * The total number of visitor endpoints.
         */
        public CounterMetric totalVisitors = VideobridgeMetricsContainer.getInstance().registerCounter(
                "visitors",
                "The total number of visitor endpoints created.");

        /**
         * The number of endpoints which had not established an endpoint
         * message transport even after some delay.
         */
        public CounterMetric numEndpointsNoMessageTransportAfterDelay = VideobridgeMetricsContainer.getInstance()
                .registerCounter("endpoints_no_message_transport_after_delay",
                "Number of endpoints which had not established a relay message transport even after some delay.");

        /**
         * The total number of relays created.
         */
        public CounterMetric totalRelays = VideobridgeMetricsContainer.getInstance().registerCounter(
                "relays",
                "The total number of relays created.");

        /**
         * The number of relays which had not established a relay
         * message transport even after some delay.
         */
        public CounterMetric numRelaysNoMessageTransportAfterDelay = VideobridgeMetricsContainer.getInstance()
                .registerCounter("relays_no_message_transport_after_delay",
                "Number of relays which had not established a relay message transport even after some delay.");

        /**
         * The total number of times the dominant speaker in any conference
         * changed.
         */
        public CounterMetric dominantSpeakerChanges = VideobridgeMetricsContainer.getInstance().registerCounter(
                "dominant_speaker_changes",
                "Number of times the dominant speaker in any conference changed.");

        /**
         * Number of endpoints whose ICE connection was established, but DTLS
         * wasn't (at the time of expiration).
         */
        public CounterMetric endpointsDtlsFailed = VideobridgeMetricsContainer.getInstance().registerCounter(
                "endpoints_dtls_failed",
                "Number of endpoints whose ICE connection was established, but DTLS wasn't (at time of expiration).");

        /**
         * The stress level for this bridge
         */
        public Double stressLevel = 0.0;

        /** Distribution of energy scores for discarded audio packets  */
        public BucketStats tossedPacketsEnergy = new BucketStats(
                Stream.iterate(0L, n -> n + 1).limit(17)
                        .map(w -> Math.max(8 * w - 1, 0))
                        .collect(Collectors.toList()),
                "", "");

        /** Number of preemptive keyframe requests that were sent. */
        public CounterMetric preemptiveKeyframeRequestsSent = VideobridgeMetricsContainer.getInstance().registerCounter(
                "preemptive_keyframe_requests_sent",
                "Number of preemptive keyframe requests that were sent.");

        /** Number of preemptive keyframe requests that were not sent because no endpoints were in stage view. */
        public CounterMetric preemptiveKeyframeRequestsSuppressed = VideobridgeMetricsContainer.getInstance()
                .registerCounter("preemptive_keyframe_requests_suppressed",
                "Number of preemptive keyframe requests that were not sent because no endpoints were in stage view.");

        /** The total number of keyframes that were received (updated on endpoint expiration). */
        public CounterMetric keyframesReceived = VideobridgeMetricsContainer.getInstance().registerCounter(
                "keyframes_received",
                "Number of keyframes that were received (updated on endpoint expiration).");

        /**
         * The total number of times the layering of an incoming video stream changed (updated on endpoint expiration).
         */
        public CounterMetric layeringChangesReceived = VideobridgeMetricsContainer.getInstance().registerCounter(
                "layering_changes_received",
                "Number of times the layering of an incoming video stream changed (updated on endpoint expiration).");

        /**
         * The total duration, in milliseconds, of video streams (SSRCs) that were received. For example, if an
         * endpoint sends simulcast with 3 SSRCs for 1 minute it would contribute a total of 3 minutes. Suspended
         * streams do not contribute to this duration.
         *
         * This is updated on endpoint expiration.
         */
        public AtomicLong totalVideoStreamMillisecondsReceived = new AtomicLong();

        /**
         * Number of local endpoints that exist currently.
         */
        public LongGaugeMetric currentLocalEndpoints = VideobridgeMetricsContainer.getInstance().registerLongGauge(
                "local_endpoints",
                "Number of local endpoints that exist currently."
        );

        /**
         * Number of visitor endpoints that exist currently.
         */
        public LongGaugeMetric currentVisitors = VideobridgeMetricsContainer.getInstance().registerLongGauge(
                "current_visitors",
                "Number of visitor endpoints."
        );

        /**
         * Current number of conferences.
         */
        public LongGaugeMetric currentConferences = VideobridgeMetricsContainer.getInstance().registerLongGauge(
                "conferences",
                "Current number of conferences."
        );
    }

    private static class ConferenceNotFoundException extends Exception {}
    private static class ConferenceAlreadyExistsException extends Exception {}
    private static class InGracefulShutdownException extends Exception {}
}
