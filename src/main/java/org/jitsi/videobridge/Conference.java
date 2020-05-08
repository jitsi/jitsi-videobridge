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

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jetbrains.annotations.Nullable;
import org.jitsi.eventadmin.*;
import org.jitsi.nlj.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.collections.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

import java.beans.*;
import java.io.*;
import java.lang.*;
import java.lang.SuppressWarnings;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import static org.jitsi.utils.collections.JMap.entry;
import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Represents a conference in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Hristo Terezov
 * @author George Politis
 */
public class Conference
     extends PropertyChangeNotifier
     implements PropertyChangeListener,
        Expireable,
        AbstractEndpointMessageTransport.EndpointMessageTransportEventHandler
{
    /**
     * The endpoints participating in this {@link Conference}. Although it's a
     * {@link ConcurrentHashMap}, writing to it must be protected by
     * synchronizing on the map itself, because it must be kept in sync with
     * {@link #endpointsCache}.
     */
    private final ConcurrentHashMap<String, AbstractEndpoint> endpoints
            = new ConcurrentHashMap<>();

    /**
     * A read-only cache of the endpoints in this conference. Note that it
     * contains only the {@link Endpoint} instances (and not Octo endpoints).
     * This is because the cache was introduced for performance reasons only
     * (we iterate over it for each RTP packet) and the Octo endpoints are not
     * needed.
     */
    private List<Endpoint> endpointsCache = Collections.emptyList();

    private final Object endpointsCacheLock = new Object();

    /**
     * The {@link EventAdmin} instance (to be) used by this {@code Conference}
     * and all instances (of {@code Content}, {@code Channel}, etc.) created by
     * it.
     */
    private final EventAdmin eventAdmin;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Conference</tt>.
     */
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "The value is deemed safe to read without " +
                "synchronization.")
    private boolean expired = false;

    /**
     * The JID of the conference focus who has initialized this instance and
     * from whom requests to manage this instance must come or they will be
     * ignored. If <tt>null</tt> value is assigned we don't care who modifies
     * the conference.
     */
    private final Jid focus;

    /**
     * The (unique) identifier/ID of this instance.
     */
    private final String id;

    /**
     * The "global" id of this conference, set by the controller (e.g. jicofo)
     * as opposed to the bridge. This defaults to {@code null} unless it is
     * specified.
     */
    private final String gid;

    /**
     * The world readable name of this instance if any.
     */
    private Localpart name;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Conference</tt>. In the time interval between the last activity and
     * now, this <tt>Conference</tt> is considered inactive.
     */
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "The value is deemed safe to read without " +
                    "synchronization.")
    private long lastActivityTime;

    /**
     * If {@link #focus} is <tt>null</tt> the value of the last known focus is
     * stored in this member.
     */
    private Jid lastKnownFocus;

    /**
     * The <tt>PropertyChangeListener</tt> which listens to
     * <tt>PropertyChangeEvent</tt>s on behalf of this instance while
     * referencing it by a <tt>WeakReference</tt>.
     */
    private final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * The speech activity (representation) of the <tt>Endpoint</tt>s of this
     * <tt>Conference</tt>.
     */
    private final ConferenceSpeechActivity speechActivity;

    /**
     * The audio level listener.
     */
    private final AudioLevelListener audioLevelListener;

    /**
     * The <tt>Videobridge</tt> which has initialized this <tt>Conference</tt>.
     */
    private final Videobridge videobridge;

    /**
     * Holds conference statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;


    /**
     * Whether this conference should be considered when generating statistics.
     */
    private final boolean includeInStatistics;

    /**
     * The time when this {@link Conference} was created.
     */
    private final long creationTime = System.currentTimeMillis();

    /**
     * The {@link ExpireableImpl} which we use to safely expire this conference.
     */
    private final ExpireableImpl expireableImpl;

    /**
     * The shim which handles Colibri-related logic for this conference.
     */
    private final ConferenceShim shim;

    //TODO not public
    final public EncodingsManager encodingsManager = new EncodingsManager();

    /**
     * This {@link Conference}'s link to Octo.
     */
    private ConfOctoTransport tentacle;

    /**
     * Initializes a new <tt>Conference</tt> instance which is to represent a
     * conference in the terms of Jitsi Videobridge which has a specific
     * (unique) ID and is managed by a conference focus with a specific JID.
     *
     * @param videobridge the <tt>Videobridge</tt> on which the new
     * <tt>Conference</tt> instance is to be initialized
     * @param id the (unique) ID of the new instance to be initialized
     * @param focus the JID of the conference focus who has requested the
     * initialization of the new instance and from whom further/future requests
     * to manage the new instance must come or they will be ignored.
     * Pass <tt>null</tt> to override this safety check.
     * @param name world readable name of this instance if any.
     * @param enableLogging whether logging should be enabled for this
     * {@link Conference} and its sub-components, and whether this conference
     * should be considered when generating statistics.
     * @param gid the optional "global" id of the conference.
     */
    public Conference(Videobridge videobridge,
                      String id,
                      Jid focus,
                      Localpart name,
                      boolean enableLogging,
                      String gid)
    {
        this.videobridge = Objects.requireNonNull(videobridge, "videobridge");
        Level minLevel = enableLogging ? Level.ALL : Level.WARNING;
        Map<String, String> context = JMap.ofEntries(
            entry("confId", id)
        );
        if (gid != null)
        {
            context.put("gid", gid);
        }
        if (name != null)
        {
            context.put("conf_name", name.toString());
        }
        logger = new LoggerImpl(Conference.class.getName(), minLevel, new LogContext(context));
        this.shim = new ConferenceShim(this, logger);
        this.id = Objects.requireNonNull(id, "id");
        this.gid = gid;
        this.focus = focus;
        this.eventAdmin = enableLogging ? videobridge.getEventAdmin() : null;
        this.includeInStatistics = enableLogging;
        this.name = name;

        lastKnownFocus = focus;

        speechActivity = new ConferenceSpeechActivity(this);
        audioLevelListener
            = (sourceSsrc, level)
                -> speechActivity.levelChanged(sourceSsrc, (int) level);

        expireableImpl = new ExpireableImpl(logger, this::expire);

        if (enableLogging)
        {
            eventAdmin.sendEvent(EventFactory.conferenceCreated(this));
            Videobridge.Statistics videobridgeStatistics
                = videobridge.getStatistics();
            videobridgeStatistics.totalConferencesCreated.incrementAndGet();
        }

        // We listen to our own events so we have a centralized place to handle
        // certain things (e.g. anytime the endpoints list changes)
        addPropertyChangeListener(propertyChangeListener);

        touch();
    }

    /**
     * Creates a new diagnostic context instance that includes the conference
     * name and the conference creation time.
     *
     * @return the new {@link DiagnosticContext} instance.
     */
    public DiagnosticContext newDiagnosticContext()
    {


        if (name != null)
        {
            DiagnosticContext diagnosticContext = new DiagnosticContext();
            diagnosticContext.put("conf_name", name.toString());
            diagnosticContext.put("conf_creation_time_ms", creationTime);
            return diagnosticContext;
        }
        else
        {
            return new NoOpDiagnosticContext();
        }
    }

    /**
     * Gets the statistics of this {@link Conference}.
     *
     * @return the statistics of this {@link Conference}.
     */
    public Statistics getStatistics()
    {
        return statistics;
    }

    /**
     * @return whether this conference should be included in generated
     * statistics.
     */
     public boolean includeInStatistics()
     {
         return includeInStatistics;
     }

    /**
     * Sends a message to a subset of endpoints in the call, primary use
     * case being a message that has originated from an endpoint (as opposed to
     * a message originating from the bridge and being sent to all endpoints in
     * the call, for that see {@link #broadcastMessage(String)}.
     *
     * @param msg the message to be sent
     * @param endpoints the list of <tt>Endpoint</tt>s to which the message will
     * be sent.
     */
    public void sendMessage(
        String msg,
        List<AbstractEndpoint> endpoints,
        boolean sendToOcto)
    {
        for (AbstractEndpoint endpoint : endpoints)
        {
            try
            {
                endpoint.sendMessage(msg);
            }
            catch (IOException e)
            {
                logger.error(
                    "Failed to send message on data channel to: "
                        + endpoint.getID() + ", msg: " + msg, e);
            }
        }

        if (sendToOcto && tentacle != null)
        {
            tentacle.sendMessage(msg);
        }
    }

    /**
     * Used to send a message to a subset of endpoints in the call, primary use
     * case being a message that has originated from an endpoint (as opposed to
     * a message originating from the bridge and being sent to all endpoints in
     * the call, for that see {@link #broadcastMessage(String)}.
     *
     * @param msg the message to be sent
     * @param endpoints the list of <tt>Endpoint</tt>s to which the message will
     * be sent.
     */
    public void sendMessage(String msg, List<AbstractEndpoint> endpoints)
    {
        sendMessage(msg, endpoints, false);
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(String msg, boolean sendToOcto)
    {
        sendMessage(msg, getEndpoints(), sendToOcto);
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(String msg)
    {
        broadcastMessage(msg, false);
    }

    /**
     * Requests a keyframe from the endpoint with the specified id, if the
     * endpoint is found in the conference.
     *
     * @param endpointID the id of the endpoint to request a keyframe from.
     */
    public void requestKeyframe(String endpointID, long mediaSsrc)
    {
        AbstractEndpoint remoteEndpoint = getEndpoint(endpointID);

        if (remoteEndpoint != null)
        {
            remoteEndpoint.requestKeyframe(mediaSsrc);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug(
                "Cannot request keyframe because the endpoint was not found.");
        }
    }
    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is shallow i.e. the
     * <tt>Content</tt>s of this instance are not described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(getID());
        iq.setName(getName());
    }

    /**
     * Notifies this instance that {@link #speechActivity} has identified a
     * speaker switch event in this multipoint conference and there is now a new
     * dominant speaker.
     */
    void dominantSpeakerChanged()
    {
        AbstractEndpoint dominantSpeaker = speechActivity.getDominantEndpoint();

        if (logger.isInfoEnabled())
        {
            String id
                = dominantSpeaker == null ? "null" : dominantSpeaker.getID();
            logger.info("ds_change ds_id=" + id);
            getVideobridge().getStatistics().totalDominantSpeakerChanges.increment();
        }

        speechActivityEndpointsChanged(speechActivity.getEndpointIds());

        if (dominantSpeaker != null)
        {
            broadcastMessage(
                    createDominantSpeakerEndpointChangeEvent(
                        dominantSpeaker.getID()));
            if (getEndpointCount() > 2)
            {
                double senderRtt = getRtt(dominantSpeaker);
                double maxReceiveRtt = getMaxReceiverRtt(dominantSpeaker.getID());
                // We add an additional 10ms delay to reduce the risk of the keyframe arriving
                // too early
                double keyframeDelay = maxReceiveRtt - senderRtt + 10;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Scheduling keyframe request from " + dominantSpeaker.getID() + " after a delay" +
                            " of " + keyframeDelay + "ms");
                }
                TaskPools.SCHEDULED_POOL.schedule(
                        (Runnable)dominantSpeaker::requestKeyframe,
                        (long)keyframeDelay,
                        TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private double getRtt(AbstractEndpoint endpoint)
    {
        if (endpoint instanceof Endpoint)
        {
            Endpoint localDominantSpeaker = (Endpoint)endpoint;
            return localDominantSpeaker.getRtt();
        }
        else
        {
            // Octo endpoint
            // TODO(brian): we don't currently have a way to get the RTT from this bridge
            // to a remote endpoint, so we hard-code a value here.  Discussed this with
            // Boris, and we talked about perhaps having OctoEndpoint periodically
            // send pings to the remote endpoint to calculate its RTT from the perspective
            // of this bridge.
            return 100;
        }
    }

    private double getMaxReceiverRtt(String excludedEndpointId)
    {
        return endpointsCache.stream()
                .filter(ep -> !ep.getID().equalsIgnoreCase(excludedEndpointId))
                .map(Endpoint::getRtt)
                .mapToDouble(Double::valueOf)
                .max()
                .orElse(0);
    }

    /**
     * Expires this <tt>Conference</tt>, its <tt>Content</tt>s and their
     * respective <tt>Channel</tt>s. Releases the resources acquired by this
     * instance throughout its life time and prepares it to be garbage
     * collected.
     */
    public void expire()
    {
        synchronized (this)
        {
            if (expired)
            {
                return;
            }
            else
            {
                expired = true;
            }
        }

        logger.info("Expiring.");
        EventAdmin eventAdmin = getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.conferenceExpired(this));
        }

        Videobridge videobridge = getVideobridge();

        try
        {
            videobridge.expireConference(this);
        }
        finally
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Expiring endpoints.");
            }
            getEndpoints().forEach(AbstractEndpoint::expire);
            speechActivity.expire();
            if (tentacle != null)
            {
                tentacle.expire();
                tentacle = null;
            }

            if (includeInStatistics)
            {
                updateStatisticsOnExpire();
            }
        }
    }

    /**
     * Updates the statistics for this conference when it is about to expire.
     */
    private void updateStatisticsOnExpire()
    {
        long durationSeconds
            = Math.round((System.currentTimeMillis() - creationTime) / 1000d);

        Videobridge.Statistics videobridgeStatistics
            = getVideobridge().getStatistics();

        videobridgeStatistics.totalConferencesCompleted
            .incrementAndGet();
        videobridgeStatistics.totalConferenceSeconds.addAndGet(
            durationSeconds);

        videobridgeStatistics.totalBytesReceived.addAndGet(
            statistics.totalBytesReceived.get());
        videobridgeStatistics.totalBytesSent.addAndGet(
            statistics.totalBytesSent.get());
        videobridgeStatistics.totalPacketsReceived.addAndGet(
            statistics.totalPacketsReceived.get());
        videobridgeStatistics.totalPacketsSent.addAndGet(
            statistics.totalPacketsSent.get());

        boolean hasFailed
            = statistics.hasIceFailedEndpoint
                && !statistics.hasIceSucceededEndpoint;
        boolean hasPartiallyFailed
            = statistics.hasIceFailedEndpoint
                && statistics.hasIceSucceededEndpoint;

        if (hasPartiallyFailed)
        {
            videobridgeStatistics.totalPartiallyFailedConferences
                .incrementAndGet();
        }

        if (hasFailed)
        {
            videobridgeStatistics.totalFailedConferences.incrementAndGet();
        }

        if (logger.isInfoEnabled())
        {
            StringBuilder sb = new StringBuilder("expire_conf,");
            sb.append("duration=").append(durationSeconds)
                .append(",has_failed=").append(hasFailed)
                .append(",has_partially_failed=").append(hasPartiallyFailed);
            logger.info(sb.toString());
        }
    }

    /**
     * Finds an <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with a specific SSRC and with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of an RTP stream received by this
     * <tt>Conference</tt> whose sending <tt>Endpoint</tt> is to be found
     * @return <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with the specified <tt>ssrc</tt> and with the specified
     * <tt>mediaType</tt>; otherwise, <tt>null</tt>
     */
    AbstractEndpoint findEndpointByReceiveSSRC(long receiveSSRC)
    {
        return getEndpoints().stream()
                .filter(ep -> ep.receivesSsrc(receiveSSRC))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the OSGi <tt>BundleContext</tt> in which this Conference is
     * executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which the Conference is
     * executing.
     */
    public BundleContext getBundleContext()
    {
        return getVideobridge().getBundleContext();
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt> or <tt>null</tt>
     */
    @Nullable
    public AbstractEndpoint getEndpoint(@NotNull String id)
    {
        return endpoints.get(
            Objects.requireNonNull(id, "id must be non null"));
    }

    /**
     * Initializes a new <tt>Endpoint</tt> instance with the specified
     * <tt>id</tt> and adds it to the list of <tt>Endpoint</tt>s participating
     * in this <tt>Conference</tt>.
     * @param id the identifier/ID of the <tt>Endpoint</tt> which will be
     * created
     * @param iceControlling {@code true} if the ICE agent of this endpoint's
     * transport will be initialized to serve as a controlling ICE agent;
     * otherwise, {@code false}
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     */
    @NotNull
    public Endpoint createLocalEndpoint(String id, boolean iceControlling)
    {
        final AbstractEndpoint existingEndpoint = getEndpoint(id);
        if (existingEndpoint instanceof OctoEndpoint)
        {
            // It is possible that an Endpoint was migrated from another bridge
            // in the conference to this one, and the sources lists (which
            // implicitly signal the Octo endpoints in the conference) haven't
            // been updated yet. We'll force the Octo endpoint to expire and
            // we'll continue with the creation of a new local Endpoint for the
            // participant.
            existingEndpoint.expire();
        }
        else if (existingEndpoint != null)
        {
            throw new IllegalArgumentException("Local endpoint with ID = "
                + id + "already created");
        }

        final Endpoint endpoint = new Endpoint(
            id, this, logger, iceControlling);
        // The propertyChangeListener will weakly reference this
        // Conference and will unregister itself from the endpoint
        // sooner or later.
        endpoint.addPropertyChangeListener(propertyChangeListener);

        addEndpoint(endpoint);

        EventAdmin eventAdmin = getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(
                EventFactory.endpointCreated(endpoint));
        }

        return endpoint;
    }

    /**
     * An endpoint was added or removed.
     */
    private void endpointsChanged()
    {
        speechActivity.endpointsChanged();
    }

    /**
     * The media stream tracks of one of the endpoints in this conference
     * changed.
     *
     * @param endpoint the endpoint, or {@code null} if it was an Octo endpoint.
     */
    public void endpointTracksChanged(AbstractEndpoint endpoint)
    {
        List<String> endpoints = speechActivity.getEndpointIds();
        endpointsCache.forEach((e) -> {
            if (e != endpoint)
            {
                e.speechActivityEndpointsChanged(endpoints);
            }
        });
    }

    /**
     * Updates {@link #endpointsCache} with the current contents of
     * {@link #endpoints}.
     */
    private void updateEndpointsCache()
    {
        synchronized (endpointsCacheLock)
        {
            ArrayList<Endpoint>
                    endpointsList
                    = new ArrayList<>(endpoints.size());
            endpoints.values().forEach(e ->
            {
                if (e instanceof Endpoint)
                {
                    endpointsList.add((Endpoint) e);
                }
            });

            endpointsCache = Collections.unmodifiableList(endpointsList);
        }
    }

    /**
     * Returns the number of local AND remote {@link Endpoint}s in this {@link Conference}.
     *
     * @return the number of local AND remote {@link Endpoint}s in this {@link Conference}.
     */
    public int getEndpointCount()
    {
        return endpoints.size();
    }

    /**
     * Returns the number of local {@link Endpoint}s in this {@link Conference}.
     *
     * @return the number of local {@link Endpoint}s in this {@link Conference}.
     */
    public int getLocalEndpointCount()
    {
        return getLocalEndpoints().size();
    }

    /**
     * Gets the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>
     */
    public List<AbstractEndpoint> getEndpoints()
    {
        return new ArrayList<>(this.endpoints.values());
    }

    /**
     * Gets the list of endpoints which are local to this bridge (as opposed
     * to being on a remote bridge through Octo).
     */
    public List<Endpoint> getLocalEndpoints()
    {
        return endpointsCache;
    }

    /**
     * Gets the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored.
     *
     * @return the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored
     */
    public final Jid getFocus()
    {
        return focus;
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>
     */
    public long getLastActivityTime()
    {
        synchronized (this)
        {
            return lastActivityTime;
        }
    }

    /**
     * Returns the JID of the last known focus.
     * @return the JID of the last known focus.
     */
    public Jid getLastKnowFocus()
    {
        return lastKnownFocus;
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * or {@code null} if endpoint with specified ID does not exist in a
     * conference
     */
    @Nullable
    public Endpoint getLocalEndpoint(String id)
    {
        AbstractEndpoint endpoint = getEndpoint(id);
        if (endpoint instanceof Endpoint)
        {
            return (Endpoint) endpoint;
        }

        return null;
    }

    /**
     * Gets the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>.
     *
     * @return the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>
     */
    public ConferenceSpeechActivity getSpeechActivity()
    {
        return speechActivity;
    }

    /**
     * Gets the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>
     */
    public final Videobridge getVideobridge()
    {
        return videobridge;
    }

    /**
     * Gets the indicator which determines whether this <tt>Conference</tt> has
     * expired.
     *
     * @return <tt>true</tt> if this <tt>Conference</tt> has expired; otherwise,
     * <tt>false</tt>
     */
    public boolean isExpired()
    {
        // this.expired starts as 'false' and only ever changes to 'true',
        // so there is no need to synchronize while reading.
        return expired;
    }

    /**
     * Notifies this instance that there was a change in the value of a property
     * of an object in which this instance is interested.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the object of
     * interest, the name of the property and the old and new values of that
     * property
     */
    @Override
    @SuppressWarnings("unchecked")
    public void propertyChange(PropertyChangeEvent ev)
    {
        Object source = ev.getSource();

        if (isExpired())
        {
            // An expired Conference is to be treated like a null Conference
            // i.e. it does not handle any PropertyChangeEvents. If possible,
            // make sure that no further PropertyChangeEvents will be delivered
            // to this Conference.
            if (source instanceof PropertyChangeNotifier)
            {
                ((PropertyChangeNotifier) source).removePropertyChangeListener(
                        propertyChangeListener);
            }
        }
        else if (Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME
                .equals(ev.getPropertyName()))
        {
            Set<String> oldSelectedEndpoints = (Set<String>)ev.getOldValue();
            Set<String> newSelectedEndpoints = (Set<String>)ev.getNewValue();
            // Any endpoints in the oldSelectedEndpoints list which AREN'T
            // in the newSelectedEndpoints list should have their count decremented
            oldSelectedEndpoints.stream()
                .filter(
                    oldSelectedEp -> !newSelectedEndpoints.contains(oldSelectedEp))
                .map(this::getEndpoint)
                .filter(Objects::nonNull)
                .forEach(AbstractEndpoint::decrementSelectedCount);

            // Any endpoints in the newSelectedEndpoints list which AREN'T
            // in the oldSelectedEndpoints list should have their count incremented
            newSelectedEndpoints.stream()
                .filter(
                    newSelectedEp -> !oldSelectedEndpoints.contains(newSelectedEp))
                .map(this::getEndpoint)
                .filter(Objects::nonNull)
                .forEach(AbstractEndpoint::incrementSelectedCount);
        }
    }

    /**
     * Notifies this conference that one of it's endpoints has expired.
     *
     * @param endpoint the <tt>Endpoint</tt> which expired.
     */
    void endpointExpired(AbstractEndpoint endpoint)
    {
        final AbstractEndpoint removedEndpoint;
        String id = endpoint.getID();
        removedEndpoint = endpoints.remove(id);
        if (removedEndpoint != null)
        {
            updateEndpointsCache();
        }

        if (tentacle != null)
        {
            tentacle.endpointExpired(id);
        }

        if (removedEndpoint != null)
        {
            final EventAdmin eventAdmin = getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.sendEvent(
                    EventFactory.endpointExpired(removedEndpoint));
            }
            endpointsChanged();
        }
    }

    /**
     * Adds a specific {@link AbstractEndpoint} instance to the list of
     * endpoints in this conference.
     * @param endpoint the endpoint to add.
     */
    public void addEndpoint(AbstractEndpoint endpoint)
    {
        if (endpoint.getConference() != this)
        {
            throw new IllegalArgumentException("Endpoint belong to other " +
                "conference = " + endpoint.getConference());
        }

        final AbstractEndpoint replacedEndpoint;
        replacedEndpoint = endpoints.put(endpoint.getID(), endpoint);
        updateEndpointsCache();

        endpointsChanged();

        if (replacedEndpoint != null)
        {
            logger.info("Endpoint with id " + endpoint.getID() + ": " +
                replacedEndpoint + " has been replaced by new " +
                "endpoint with same id: " + endpoint);
        }
    }

    /**
     * Notifies this {@link Conference} that one of its {@link Endpoint}s
     * transport channel has become available.
     *
     * @param endpoint the {@link Endpoint} whose transport channel has become
     * available.
     */
    @Override
    public void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint)
    {
        EventAdmin eventAdmin = getEventAdmin();

        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                EventFactory.endpointMessageTransportReady(endpoint));
        }

        if (!isExpired())
        {
            AbstractEndpoint dominantSpeaker
                    = speechActivity.getDominantEndpoint();

            if (dominantSpeaker != null)
            {
                try
                {
                    endpoint.sendMessage(
                            createDominantSpeakerEndpointChangeEvent(
                                dominantSpeaker.getID()));
                }
                catch (IOException e)
                {
                    logger.error(
                            "Failed to send dominant speaker update"
                                + " on data channel to " + endpoint.getID(),
                            e);
                }
            }
        }
    }

    /**
     * Sets the JID of the last known focus.
     *
     * @param jid the JID of the last known focus.
     */
    public void setLastKnownFocus(Jid jid)
    {
        lastKnownFocus = jid;
    }

    /**
     * Notifies this instance that the list of ordered endpoints has changed
     */
    void speechActivityEndpointsChanged(List<String> newEndpointIds)
    {
        endpointsCache.forEach(
                e ->  e.speechActivityEndpointsChanged(newEndpointIds));
    }

    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt> to the current system time.
     */
    public void touch()
    {
        long now = System.currentTimeMillis();

        synchronized (this)
        {
            if (getLastActivityTime() < now)
            {
                lastActivityTime = now;
            }
        }
    }

    /**
     * Gets the conference name.
     *
     * @return the conference name
     */
    public Localpart getName()
    {
        return name;
    }

    /**
     * Returns the <tt>EventAdmin</tt> instance used by this <tt>Conference</tt>
     * and all instances (of {@code Content}, {@code Channel}, etc.) created by
     * it.
     *
     * @return the <tt>EventAdmin</tt> instance used by this <tt>Conference</tt>
     */
    public EventAdmin getEventAdmin()
    {
        return eventAdmin;
    }

    /**
     * @return the {@link Logger} used by this instance.
     */
    public Logger getLogger()
    {
        return logger;
    }

    /**
     * @return the global ID of the conference, or {@code null} if none has been
     * set.
     */
    public String getGid()
    {
        return gid;
    }

    /**
     * {@inheritDoc}
     * </p>
     * @return {@code true} if this {@link Conference} is ready to be expired.
     */
    @Override
    public boolean shouldExpire()
    {
        // Allow a conference to have no endpoints in the first 20 seconds.
        return getEndpointCount() == 0
                && (System.currentTimeMillis() - creationTime > 20000);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void safeExpire()
    {
        expireableImpl.safeExpire();
    }

    /**
     * @return this {@link Conference}'s Colibri shim.
     */
    public ConferenceShim getShim()
    {
        return shim;
    }

    /**
     * Broadcasts the packet to all endpoints and tentacles that want it.
     *
     * @param packetInfo the packet
     */
    private void sendOut(PacketInfo packetInfo)
    {
        String sourceEndpointId = packetInfo.getEndpointId();
        // We want to avoid calling 'clone' for the last receiver of this packet
        // since it's unnecessary.  To do so, we'll wait before we clone and send
        // to an interested handler until after we've determined another handler
        // is also interested in the packet.  We'll give the last handler the
        // original packet (without cloning).
        PotentialPacketHandler prevHandler = null;
        for (Endpoint endpoint : endpointsCache)
        {
            if (endpoint.getID().equals(sourceEndpointId))
            {
                continue;
            }

            if (endpoint.wants(packetInfo))
            {
                if (prevHandler != null)
                {
                    prevHandler.send(packetInfo.clone());
                }
                prevHandler = endpoint;
            }
        }
        if (tentacle != null && tentacle.wants(packetInfo))
        {
            if (prevHandler != null)
            {
                prevHandler.send(packetInfo.clone());
            }
            prevHandler = tentacle;
        }

        if (prevHandler != null)
        {
            prevHandler.send(packetInfo);
        }
        else
        {
            // No one wanted the packet, so the buffer is now free!
            ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
    }

    /**
     * Gets the audio level listener.
     */
    public AudioLevelListener getAudioLevelListener()
    {
        return audioLevelListener;
    }

    /**
     * @return The {@link ConfOctoTransport} for this conference.
     */
    public ConfOctoTransport getTentacle()
    {
        if (tentacle == null)
        {
            tentacle = new ConfOctoTransport(this);
            tentacle.addPropertyChangeListener(propertyChangeListener);
        }
        return tentacle;
    }

    public boolean isOctoEnabled()
    {
        return tentacle != null;
    }

    /**
     * Handles an RTP/RTCP packet coming from a specific endpoint.
     * @param packetInfo
     */
    public void handleIncomingPacket(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        if (packet instanceof RtpPacket)
        {
            // This is identical to the default 'else' below, but it defined
            // because the vast majority of packet will follow this path.
            sendOut(packetInfo);
        }
        else if (packet instanceof RtcpFbPliPacket
                || packet instanceof RtcpFbFirPacket)
        {
            long mediaSsrc = (packet instanceof RtcpFbPliPacket)
                ? ((RtcpFbPliPacket) packet).getMediaSourceSsrc()
                : ((RtcpFbFirPacket) packet).getMediaSenderSsrc();

            // XXX we could make this faster with a map
            AbstractEndpoint targetEndpoint
                = findEndpointByReceiveSSRC(mediaSsrc);

            PotentialPacketHandler pph = null;
            if (targetEndpoint instanceof Endpoint)
            {
                pph = (Endpoint) targetEndpoint;
            }
            else if (targetEndpoint instanceof OctoEndpoint)
            {
                pph = tentacle;
            }

            // This is not a redundant check. With Octo and 3 or more bridges,
            // some PLI or FIR will come from Octo but the target endpoint will
            // also be Octo. We need to filter these out.
            if (pph == null)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Dropping FIR/PLI for media ssrc " + mediaSsrc);
                }
            }
            else if (pph.wants(packetInfo))
            {
                pph.send(packetInfo);
            }
        }
        else
        {
            sendOut(packetInfo);
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     *
     * @param full if specified the result will include more details and will
     * include the debug state of the endpoint(s). Otherwise just the IDs and
     * names of the conference and endpoints are included.
     * @param endpointId the ID of the endpoint to include. If set to
     * {@code null}, all endpoints will be included.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState(boolean full, String endpointId)
    {
        JSONObject debugState = new JSONObject();
        debugState.put("id", id);
        debugState.put("name", name == null ? null : name.toString());

        if (full)
        {
            debugState.put("gid", gid);
            debugState.put("expired", expired);
            debugState.put("creationTime", creationTime);
            debugState.put("lastActivity", lastActivityTime);
            debugState.put("speechActivity", speechActivity.getDebugState());
            debugState.put("includeInStatistics", includeInStatistics);
            debugState.put("statistics", statistics.getJson());
            //debugState.put("encodingsManager", encodingsManager.getDebugState());
            ConfOctoTransport tentacle = this.tentacle;
            debugState.put(
                    "tentacle",
                    tentacle == null ? null : tentacle.getDebugState());
        }

        JSONObject endpoints = new JSONObject();
        debugState.put("endpoints", endpoints);
        for (Endpoint e : endpointsCache)
        {
            if (endpointId == null || endpointId.equals(e.getID()))
            {
                endpoints.put(e.getID(),
                        full ? e.getDebugState() : e.getStatsId());
            }
        }
        return debugState;
    }

    /**
     * Whether this looks like a conference in which the two endpoints are
     * using a peer-to-peer connection (i.e. none of them are sending audio
     * or video).
     * This has false positives when e.g. an endpoint doesn't support p2p
     * (firefox) and both are audio/video muted.
     */
    public boolean isP2p()
    {
        return isInactive() && getEndpointCount() == 2;
    }

    /**
     * Whether the conference is inactive, in the sense that none of its
     * endpoints are sending audio or video.
     */
    public boolean isInactive()
    {
        return getEndpoints().stream().noneMatch(e -> e.isSendingAudio() || e.isSendingVideo());
    }

    /**
     * Holds conference statistics.
     */
    public static class Statistics
    {
        /**
         * The total number of bytes received in RTP packets in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        AtomicLong totalBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        AtomicLong totalBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        AtomicLong totalPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets received in channels in this
         * conference. Note that this is only updated when channels expire.
         */
        AtomicLong totalPacketsSent = new AtomicLong();

        /**
         * Whether at least one endpoint in this conference failed ICE.
         */
        boolean hasIceFailedEndpoint = false;

        /**
         * Whether at least one endpoint in this conference completed ICE
         * successfully.
         */
        boolean hasIceSucceededEndpoint = false;

        /**
         * Gets a snapshot of this object's state as JSON.
         */
        @SuppressWarnings("unchecked")
        private JSONObject getJson()
        {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("total_bytes_received", totalBytesReceived.get());
            jsonObject.put("total_bytes_sent", totalBytesSent.get());
            jsonObject.put("total_packets_received", totalPacketsReceived.get());
            jsonObject.put("total_packets_sent", totalPacketsSent.get());
            jsonObject.put("has_failed_endpoint", hasIceFailedEndpoint);
            jsonObject.put("has_succeeded_endpoint", hasIceSucceededEndpoint);
            return jsonObject;
        }
    }

    /**
     * This is a no-op diagnostic context (one that will record nothing) meant
     * to disable logging of time-series for health checks.
     */
    static class NoOpDiagnosticContext
        extends DiagnosticContext
    {
        @Override
        public TimeSeriesPoint makeTimeSeriesPoint(String timeSeriesName, long tsMs)
        {
            return new NoOpTimeSeriesPoint();
        }

        @Override
        public Object put(@NotNull String key, @NotNull Object value)
        {
            return null;
        }
    }

    static class NoOpTimeSeriesPoint
        extends DiagnosticContext.TimeSeriesPoint
    {
        public NoOpTimeSeriesPoint()
        {
            this(Collections.emptyMap());
        }

        public NoOpTimeSeriesPoint(Map<String, Object> m)
        {
            super(m);
        }

        @Override
        public Object put(String key, Object value)
        {
            return null;
        }
    }
}
