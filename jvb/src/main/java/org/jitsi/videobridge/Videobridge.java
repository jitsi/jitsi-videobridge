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

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.*;
import org.jitsi.health.Result;
import org.jitsi.nlj.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.load_management.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.shutdown.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.time.*;
import java.util.*;

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
     * <p/>
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
     * <p/>
     * Note that currently most code uses the system clock directly.
     */
    @NotNull
    private final Clock clock;

    /**
     * The {@link JvbLoadManager} instance used for this bridge.
     */
    @NotNull
    private final JvbLoadManager<?> jvbLoadManager;

    @NotNull private final Version version;

    @NotNull private final ShutdownManager shutdownManager;

    /**
     * Initializes a new <tt>Videobridge</tt> instance.
     */
    public Videobridge(
        @Nullable XmppConnection xmppConnection,
        @NotNull ShutdownServiceImpl shutdownService,
        @NotNull Version version,
        @NotNull Clock clock)
    {
        this.clock = clock;
        jvbLoadManager = JvbLoadManager.create(this);
        if (xmppConnection != null)
        {
            xmppConnection.setEventHandler(new XmppConnectionEventHandler());
        }
        this.version = version;
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
                    VideobridgeMetrics.currentConferences.inc();

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
        VideobridgeMetrics.currentLocalEndpoints.inc();
        if (visitor)
        {
            VideobridgeMetrics.currentVisitors.inc();
        }
    }

    void localEndpointExpired(boolean visitor)
    {
        long remainingEndpoints = VideobridgeMetrics.currentLocalEndpoints.decAndGet();
        if (visitor)
        {
            VideobridgeMetrics.currentVisitors.dec();
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
                VideobridgeMetrics.currentConferences.dec();

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
        shutdownManager.maybeShutdown(VideobridgeMetrics.currentLocalEndpoints.get());
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
        VideobridgeMetrics.INSTANCE.getDrainMode().set(enable);
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
     * Stops this {@link Videobridge}.
     */
    void stop()
    {
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
    public OrderedJsonObject getDebugState(String conferenceId, String endpointId, DebugStateMode mode)
    {
        OrderedJsonObject debugState = new OrderedJsonObject();

        if (mode == DebugStateMode.FULL || mode == DebugStateMode.SHORT)
        {
            debugState.put("shutdown_state", shutdownManager.getState().toString());
            debugState.put("drain", drainMode);
            debugState.put("time", System.currentTimeMillis());

            debugState.put("load_management", jvbLoadManager.getStats());

            Double jitter = PacketTransitStats.getBridgeJitter();
            if (jitter != null)
            {
                debugState.put("overall_bridge_jitter", jitter);
            }
        }

        JSONObject conferences = new JSONObject();
        debugState.put("conferences", conferences);
        if (StringUtils.isBlank(conferenceId))
        {
            getConferences().stream()
                    .filter(c -> mode != DebugStateMode.STATS || c.isRtcStatsEnabled())
                    .forEach(conference ->
                conferences.put(
                        conference.getID(),
                        conference.getDebugState(mode, null)));
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
                    conference == null ? "null" : conference.getDebugState(mode, endpointId));
        }

        return debugState;
    }

    @NotNull
    public Version getVersion()
    {
        return version;
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

    private static class ConferenceNotFoundException extends Exception {}
    private static class ConferenceAlreadyExistsException extends Exception {}
    private static class InGracefulShutdownException extends Exception {}
}
