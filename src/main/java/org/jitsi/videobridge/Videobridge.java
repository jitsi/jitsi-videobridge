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
import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jetbrains.annotations.*;
import org.jitsi.config.*;
import org.jitsi.eventadmin.*;
import org.jitsi.health.*;
import org.jitsi.meet.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.util.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.version.Version;
import org.jitsi.videobridge.ice.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.version.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

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
     * The property that specifies allowed entities for turning on graceful
     * shutdown mode. For XMPP API this is "from" JID. In case of REST
     * the source IP is being copied into the "from" field of the IQ.
     */
    public static final String SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME
        = "org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP";

    /**
     * The (OSGi) <tt>BundleContext</tt> in which this <tt>Videobridge</tt> has
     * been started.
     */
    private BundleContext bundleContext;

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferences = new HashMap<>();

    /**
     * Indicates if this bridge instance has entered graceful shutdown mode.
     */
    private boolean shutdownInProgress;

    /**
     * The pattern used to filter entities that are allowed to trigger graceful
     * shutdown mode.
     */
    private Pattern shutdownSourcePattern;

    /**
     * A class that holds some instance statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Thread that checks expiration for conferences, contents, channels and
     * execute expire procedure for any of them.
     */
    private VideobridgeExpireThread videobridgeExpireThread;

    /**
     * The shim which handles Colibri-related logic for this
     * {@link Videobridge}.
     */
    private final VideobridgeShim shim = new VideobridgeShim(this);

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
    public Videobridge()
    {
        videobridgeExpireThread = new VideobridgeExpireThread(this);
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
    public @NotNull Conference createConference(Localpart name, long gid)
    {
        return this.createConference(name == null ? null : name.toString(), /* enableLogging */ true, gid);
    }

    /**
     * Generate conference IDs until one is found that isn't in use and create a new {@link Conference}
     * object using that ID
     * @param name
     * @param enableLogging
     * @param gid
     * @return
     */
    private @NotNull Conference doCreateConference(String name, boolean enableLogging, long gid)
    {
        Conference conference = null;
        do
        {
            String id = generateConferenceID();

            synchronized (conferences)
            {
                if (!conferences.containsKey(id))
                {
                    conference
                        = new Conference(
                                this,
                                id,
                                name,
                                enableLogging,
                                gid);
                    conferences.put(id, conference);
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
    public @NotNull Conference createConference(
            String name, boolean enableLogging)
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
    public @NotNull Conference createConference(
            String name, boolean enableLogging, long gid)
    {
        final Conference conference = doCreateConference(name, enableLogging, gid);

        logger.info(() -> "create_conf, id=" + conference.getID()
                + " gid=" + conference.getGid()
                + " logging=" + enableLogging);

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
        boolean expireConference;

        synchronized (conferences)
        {
            if (conference.equals(conferences.get(id)))
            {
                conferences.remove(id);
                expireConference = true;
            }
            else
            {
                expireConference = false;
            }
        }
        if (expireConference)
        {
            conference.expire();
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
     * Returns the OSGi <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is executing.
     */
    public BundleContext getBundleContext()
    {
        return bundleContext;
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
        synchronized (conferences)
        {
            return conferences.get(id);
        }
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public Collection<Conference> getConferences()
    {
        synchronized (conferences)
        {
            return new HashSet<>(conferences.values());
        }
    }

    /**
     * Returns the <tt>ConfigurationService</tt> used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>ConfigurationService</tt> used by this
     * <tt>Videobridge</tt>.
     */
    public ConfigurationService getConfigurationService()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext == null)
        {
            return null;
        }
        else
        {
            return ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        }
    }

    /**
     * Returns the <tt>EventAdmin</tt> instance (to be) used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>EventAdmin</tt> instance (to be) used by this
     * <tt>Videobridge</tt>.
     */
    public EventAdmin getEventAdmin()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext == null)
        {
            return null;
        }
        else
        {
            return ServiceUtils2.getService(bundleContext, EventAdmin.class);
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
     *
     * @throws Exception if the videobridge is not healthy.
     */
    private String getHealthStatus()
    {
        HealthCheckService health = ServiceUtils2.getService(bundleContext, HealthCheckService.class);
        if (health == null)
        {
            return "No health check service running";
        }

        Exception result = health.getResult();
        return result == null ? "OK" : result.getMessage();
    }

    /**
     * Handles a <tt>GracefulShutdownIQ</tt> stanza which represents a request.
     *
     * @param shutdownIQ the <tt>GracefulShutdownIQ</tt> stanza represents
     *        the request to handle
     * @return an <tt>IQ</tt> stanza which represents the response to
     *         the specified request or <tt>null</tt> to reply with
     *         <tt>feature-not-implemented</tt>
     */
    public IQ handleShutdownIQ(ShutdownIQ shutdownIQ)
    {
        // Security not configured - service unavailable
        if (shutdownSourcePattern == null)
        {
            return IQUtils.createError(shutdownIQ, XMPPError.Condition.service_unavailable);
        }
        // Check if source matches pattern
        Jid from = shutdownIQ.getFrom();
        if (from != null && shutdownSourcePattern.matcher(from).matches())
        {
            logger.info("Accepted shutdown request from: " + from);
            if (shutdownIQ.isGracefulShutdown())
            {
                if (!isShutdownInProgress())
                {
                    enableGracefulShutdownMode();
                }
            }
            else
            {
                new Thread(() -> {
                    try
                    {
                        Thread.sleep(1000);
                        logger.warn("JVB force shutdown - now");
                        System.exit(0);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }, "ForceShutdownThread").start();
            }
            return IQ.createResultIQ(shutdownIQ);
        }
        else
        {
            // Unauthorized
            logger.error("Rejected shutdown request from: " + from);
            return IQUtils.createError(shutdownIQ, XMPPError.Condition.not_authorized);
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

        synchronized (conferences)
        {
            if (conferences.isEmpty())
            {
                logger.info("Videobridge is shutting down NOW");
                ShutdownService shutdownService = ServiceUtils2.getService(bundleContext, ShutdownService.class);
                if (shutdownService != null)
                {
                    shutdownService.beginShutdown();
                }
            }
        }
    }

    /**
     * Starts this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is to start
     */
    void start(final BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;

        UlimitCheck.printUlimits();

        ConfigurationService cfg = getConfigurationService();

        videobridgeExpireThread.start();

        String shutdownSourcesRegexp
            = (cfg == null)
                ? null
                : cfg.getString(SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME);

        if (!StringUtils.isBlank(shutdownSourcesRegexp))
        {
            try
            {
                shutdownSourcePattern = Pattern.compile(shutdownSourcesRegexp);
            }
            catch (PatternSyntaxException exc)
            {
                logger.error(
                   "Error parsing enableGracefulShutdownMode sources reg expr: "
                        + shutdownSourcesRegexp, exc);
            }
        }

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

        startIce4j(bundleContext, cfg);
    }

    /**
     * Implements the ice4j-related portion of {@link #start(BundleContext)}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Videobridge} is to start
     */
    private void startIce4j(
            BundleContext bundleContext,
            ConfigurationService cfg)
    {
        // TODO Packet logging for ice4j is not supported at this time.
        StunStack.setPacketLogger(null);

        // Make all ice4j properties system properties.
        if (cfg != null)
        {
            List<String> ice4jPropertyNames = cfg.getPropertyNamesByPrefix("org.ice4j", false);

            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
            {
                for (String propertyName : ice4jPropertyNames)
                {
                    String propertyValue = cfg.getString(propertyName);

                    // we expect the getString to return either null or a
                    // non-empty String object.
                    if (propertyValue != null)
                    {
                        System.setProperty(propertyName, propertyValue);
                    }
                }
            }

            // Reload for all the new system properties to be seen
            JitsiConfig.Companion.reload();
        }

        // Initialize the the host candidate interface filters in the ice4j
        // stack.
        try
        {
            HostCandidateHarvester.initializeInterfaceFilters();
        }
        catch (Exception e)
        {
            logger.warn(
                    "There were errors during host candidate interface filters"
                        + " initialization.",
                    e);
        }

        // Start the initialization of the mapping candidate harvesters.
        // Asynchronous, because the AWS and STUN harvester may take a long
        // time to initialize.
        new Thread(MappingCandidateHarvesters::initialize).start();
    }

    /**
     * Stops this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is to stop
     */
    void stop(BundleContext bundleContext)
    {
        try
        {
            ConfigurationService cfg = getConfigurationService();
            stopIce4j(bundleContext, cfg);
        }
        finally
        {
            videobridgeExpireThread.stop(bundleContext);
            this.bundleContext = null;
        }
    }

    /**
     * Implements the ice4j-related portion of {@link #stop(BundleContext)}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Videobridge} is to start
     */
    private void stopIce4j(
        BundleContext bundleContext,
        ConfigurationService cfg)
    {
        // Shut down harvesters.
        Harvesters.closeStaticConfiguration();

        // Clear all system properties that were ice4j properties. This is done
        // to deal with any properties that are conditionally set during
        // initialization. If the conditions have changed upon restart (of the
        // component, rather than the JVM), it would not be enough to "not set"
        // the system property (as it would have survived the restart).
        if (cfg != null)
        {
            List<String> ice4jPropertyNames = cfg.getPropertyNamesByPrefix("org.ice4j", false);

            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
            {
                for (String propertyName : ice4jPropertyNames)
                {
                    System.clearProperty(propertyName);
                }
            }
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
        debugState.put("e2e_packet_delay", Endpoint.getPacketDelayStats());
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
                conference = this.conferences.get(conferenceId);
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
    public Version getVersion()
    {
        return CurrentVersionImpl.VERSION;
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
    }
}
