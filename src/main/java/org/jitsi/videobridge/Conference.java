/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
import org.jitsi.eventadmin.*;
import org.jitsi.nlj.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging.Logger;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.stream.*;

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
     implements PropertyChangeListener, Expireable
{
    /**
     * The {@link Logger} used by the {@link Conference} class to print debug
     * information. Note that {@link Conference} instances should use {@link
     * #logger} instead.
     */
    private static final Logger classLogger = Logger.getLogger(Conference.class);

    /**
     * The endpoints participating in this {@link Conference}. Although it's a
     * {@link ConcurrentHashMap}, writing to it must be protected by
     * synchronizing on the map itself, because it must be kept in sync with
     * {@link #endpointsCache}.
     */
    private final Map<String, AbstractEndpoint> endpoints
            = new ConcurrentHashMap<>();

    /**
     * A read-only cache of the endpoints in this conference. Note that it
     * contains only the {@link Endpoint} instances (and not Octo endpoints).
     * This is because the cache was introduced for performance reasons only
     * (we iterate over it for each RTP packet) and the Octo endpoints are not
     * needed.
     */
    private List<Endpoint> endpointsCache = Collections.EMPTY_LIST;

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
     * The string used to identify this conference for the purposes of logging.
     */
    private final String logPrefix;

    /**
     * The world readable name of this instance if any.
     */
    private Localpart name;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Conference</tt>. In the time interval between the last activity and
     * now, this <tt>Conference</tt> is considered inactive.
     */
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
    private final Logger logger = Logger.getLogger(classLogger, null);

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
    private OctoTentacle tentacle;

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
        this.shim = new ConferenceShim(this);
        this.id = Objects.requireNonNull(id, "id");
        this.gid = gid;
        this.focus = focus;
        this.eventAdmin = enableLogging ? videobridge.getEventAdmin() : null;
        this.includeInStatistics = enableLogging;
        this.name = name;
        this.logPrefix = "[id=" + id + " gid=" + gid + " name=" + name + "] ";

        if (!enableLogging)
        {
            logger.setLevel(Level.WARNING);
        }

        lastKnownFocus = focus;

        speechActivity = new ConferenceSpeechActivity(this);
        audioLevelListener
            = (sourceSsrc, level)
                -> speechActivity.levelChanged(sourceSsrc, (int) level);

        expireableImpl = new ExpireableImpl(logPrefix, this::expire);

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
     * Appends the conference name and the conference creation time to the
     * {@link DiagnosticContext} that is passed as a parameter.
     *
     * @param diagnosticContext the {@link DiagnosticContext} to append the
     * diagnostic information to.
     */
    public void appendDiagnosticInformation(DiagnosticContext diagnosticContext)
    {
        Objects.requireNonNull(diagnosticContext);

        if (name != null)
        {
            diagnosticContext.put("conf_name", name.toString());
        }

        diagnosticContext.put("conf_creation_time_ms", creationTime);
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
            logger.info(getLogPrefix() + "ds_change ds_id=" + id);
        }

        if (dominantSpeaker != null)
        {
            broadcastMessage(
                    createDominantSpeakerEndpointChangeEvent(
                        dominantSpeaker.getID()));
        }
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

        logger.info(logPrefix + "Expiring.");
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
                logger.debug(logPrefix + "Expiring endpoints.");
            }
            getEndpoints().forEach(AbstractEndpoint::expire);
            speechActivity.expire();

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
            sb.append(logPrefix)
                .append("duration=").append(durationSeconds)
                .append(",conf_completed=")
                    .append(videobridgeStatistics.totalConferencesCompleted)
                .append(",has_failed=").append(hasFailed)
                .append(",has_partially_failed=").append(hasPartiallyFailed);
            logger.info(Logger.Category.STATISTICS, sb.toString());
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
    public AbstractEndpoint getEndpoint(String id)
    {
        return getEndpoint(id, /* create */ false);
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID. If an <tt>Endpoint</tt> participating in
     * this <tt>Conference</tt> with the specified <tt>id</tt> does not exist at
     * the time the method is invoked, the method optionally initializes a new
     * <tt>Endpoint</tt> instance with the specified <tt>id</tt> and adds it to
     * the list of <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt> or <tt>null</tt> if there is no such
     * <tt>Endpoint</tt> and <tt>create</tt> equals <tt>false</tt>
     */
    private AbstractEndpoint getEndpoint(String id, boolean create)
    {
        AbstractEndpoint endpoint;
        endpoint = endpoints.get(id);

        if (endpoint == null && create)
        {
            synchronized (endpoints)
            {
                endpoint = new Endpoint(id, this);
                // The propertyChangeListener will weakly reference this
                // Conference and will unregister itself from the endpoint
                // sooner or later.
                endpoint.addPropertyChangeListener(propertyChangeListener);
                endpoints.put(id, endpoint);

                updateEndpointsCache();
            }

            EventAdmin eventAdmin = getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.sendEvent(
                        EventFactory.endpointCreated(endpoint));
            }

            endpointsChanged();
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
        synchronized (endpoints)
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
     * Returns the number of <tt>Endpoint</tt>s in this <tt>Conference</tt>.
     *
     * @return the number of <tt>Endpoint</tt>s in this <tt>Conference</tt>.
     */
    public int getEndpointCount()
    {
        return endpoints.size();
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
     * has a specific identifier/ID. If an <tt>Endpoint</tt> participating in
     * this <tt>Conference</tt> with the specified <tt>id</tt> does not exist at
     * the time the method is invoked, the method initializes a new
     * <tt>Endpoint</tt> instance with the specified <tt>id</tt> and adds it to
     * the list of <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt>
     */
    public Endpoint getOrCreateLocalEndpoint(String id)
    {
        AbstractEndpoint endpoint = getEndpoint(id, /* create */ true);
        if (!(endpoint instanceof Endpoint))
        {
            throw new IllegalStateException("Endpoint with id " + id +
                    " should be an Endpoint instance");
        }

        return (Endpoint) endpoint;
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
        // Conference starts with expired equal to false and the only assignment
        // to expired is to set it to true so there is no need to synchronize
        // the reading of expired.
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
        if (endpoints.remove(endpoint.getID()) != null)
        {
            updateEndpointsCache();
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
        synchronized (endpoints)
        {
            endpoints.put(endpoint.getID(), endpoint);
            updateEndpointsCache();
        }

        endpointsChanged();
    }

    /**
     * Notifies this {@link Conference} that one of its {@link Endpoint}s
     * transport channel has become available.
     *
     * @param endpoint the {@link Endpoint} whose transport channel has become
     * available.
     */
    void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint)
    {
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
    void speechActivityEndpointsChanged()
    {
        List<String> endpoints = speechActivity.getEndpointIds();
        endpointsCache.forEach(
                e ->  e.speechActivityEndpointsChanged(endpoints));
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
     * @return a string which identifies this {@link Conference} for the
     * purposes of logging. The string is a comma-separated list of "key=value"
     * pairs.
     */
    public String getLogPrefix()
    {
        return logPrefix;
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
        return getEndpointCount() == 0;
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
    }

    /**
     * Gets the audio level listener.
     */
    public AudioLevelListener getAudioLevelListener()
    {
        return audioLevelListener;
    }

    /**
     * @return The {@link OctoTentacle} for this conference.
     */
    public OctoTentacle getTentacle()
    {
        if (tentacle == null)
        {
            tentacle = new OctoTentacle(this);
            tentacle.addPropertyChangeListener(propertyChangeListener);
        }
        return tentacle;
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
            RtcpFbPacket rtcpFbPacket = (RtcpFbPacket) packet;

            // XXX we could make this faster with a map
            AbstractEndpoint targetEndpoint
                = findEndpointByReceiveSSRC(rtcpFbPacket.getMediaSourceSsrc());

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
                    logger.debug("Dropping FIR/PLI for media ssrc "
                        + rtcpFbPacket.getMediaSourceSsrc());
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
            OctoTentacle tentacle = this.tentacle;
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
     * Holds conference statistics.
     */
    public class Statistics
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
}
