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
import org.apache.commons.lang3.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.util.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.load_management.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.version.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

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
     * The REST-like HTTP/JSON API of Jitsi Videobridge.
     */
    public static final String REST_API = "rest";

    /**
     * The (base) <tt>System</tt> and/or <tt>ConfigurationService</tt> property
     * of the REST-like HTTP/JSON API of Jitsi Videobridge.
     *
     * NOTE: Previously, the rest API name ("org.jitsi.videobridge.rest")
     * conflicted with other values in the new config, since we have
     * other properties scoped *under* "org.jitsi.videobridge.rest".   The
     * long term solution will be to port the command-line args to new config,
     * but for now we rename the property used to signal the rest API is
     * enabled so it doesn't conflict.
     */
    public static final String REST_API_PNAME = "org.jitsi.videobridge." + REST_API + "_api_temp";

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferencesById = new HashMap<>();

    /**
     * Indicates if this bridge instance has entered graceful shutdown mode.
     */
    private boolean shutdownInProgress;

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
     * The shim which handles Colibri-related logic for this
     * {@link Videobridge}.
     */
    private final VideobridgeShim shim = new VideobridgeShim(this);

    /**
     * The {@link JvbLoadManager} instance used for this bridge.
     */
    private final JvbLoadManager<PacketRateMeasurement> jvbLoadManager;

    /**
     * The task which manages the recurring load sampling and updating of
     * {@link Videobridge#jvbLoadManager}.
     */
    private final ScheduledFuture<?> loadSamplerTask;

    public final JvbHealthChecker healthChecker;

    private final VersionService versionService;

    @NotNull private final ShutdownServiceImpl shutdownService;

    private final EventEmitter<EventHandler> eventEmitter = new EventEmitter<>();

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
        @NotNull ShutdownServiceImpl shutdownService)
    {
        videobridgeExpireThread = new VideobridgeExpireThread(this);
        jvbLoadManager = new JvbLoadManager<>(
            PacketRateMeasurement.getLoadedThreshold(),
            PacketRateMeasurement.getRecoveryThreshold(),
            new LastNReducer(
                this::getConferences,
                JvbLastNKt.jvbLastNSingleton
            )
        );
        loadSamplerTask = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(
            new PacketRateLoadSampler(
                this,
                (loadMeasurement) -> {
                    // Update the load manager with the latest measurement
                    jvbLoadManager.loadUpdate(loadMeasurement);
                    // Update the stats with the latest stress level
                    getStatistics().stressLevel = jvbLoadManager.getCurrentStressLevel();
                    return Unit.INSTANCE;
                }
            ),
            0,
            10,
            TimeUnit.SECONDS
        );
        if (xmppConnection != null)
        {
            xmppConnection.setEventHandler(new XmppConnectionEventHandler());
        }
        healthChecker = new JvbHealthChecker(this);
        versionService = new JvbVersionService();
        this.shutdownService = shutdownService;
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @param name world readable name of the conference to create.
     * @param gid the optional "global" id of the conference.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public @NotNull Conference createConference(EntityBareJid name, long gid)
    {
        return this.createConference(name, /* enableLogging */ true, gid);
    }

    /**
     * Generate conference IDs until one is found that isn't in use and create a new {@link Conference}
     * object using that ID
     * @param name
     * @param enableLogging
     * @param gid
     * @return
     */
    private @NotNull Conference doCreateConference(EntityBareJid name, boolean enableLogging, long gid)
    {
        Conference conference = null;
        do
        {
            String id = generateConferenceID();

            synchronized (conferencesById)
            {
                if (!conferencesById.containsKey(id))
                {
                    conference
                        = new Conference(
                                this,
                                id,
                                name,
                                enableLogging,
                                gid);
                    conferencesById.put(id, conference);
                }
            }
        }
        while (conference == null);

        return conference;
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @param name world readable name of the conference to create.
     * @param enableLogging whether logging should be enabled or disabled for
     * the {@link Conference}.
     */
    public @NotNull Conference createConference(EntityBareJid name, boolean enableLogging)
    {
        return createConference(name, enableLogging, Conference.GID_NOT_SET);
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @param name world readable name of the conference to create.
     * @param enableLogging whether logging should be enabled or disabled for
     * the {@link Conference}.
     * @param gid the "global" id of the conference (or
     * {@link Conference#GID_NOT_SET} if it is not specified.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public @NotNull Conference createConference(EntityBareJid name, boolean enableLogging, long gid)
    {
        final Conference conference = doCreateConference(name, enableLogging, gid);

        logger.info(() -> "create_conf, id=" + conference.getID()
                + " gid=" + conference.getGid()
                + " logging=" + enableLogging);

        if (conference.includeInStatistics())
        {
            eventEmitter.fireEvent(handler ->
            {
                handler.conferenceCreated(conference);
                return Unit.INSTANCE;
            });
        }

        return conference;
    }

    /**
     * Enables graceful shutdown mode on this bridge instance and eventually
     * starts the shutdown immediately if no conferences are currently being
     * hosted. Otherwise bridge will shutdown once all conferences expire.
     */
    private void enableGracefulShutdownMode()
    {
        if (!shutdownInProgress)
        {
            logger.info("Entered graceful shutdown mode");
        }
        this.shutdownInProgress = true;
        maybeDoShutdown();
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

        synchronized (conferencesById)
        {
            if (conference.equals(conferencesById.get(id)))
            {
                conferencesById.remove(id);
                conference.expire();
                if (conference.includeInStatistics())
                {
                    eventEmitter.fireEvent(handler ->
                    {
                        handler.conferenceExpired(conference);
                        return Unit.INSTANCE;
                    });
                }
            }
        }

        // Check if it's the time to shutdown now
        maybeDoShutdown();
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
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     */
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ)
    {
        return shim.handleColibriConferenceIQ(conferenceIQ);
    }

    /**
     * Handles <tt>HealthCheckIQ</tt> by performing health check on this
     * <tt>Videobridge</tt> instance.
     *
     * @param healthCheckIQ the <tt>HealthCheckIQ</tt> to be handled.
     * @return IQ with &quot;result&quot; type if the health check succeeded or
     * IQ with &quot;error&quot; type if something went wrong.
     * {@link XMPPError.Condition#internal_server_error} is returned when the
     * health check fails or {@link XMPPError.Condition#not_authorized} if the
     * request comes from a JID that is not authorized to do health checks on
     * this instance.
     */
    public IQ handleHealthCheckIQ(HealthCheckIQ healthCheckIQ)
    {
        try
        {
            return IQ.createResultIQ(healthCheckIQ);
        }
        catch (Exception e)
        {
            logger.warn("Exception while handling health check IQ request", e);
            return
                IQUtils.createError(
                        healthCheckIQ,
                        XMPPError.Condition.internal_server_error,
                        e.getMessage());
        }
    }

    /**
     * Returns a string representing the health of this {@link Videobridge}.
     * Note that this method does not perform any tests, but only checks the
     * cached value provided by the {@link org.jitsi.health.HealthCheckService}.
     */
    private String getHealthStatus()
    {
        Exception result = healthChecker.getResult();
        return result == null ? "OK" : result.getMessage();
    }

    /**
     * Handles a <tt>GracefulShutdownIQ</tt> stanza which represents a request.
     *
     * @param shutdownIQ the <tt>GracefulShutdownIQ</tt> stanza represents
     *        the request to handle
     */
    public void shutdown(boolean graceful)
    {
        logger.warn("Received shutdown request, graceful=" + graceful);
        if (graceful)
        {
            enableGracefulShutdownMode();
        }
        else
        {
            logger.warn("Will shutdown in 1 second.");
            new Thread(() -> {
                try
                {
                    Thread.sleep(1000);
                    logger.warn("JVB force shutdown - now");
                    System.exit(0);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }, "ForceShutdownThread").start();
        }
    }

    /**
     * Returns {@code true} if this instance has entered graceful shutdown mode.
     *
     * @return {@code true} if this instance has entered graceful shutdown mode;
     * otherwise, {@code false}
     */
    public boolean isShutdownInProgress()
    {
        return shutdownInProgress;
    }

    /**
     * Triggers the shutdown given that we're in graceful shutdown mode and
     * there are no conferences currently in progress.
     */
    private void maybeDoShutdown()
    {
        if (!shutdownInProgress)
        {
            return;
        }

        synchronized (conferencesById)
        {
            if (conferencesById.isEmpty())
            {
                logger.info("Videobridge is shutting down NOW");
                shutdownService.beginShutdown();
            }
        }
    }

    /**
     * Starts this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * NOTE: we have to make this public so Jicofo can call it from its
     * tests
     */
    public void start()
    {
        UlimitCheck.printUlimits();

        videobridgeExpireThread.start();
        healthChecker.start();

        // <conference>
        ProviderManager.addIQProvider(
                ColibriConferenceIQ.ELEMENT_NAME,
                ColibriConferenceIQ.NAMESPACE,
                new ColibriIQProvider());

        // ICE-UDP <transport>
        ProviderManager.addExtensionProvider(
                IceUdpTransportPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(IceUdpTransportPacketExtension.class));

        DefaultPacketExtensionProvider<CandidatePacketExtension> candidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(CandidatePacketExtension.class);

        // ICE-UDP <candidate>
        ProviderManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                candidatePacketExtensionProvider);
        ProviderManager.addExtensionProvider(
                RtcpmuxPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(RtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        ProviderManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT_NAME,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(DtlsFingerprintPacketExtension.class));

        // Health-check
        ProviderManager.addIQProvider(
                HealthCheckIQ.ELEMENT_NAME,
                HealthCheckIQ.NAMESPACE,
                new HealthCheckIQProvider());
    }

    /**
     * Stops this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is to stop
     *
     * NOTE: we have to make this public so Jicofo can call it from its
     * tests
     */
    public void stop()
    {
        videobridgeExpireThread.stop();
        healthChecker.stop();
        if (loadSamplerTask != null)
        {
            loadSamplerTask.cancel(true);
        }
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
        debugState.put("shutdownInProgress", shutdownInProgress);
        debugState.put("time", System.currentTimeMillis());

        debugState.put("health", getHealthStatus());
        debugState.put("load-management", jvbLoadManager.getStats());
        debugState.put(Endpoint.overallAverageBridgeJitter.name, Endpoint.overallAverageBridgeJitter.get());

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
                getJsonFromQueueErrorHandler(Endpoint.queueErrorCounter));
        queueStats.put(
                "octo_receive_queue",
                getJsonFromQueueErrorHandler(ConfOctoTransport.queueErrorCounter));
        queueStats.put(
                "octo_send_queue",
                getJsonFromQueueErrorHandler(OctoRtpReceiver.queueErrorCounter));
        queueStats.put(
                "rtp_receiver_queue",
                getJsonFromQueueErrorHandler(RtpReceiverImpl.Companion.getQueueErrorCounter()));
        queueStats.put(
                "rtp_sender_queue",
                getJsonFromQueueErrorHandler(RtpSenderImpl.Companion.getQueueErrorCounter()));

        return queueStats;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getJsonFromQueueErrorHandler(
            CountingErrorHandler countingErrorHandler)
    {
        JSONObject json = new JSONObject();
        json.put("dropped_packets", countingErrorHandler.getNumPacketsDropped());
        json.put("exceptions", countingErrorHandler.getNumExceptions());
        return json;
    }

    /**
     * Gets the version of the jitsi-videobridge application.
     */
    // TODO(brian): this should just return a Version, instead of the VersionService,
    // but Jicoco needs access to the VersionService right now (though it would work
    // just fine with just having the Version).  So fix this once Jicoco is fixed.
    public VersionService getVersionService()
    {
        return versionService;
    }

    public void addEventHandler(EventHandler eventHandler)
    {
        eventEmitter.addHandler(eventHandler);
    }

    public void removeEventHandler(EventHandler eventHandler)
    {
        eventEmitter.removeHandler(eventHandler);
    }

    private class XmppConnectionEventHandler implements XmppConnection.EventHandler
    {
        @NotNull
        @Override
        public IQ colibriConferenceIqReceived(@NotNull ColibriConferenceIQ iq)
        {
            return handleColibriConferenceIQ(iq);
        }

        @NotNull
        @Override
        public IQ versionIqReceived(@NotNull org.jivesoftware.smackx.iqversion.packet.Version iq)
        {
            Version currentVersion = versionService.getCurrentVersion();
            if (currentVersion == null)
            {
                return IQ.createErrorResponse(
                    iq,
                    XMPPError.getBuilder(XMPPError.Condition.internal_server_error)
                );
            }

            // send packet
            org.jivesoftware.smackx.iqversion.packet.Version versionResult =
                new org.jivesoftware.smackx.iqversion.packet.Version(
                    currentVersion.getApplicationName(),
                    currentVersion.toString(),
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
            return handleHealthCheckIQ(iq);
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
         * The cumulative/total number of conferences that had all of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalFailedConferences = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences that had some of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalPartiallyFailedConferences = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences completed/expired on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCompleted = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences created on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCreated = new AtomicInteger(0);

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
         * The total number of times an ICE Agent failed to establish
         * connectivity.
         */
        public AtomicInteger totalIceFailed = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded.
         */
        public AtomicInteger totalIceSucceeded = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded and the selected
         * candidate was a TCP candidate.
         */
        public AtomicInteger totalIceSucceededTcp = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded and the selected
         * candidate pair included a relayed candidate.
         */
        public AtomicInteger totalIceSucceededRelayed = new AtomicInteger();

        /**
         * The total number of messages received from the data channels of
         * the {@link Endpoint}s of this conference.
         */
        public AtomicLong totalDataChannelMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * {@link Endpoint}s of this conference.
         */
        public AtomicLong totalDataChannelMessagesSent = new AtomicLong();

        /**
         * The total number of messages received from the data channels of
         * the {@link Endpoint}s of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * {@link Endpoint}s of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesSent = new AtomicLong();

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
        public AtomicLong totalPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets sent in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalPacketsSent = new AtomicLong();

        /**
         * The total number of endpoints created.
         */
        public AtomicInteger totalEndpoints = new AtomicInteger();

        /**
         * The number of endpoints which had not established an endpoint
         * message transport even after some delay.
         */
        public AtomicInteger numEndpointsNoMessageTransportAfterDelay = new AtomicInteger();

        /**
         * The total number of times the dominant speaker in any conference
         * changed.
         */
        public LongAdder totalDominantSpeakerChanges = new LongAdder();

        /**
         * Number of endpoints whose ICE connection was established, but DTLS
         * wasn't (at the time of expiration).
         */
        public AtomicInteger dtlsFailedEndpoints = new AtomicInteger();

        /**
         * The stress level for this bridge
         */
        public Double stressLevel = 0.0;
    }

    public static interface EventHandler {
        void conferenceCreated(Conference conference);
        void conferenceExpired(Conference conference);
    }
}
