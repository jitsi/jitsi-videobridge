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

import java.beans.*;
import java.io.*;
import java.lang.reflect.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ.Recording.*;
import net.java.sip.communicator.util.*;

import org.jetbrains.annotations.*;
import org.jitsi.eventadmin.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.utils.event.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.octo.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

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
     * The name of the <tt>Conference</tt> property <tt>endpoints</tt> which
     * lists the <tt>Endpoint</tt>s participating in/contributing to the
     * <tt>Conference</tt>.
     */
    public static final String ENDPOINTS_PROPERTY_NAME
        = Conference.class.getName() + ".endpoints";

    /**
     * The {@link Logger} used by the {@link Conference} class to print debug
     * information. Note that {@link Conference} instances should use {@link
     * #logger} instead.
     */
    private static final Logger classLogger = Logger.getLogger(Conference.class);

    /**
     * @return a string which identifies a specific {@link Conference} for the
     * purposes of logging. The string is a comma-separated list of "key=value"
     * pairs.
     * @param conference The conference for which to return a string.
     */
    public static String getLoggingId(Conference conference)
    {
        return
            (conference == null ? "conf_id=null" : conference.getLoggingId());
    }

    /**
     * The <tt>Content</tt>s of this <tt>Conference</tt>.
     */
    private final List<Content> contents = new LinkedList<>();

    /**
     * An instance used to save information about the endpoints of this
     * <tt>Conference</tt>, when media recording is enabled.
     */
    private EndpointRecorder endpointRecorder = null;

    /**
     * The <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     */
    private final List<AbstractEndpoint> endpoints = new LinkedList<>();

    /**
     * The {@link OctoEndpoints} instance, if Octo is enabled for this
     * conference, which manages the foreign {@link AbstractEndpoint}s of the
     * conference (i.e. endpoints connected to remote jitsi-videobridge
     * instances).
     * If/while Octo is not enabled for the conference, this is {@code null}.
     */
    private OctoEndpoints octoEndpoints = null;

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
    private final String loggingId;

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
     * The <tt>RecorderEventHandler</tt> which is used to handle recording
     * events for this <tt>Conference</tt>.
     */
    private RecorderEventHandlerImpl recorderEventHandler = null;

    /**
     * Whether media recording is currently enabled for this <tt>Conference</tt>.
     */
    private boolean recording = false;

    /**
     * The directory into which files associated with media recordings
     * for this <tt>Conference</tt> will be stored.
     */
    private String recordingDirectory = null;

    /**
     * The path to the directory into which files associated with media
     * recordings for this <tt>Conference</tt> will be stored.
     */
    private String recordingPath = null;

    /**
     * The speech activity (representation) of the <tt>Endpoint</tt>s of this
     * <tt>Conference</tt>.
     */
    private final ConferenceSpeechActivity speechActivity;

    /**
     * Maps an ID of a channel-bundle to the <tt>TransportManager</tt> instance
     * responsible for its transport.
     */
    private final Map<String, IceUdpTransportManager> transportManagers
        = new HashMap<>();

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
        this.id = Objects.requireNonNull(id, "id");
        this.gid = gid;
        this.loggingId = "conf_id=" + id;
        this.focus = focus;
        this.eventAdmin = enableLogging ? videobridge.getEventAdmin() : null;
        this.includeInStatistics = enableLogging;
        this.name = name;

        if (!enableLogging)
        {
            logger.setLevel(Level.WARNING);
        }

        lastKnownFocus = focus;

        speechActivity = new ConferenceSpeechActivity(this);
        speechActivity.addPropertyChangeListener(propertyChangeListener);

        expireableImpl = new ExpireableImpl(loggingId, this::expire);

        if (enableLogging)
        {
            eventAdmin.sendEvent(EventFactory.conferenceCreated(this));
            Videobridge.Statistics videobridgeStatistics
                = videobridge.getStatistics();
            videobridgeStatistics.totalConferencesCreated.incrementAndGet();
        }

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

        OctoEndpoints octoEndpoints = this.octoEndpoints;
        if (sendToOcto && octoEndpoints != null)
        {
            octoEndpoints.sendMessage(msg);
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
     * Checks whether <tt>path</tt> is a valid directory for recording (creates
     * it if necessary).
     * @param path the path to the directory to check.
     * @return <tt>true</tt> if the directory <tt>path</tt> can be used for
     * media recording, <tt>false</tt> otherwise.
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    private boolean checkRecordingDirectory(String path)
    {
        if (StringUtils.isNullOrEmpty(path))
        {
            return false;
        }

        File dir = new File(path);

        if (!dir.exists())
        {
            dir.mkdir();
            if (!dir.exists())
            {
                return false;
            }
        }
        if (!dir.isDirectory() || !dir.canWrite())
        {
            return false;
        }

        return true;
    }

    /**
     * Closes given {@link #transportManagers} of this <tt>Conference</tt>
     * and removes corresponding channel bundle.
     */
    void closeTransportManager(TransportManager transportManager)
    {
        synchronized (transportManagers)
        {
            transportManagers.values().removeIf(tm -> tm == transportManager);

            // Close manager
            try
            {
                transportManager.close();
            }
            catch (Throwable t)
            {
                logger.warn(
                        "Failed to close an IceUdpTransportManager of"
                                + " conference " + getID() + "!",
                        t);
                // The whole point of explicitly closing the
                // transportManagers of this Conference is to prevent memory
                // leaks. Hence, it does not make sense to possibly leave
                // TransportManagers open because a TransportManager has
                // failed to close.
                if (t instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                }
                else if (t instanceof ThreadDeath)
                {
                    throw (ThreadDeath) t;
                }
            }
        }
    }

    /**
     * Closes the {@link #transportManagers} of this <tt>Conference</tt>.
     */
    private void closeTransportManagers()
    {
        synchronized (transportManagers)
        {
            Collection<TransportManager> transportManagers
                = new LinkedList<>(this.transportManagers.values());
            transportManagers.forEach(this::closeTransportManager);
            this.transportManagers.clear();
        }
    }


    /**
     * Adds the channel-bundles of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.ChannelBundle</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     * @param channelBundleIdsToDescribe a filter of the IDs of channel bundles
     * to describe. Channel bundles with IDs not on the list will not be
     * described. If {@code channelBundleIdsToDescribe} is {@code null}, all
     * channel bundles will be described.
     */
    void describeChannelBundles(
        ColibriConferenceIQ iq, Set<String> channelBundleIdsToDescribe)
    {
        synchronized (transportManagers)
        {
            for (Map.Entry<String, IceUdpTransportManager> entry
                    : transportManagers.entrySet())
            {
                String id = entry.getKey();
                if (channelBundleIdsToDescribe == null
                    || channelBundleIdsToDescribe.contains(id))
                {
                    ColibriConferenceIQ.ChannelBundle responseBundleIQ
                        = new ColibriConferenceIQ.ChannelBundle(id);

                    entry.getValue().describe(responseBundleIQ);
                    iq.addChannelBundle(responseBundleIQ);
                }
            }
        }
    }

    /**
     * Adds the endpoint of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.Endpoint</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     */
    void describeEndpoints(ColibriConferenceIQ iq)
    {
        getEndpoints().forEach(
            en -> iq.addEndpoint(
                new ColibriConferenceIQ.Endpoint(
                    en.getID(), en.getStatsId(), en.getDisplayName())));
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

        if (isRecording())
        {
            ColibriConferenceIQ.Recording recordingIQ
                = new ColibriConferenceIQ.Recording(State.ON.toString());
            recordingIQ.setDirectory(getRecordingDirectory());
            iq.setRecording(recordingIQ);
        }
        for (Content content : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(content.getName());

            for (Channel channel : content.getChannels())
            {
                if (channel instanceof SctpConnection)
                {
                    ColibriConferenceIQ.SctpConnection sctpConnectionIQ
                        = new ColibriConferenceIQ.SctpConnection();

                    channel.describe(sctpConnectionIQ);
                    contentIQ.addSctpConnection(sctpConnectionIQ);
                }
                else
                {
                    ColibriConferenceIQ.Channel channelIQ
                        = new ColibriConferenceIQ.Channel();

                    channel.describe(channelIQ);
                    contentIQ.addChannel(channelIQ);
                }
            }
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
    private void dominantSpeakerChanged()
    {
        AbstractEndpoint dominantSpeaker = speechActivity.getDominantEndpoint();

        if (logger.isInfoEnabled())
        {
            String id
                = dominantSpeaker == null ? "null" : dominantSpeaker.getID();
            logger.info(Logger.Category.STATISTICS,
                        "ds_change," + getLoggingId()
                        + " ds_id=" + id);
        }

        if (dominantSpeaker != null)
        {
            broadcastMessage(
                    createDominantSpeakerEndpointChangeEvent(
                        dominantSpeaker.getID()));

            if (isRecording() && (recorderEventHandler != null))
            {
                recorderEventHandler.dominantSpeakerChanged(dominantSpeaker);
            }
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

        EventAdmin eventAdmin = getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.conferenceExpired(this));
        }

        setRecording(false);
        if (recorderEventHandler != null)
        {
            recorderEventHandler.close();
            recorderEventHandler = null;
        }

        Videobridge videobridge = getVideobridge();

        try
        {
            videobridge.expireConference(this);
        }
        finally
        {
            // Expire the Contents of this Conference.
            for (Content content : getContents())
            {
                try
                {
                    content.expire();
                }
                catch (Throwable t)
                {
                    logger.warn(
                            "Failed to expire content " + content.getName()
                                + " of conference " + getID() + "!",
                            t);
                    if (t instanceof InterruptedException)
                    {
                        Thread.currentThread().interrupt();
                    }
                    else if (t instanceof ThreadDeath)
                    {
                        throw (ThreadDeath) t;
                    }
                }
            }

            // Close the transportManagers of this Conference. Normally, there
            // will be no TransportManager left to close at this point because
            // all Channels have expired and the last Channel to be removed from
            // a TransportManager closes the TransportManager. However, a
            // Channel may have expired before it has learned of its
            // TransportManager and then the TransportManager will not close.
            closeTransportManagers();

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
        videobridgeStatistics.totalUdpTransportManagers.addAndGet(
            statistics.totalUdpTransportManagers.get());
        videobridgeStatistics.totalTcpTransportManagers.addAndGet(
            statistics.totalTcpTransportManagers.get());

        videobridgeStatistics.totalNoPayloadChannels.addAndGet(
            statistics.totalNoPayloadChannels.get());
        videobridgeStatistics.totalNoTransportChannels.addAndGet(
            statistics.totalNoTransportChannels.get());

        videobridgeStatistics.totalChannels.addAndGet(
            statistics.totalChannels.get());

        videobridgeStatistics.totalBytesReceived.addAndGet(
            statistics.totalBytesReceived.get());
        videobridgeStatistics.totalBytesSent.addAndGet(
            statistics.totalBytesSent.get());
        videobridgeStatistics.totalPacketsReceived.addAndGet(
            statistics.totalPacketsReceived.get());
        videobridgeStatistics.totalPacketsSent.addAndGet(
            statistics.totalPacketsSent.get());
        videobridgeStatistics.totalBytesReceivedOcto.addAndGet(
            statistics.totalBytesReceivedOcto.get());
        videobridgeStatistics.totalBytesSentOcto.addAndGet(
            statistics.totalBytesSentOcto.get());
        videobridgeStatistics.totalPacketsReceivedOcto.addAndGet(
            statistics.totalPacketsReceivedOcto.get());
        videobridgeStatistics.totalPacketsSentOcto.addAndGet(
            statistics.totalPacketsSentOcto.get());

        boolean hasFailed
            = statistics.totalNoPayloadChannels.get()
                >= statistics.totalChannels.get();
        boolean hasPartiallyFailed
            = statistics.totalNoPayloadChannels.get() != 0;

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

            int[] metrics = videobridge.getConferenceChannelAndStreamCount();

            StringBuilder sb = new StringBuilder("expire_conf,");
            sb.append(getLoggingId())
                .append(" duration=").append(durationSeconds)
                .append(",conf_count=").append(metrics[0])
                .append(",ch_count=").append(metrics[1])
                .append(",v_streams=").append(metrics[2])
                .append(",conf_completed=")
                    .append(videobridgeStatistics.totalConferencesCompleted)
                .append(",no_payload_ch=")
                    .append(videobridgeStatistics.totalNoPayloadChannels)
                .append(",no_transport_ch=")
                    .append(videobridgeStatistics.totalNoTransportChannels)
                .append(",total_ch=")
                    .append(videobridgeStatistics.totalChannels)
                .append(",has_failed=").append(hasFailed)
                .append(",has_partially_failed=").append(hasPartiallyFailed);
            logger.info(Logger.Category.STATISTICS, sb.toString());
        }
    }

    /**
     * Expires a specific <tt>Content</tt> of this <tt>Conference</tt> (i.e. if
     * the specified <tt>content</tt> is not in the list of <tt>Content</tt>s of
     * this <tt>Conference</tt>, does nothing).
     *
     * @param content the <tt>Content</tt> to be expired by this
     * <tt>Conference</tt>
     */
    public void expireContent(Content content)
    {
        boolean expireContent;

        synchronized (contents)
        {
            expireContent = contents.contains(content);
            if (expireContent)
            {
                contents.remove(content);
            }
        }
        if (expireContent)
        {
            content.expire();
        }
    }

    /**
     * Finds a <tt>Channel</tt> of this <tt>Conference</tt> which receives a
     * specific SSRC and is with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of a received RTP stream whose receiving
     * <tt>Channel</tt> in this <tt>Conference</tt> is to be found
     * @param mediaType the <tt>MediaType</tt> of the <tt>Channel</tt> to be
     * found
     * @return the <tt>Channel</tt> in this <tt>Conference</tt> which receives
     * the specified <tt>ssrc</tt> and is with the specified <tt>mediaType</tt>;
     * otherwise, <tt>null</tt>
     */
    public Channel findChannelByReceiveSSRC(
            long receiveSSRC,
            MediaType mediaType)
    {
        for (Content content : getContents())
        {
            if (mediaType.equals(content.getMediaType()))
            {
                Channel channel = content.findChannelByReceiveSSRC(receiveSSRC);

                if (channel != null)
                {
                    return channel;
                }
            }
        }
        return null;
    }

    /**
     * Finds an <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with a specific SSRC and with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of an RTP stream received by this
     * <tt>Conference</tt> whose sending <tt>Endpoint</tt> is to be found
     * @param mediaType the <tt>MediaType</tt> of the RTP stream identified by
     * the specified <tt>ssrc</tt>
     * @return <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with the specified <tt>ssrc</tt> and with the specified
     * <tt>mediaType</tt>; otherwise, <tt>null</tt>
     */
    AbstractEndpoint findEndpointByReceiveSSRC(
        long receiveSSRC, MediaType mediaType)
    {
        Channel channel = findChannelByReceiveSSRC(receiveSSRC, mediaType);

        return (channel == null) ? null : channel.getEndpoint(receiveSSRC);
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
     * Gets the <tt>Content</tt>s of this <tt>Conference</tt>.
     *
     * @return the <tt>Content</tt>s of this <tt>Conference</tt>
     */
    public Content[] getContents()
    {
        synchronized (contents)
        {
            return contents.toArray(new Content[contents.size()]);
        }
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
        boolean changed;

        synchronized (endpoints)
        {
            changed = endpoints.removeIf(AbstractEndpoint::isExpired);

            endpoint
                = endpoints.stream()
                        .filter(e -> e.getID().equals(id))
                        .findFirst().orElse(null);

            if (create && endpoint == null)
            {
                endpoint = new Endpoint(id, this);
                // The propertyChangeListener will weakly reference this
                // Conference and will unregister itself from the endpoint
                // sooner or later.
                endpoint.addPropertyChangeListener(propertyChangeListener);
                endpoints.add(endpoint);
                changed = true;

                EventAdmin eventAdmin = getEventAdmin();
                if (eventAdmin != null)
                {
                    eventAdmin.sendEvent(
                            EventFactory.endpointCreated(endpoint));
                }
            }
        }

        if (changed)
        {
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        }

        return endpoint;
    }

    /**
     * Returns the number of <tt>Endpoint</tt>s in this <tt>Conference</tt>.
     *
     * @return the number of <tt>Endpoint</tt>s in this <tt>Conference</tt>.
     */
    public int getEndpointCount()
    {
        return getEndpoints().size();
    }

    /**
     * Returns the <tt>EndpointRecorder</tt> instance used to save the
     * endpoints information for this <tt>Conference</tt>. Creates an instance
     * if none exists.
     * @return the <tt>EndpointRecorder</tt> instance used to save the
     * endpoints information for this <tt>Conference</tt>.
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    private EndpointRecorder getEndpointRecorder()
    {
        if (endpointRecorder == null)
        {
            try
            {
                endpointRecorder
                    = new EndpointRecorder(
                            getRecordingPath() + "/endpoints.json");
            }
            catch (IOException ioe)
            {
                logger.warn("Could not create EndpointRecorder. " + ioe);
            }
        }
        return endpointRecorder;
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
        boolean changed;
        List<AbstractEndpoint> copy;

        synchronized (this.endpoints)
        {
            changed = this.endpoints.removeIf(AbstractEndpoint::isExpired);
            copy = new ArrayList<>(this.endpoints);
        }

        if (changed)
        {
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        }

        return copy;
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
     * Returns a <tt>MediaService</tt> implementation (if any).
     *
     * @return a <tt>MediaService</tt> implementation (if any)
     */
    MediaService getMediaService()
    {
        MediaService mediaService
            = ServiceUtils.getService(getBundleContext(), MediaService.class);

        // TODO For an unknown reason, ServiceUtils2.getService fails to
        // retrieve the MediaService implementation. In the form of a temporary
        // workaround, get it through LibJitsi.
        if (mediaService == null)
        {
            mediaService = LibJitsi.getMediaService();
        }

        return mediaService;
    }

    /**
     * Gets a <tt>Content</tt> of this <tt>Conference</tt> which has a specific
     * name. If a <tt>Content</tt> of this <tt>Conference</tt> with the
     * specified <tt>name</tt> does not exist at the time the method is invoked,
     * the method initializes a new <tt>Content</tt> instance with the specified
     * <tt>name</tt> and adds it to the list of <tt>Content</tt>s of this
     * <tt>Conference</tt>.
     *
     * @param name the name of the <tt>Content</tt> which is to be returned
     * @return a <tt>Content</tt> of this <tt>Conference</tt> which has the
     * specified <tt>name</tt>
     */
    public Content getOrCreateContent(String name)
    {
        Content content;

        synchronized (contents)
        {
            content
                = contents.stream()
                    .filter(c -> c.getName().equals(name))
                    .findFirst().orElse(null);
            if (content != null)
            {
                content.touch(); // It seems the content is still active.
                return content;
            }

            content = new Content(this, name);
            if (isRecording())
            {
                content.setRecording(true, getRecordingPath());
            }
            contents.add(content);
        }

        if (logger.isInfoEnabled())
        {
            /*
             * The method Videobridge.getChannelCount() should better be
             * executed outside synchronized blocks in order to reduce the risks
             * of causing deadlocks.
             */
            Videobridge videobridge = getVideobridge();

            logger.info(Logger.Category.STATISTICS,
                        "create_content," + content.getLoggingId()
                            + " " + videobridge.getConferenceCountString());
        }

        return content;
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
    public AbstractEndpoint getOrCreateEndpoint(String id)
    {
        return getEndpoint(id, /* create */ true);
    }

     /**
      * @deprecated remove-with-recording
      */
    @Deprecated
    RecorderEventHandler getRecorderEventHandler()
    {
        if (recorderEventHandler == null)
        {
            Throwable t;

            try
            {
                recorderEventHandler
                    = new RecorderEventHandlerImpl(
                            this,
                            getMediaService().createRecorderEventHandlerJson(
                                    getRecordingPath() + "/metadata.json"));
                t = null;
            }
            catch (IOException | IllegalArgumentException e)
            {
                t = e;
            }
            if (t !=  null)
            {
                logger.warn("Could not create RecorderEventHandler. " + t);
            }
        }
        return recorderEventHandler;
    }

    /**
     * Returns the directory where the recording should be stored
     *
     * @return the directory of the new recording
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    String getRecordingDirectory()
    {
        if (this.recordingDirectory == null)
        {
            SimpleDateFormat dateFormat
                = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss.");
            this.recordingDirectory
                = dateFormat.format(new Date()) + getID() +
                        ((name != null) ? "_" + name : "");
        }

        return this.recordingDirectory;
    }

    /**
     * Returns the path to the directory where the media recording related files
     * should be saved, or <tt>null</tt> if recording is not enabled in the
     * configuration, or a recording path has not been configured.
     *
     * @return the path to the directory where the media recording related files
     * should be saved, or <tt>null</tt> if recording is not enabled in the
     * configuration, or a recording path has not been configured.
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    private String getRecordingPath()
    {
        if (recordingPath == null)
        {
            ConfigurationService cfg
                = getVideobridge().getConfigurationService();

            if (cfg != null)
            {
                boolean recordingIsEnabled
                    = cfg.getBoolean(
                            Videobridge.ENABLE_MEDIA_RECORDING_PNAME,
                            false);

                if (recordingIsEnabled)
                {
                    String path
                        = cfg.getString(
                                Videobridge.MEDIA_RECORDING_PATH_PNAME,
                                null);

                    if (path != null)
                    {
                        this.recordingPath
                            = path + "/" + this.getRecordingDirectory();
                    }
                }
            }
        }
        return recordingPath;
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
     * Returns, the <tt>TransportManager</tt> instance for the channel-bundle
     * with ID <tt>channelBundleId</tt>, or <tt>null</tt> if one doesn't exist.
     *
     * @param channelBundleId the ID of the channel-bundle for which to return
     * the <tt>TransportManager</tt>.
     * @return the <tt>TransportManager</tt> instance for the channel-bundle
     * with ID <tt>channelBundleId</tt>, or <tt>null</tt> if one doesn't exist.
     */
    TransportManager getTransportManager(String channelBundleId)
    {
        // If create is false then initiator parameter will not be used.
        // So here it doesnt matter it is true, or false.
        return getTransportManager(channelBundleId, false, true);
    }

    /**
     * Returns, the <tt>TransportManager</tt> instance for the channel-bundle
     * with ID <tt>channelBundleId</tt>. If no instance exists and
     * <tt>create</tt> is <tt>true</tt>, one will be created.
     *
     * @param channelBundleId the ID of the channel-bundle for which to return
     * the <tt>TransportManager</tt>.
     * @param create whether to create a new instance, if one doesn't exist.
     * @param initiator determines ICE controlling/controlled and DTLS role.
     * @return the <tt>TransportManager</tt> instance for the channel-bundle
     * with ID <tt>channelBundleId</tt>.
     */
    IceUdpTransportManager getTransportManager(
            String channelBundleId,
            boolean create,
            boolean initiator)
    {
        IceUdpTransportManager transportManager;

        synchronized (transportManagers)
        {
            transportManager = transportManagers.get(channelBundleId);
            if (transportManager == null && create && !isExpired())
            {
                try
                {
                    transportManager
                        = new IceUdpTransportManager(
                            this, initiator, 1, channelBundleId);
                }
                catch (IOException ioe)
                {
                    throw new UndeclaredThrowableException(ioe);
                }
                transportManagers.put(channelBundleId, transportManager);

                logger.info(Logger.Category.STATISTICS,
                            "create_ice_tm," + getLoggingId()
                            + " ufrag=" + transportManager.getLocalUfrag()
                            + ",bundle=" + channelBundleId
                            + ",initiator=" + initiator);
            }
        }

        return transportManager;
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
     * Checks whether media recording is currently enabled for this
     * <tt>Conference</tt>.
     * @return <tt>true</tt> if media recording is currently enabled for this
     * <tt>Conference</tt>, false otherwise.
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    public boolean isRecording()
    {
        boolean recording = this.recording;

        //if one of the contents is not recording, stop all recording
        if (recording)
        {
            synchronized (contents)
            {
                for (Content content : contents)
                {
                    MediaType mediaType = content.getMediaType();

                    if (!MediaType.VIDEO.equals(mediaType)
                            && !MediaType.AUDIO.equals(mediaType))
                    {
                        continue;
                    }
                    if (!content.isRecording())
                    {
                        recording = false;
                    }
                }
            }
        }
        if (this.recording != recording)
        {
            setRecording(recording);
        }

        return this.recording;
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
        else if (source == speechActivity)
        {
            speechActivityPropertyChange(ev);
        }
        else if (Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME.equals(ev.getPropertyName()))
        {
            Set<String> oldSelectedEndpoints = (Set<String>)ev.getOldValue();
            Set<String> newSelectedEndpoints = (Set<String>)ev.getNewValue();
            // Any endpoints in the oldSelectedEndpoints list which AREN'T
            // in the newSelectedEndpoints list should have their count decremented
            oldSelectedEndpoints.stream()
                .filter(oldSelectedEp -> !newSelectedEndpoints.contains(oldSelectedEp))
                .map(this::getEndpoint)
                .filter(Objects::nonNull)
                .forEach(AbstractEndpoint::decrementSelectedCount);

            // Any endpoints in the newSelectedEndpoints list which AREN'T
            // in the oldSelectedEndpoints list should have their count incremented
            newSelectedEndpoints.stream()
                .filter(newSelectedEp -> !oldSelectedEndpoints.contains(newSelectedEp))
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
        boolean removed;

        synchronized (endpoints)
        {
            removed = endpoints.removeIf(AbstractEndpoint::isExpired);
        }

        if (removed)
        {
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
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
            endpoints.add(endpoint);
        }

        firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
    }

    /**
     * @return the {@link OctoEndpoints} instance for this {@link Conference}.
     */
    public OctoEndpoints getOctoEndpoints()
    {
        synchronized (endpoints)
        {
            if (octoEndpoints == null)
            {
                octoEndpoints = new OctoEndpoints(this);
            }

            return octoEndpoints;
        }
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
            AbstractEndpoint dominantSpeaker = speechActivity.getDominantEndpoint();

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
     * Attempts to enable or disable media recording for this
     * <tt>Conference</tt>.
     *
     * @param recording whether to enable or disable recording.
     * @return the state of the media recording for this <tt>Conference</tt>
     * after the attempt to enable (or disable).
     *
     * @deprecated remove-with-recording
     */
    @Deprecated
    boolean setRecording(boolean recording)
    {
        if (recording != this.recording)
        {
            if (recording)
            {
                //try enable recording
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Starting recording for conference with id="
                                + getID());
                }

                String path = getRecordingPath();
                boolean failedToStart = !checkRecordingDirectory(path);

                if (!failedToStart)
                {
                    RecorderEventHandler handler = getRecorderEventHandler();

                    if (handler == null)
                    {
                        failedToStart = true;
                    }
                }
                if (!failedToStart)
                {
                    EndpointRecorder endpointRecorder = getEndpointRecorder();

                    if (endpointRecorder == null)
                    {
                        failedToStart = true;
                    }
                    else
                    {
                        for (AbstractEndpoint endpoint : getEndpoints())
                        {
                            endpointRecorder.updateEndpoint(endpoint);
                        }
                    }
                }

                /*
                 * The Recorders of the Contents need to share a single
                 * Synchronizer, we take it from the first Recorder.
                 */
                boolean first = true;
                Synchronizer synchronizer = null;

                for (Content content : contents)
                {
                    MediaType mediaType = content.getMediaType();

                    if (!MediaType.VIDEO.equals(mediaType)
                            && !MediaType.AUDIO.equals(mediaType))
                    {
                        continue;
                    }

                    if (!failedToStart)
                    {
                        failedToStart = !content.setRecording(true, path);
                    }
                    if (failedToStart)
                    {
                        break;
                    }

                    if (first)
                    {
                        first = false;
                        synchronizer = content.getRecorder().getSynchronizer();
                    }
                    else
                    {
                        Recorder recorder = content.getRecorder();

                        if (recorder != null)
                        {
                            recorder.setSynchronizer(synchronizer);
                        }
                    }

                    content.feedKnownSsrcsToSynchronizer();
                }

                if (failedToStart)
                {
                    recording = false;
                    logger.warn(
                            "Failed to start media recording for conference "
                                + getID());
                }
            }

            // either we were asked to disable recording, or we failed to
            // enable it
            if (!recording)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Stopping recording for conference with id="
                                + getID());
                }

                for (Content content : contents)
                {
                    MediaType mediaType = content.getMediaType();

                    if (MediaType.AUDIO.equals(mediaType)
                            || MediaType.VIDEO.equals(mediaType))
                    {
                        content.setRecording(false, null);
                    }
                }

                if (recorderEventHandler != null)
                {
                    recorderEventHandler.close();
                }
                recorderEventHandler = null;
                recordingPath = null;
                recordingDirectory = null;

                if (endpointRecorder != null)
                {
                    endpointRecorder.close();
                }
                endpointRecorder = null;
            }

            this.recording = recording;
        }

        return this.recording;
    }

    /**
     * Notifies this <tt>Conference</tt> that the ordered list of
     * <tt>Endpoint</tt>s of {@link #speechActivity} i.e. the dominant speaker
     * history has changed.
     * <p>
     * This instance notifies the video <tt>Channel</tt>s about the change so
     * that they may update their last-n lists and report to this instance which
     * <tt>Endpoint</tt>s are to be asked for video keyframes.
     * </p>
     */
    private void speechActivityEndpointsChanged()
    {
        for (Content content : getContents())
        {
            if (MediaType.VIDEO.equals(content.getMediaType()))
            {
                List<AbstractEndpoint> endpoints
                    = Collections.unmodifiableList(
                        speechActivity.getEndpoints());

                content.getChannels().stream()
                    .filter(c -> c instanceof RtpChannel)
                    .forEach(
                        c -> ((RtpChannel) c)
                            .speechActivityEndpointsChanged(endpoints));
            }
        }
    }

    /**
     * Notifies this instance that there was a change in the value of a property
     * of {@link #speechActivity}.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the source of
     * the event/notification, the name of the property and the old and new
     * values of that property
     */
    private void speechActivityPropertyChange(PropertyChangeEvent ev)
    {
        String propertyName = ev.getPropertyName();

        if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME.equals(
                propertyName))
        {
            // The dominant speaker in this Conference has changed. We will
            // likely want to notify the Endpoints participating in this
            // Conference.
            dominantSpeakerChanged();
            speechActivityEndpointsChanged();
        }
        else if (ConferenceSpeechActivity.ENDPOINTS_PROPERTY_NAME.equals(
                propertyName))
        {
            speechActivityEndpointsChanged();
        }
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
     * Updates an <tt>Endpoint</tt> of this <tt>Conference</tt> with the
     * information contained in <tt>colibriEndpoint</tt>. The ID of
     * <tt>colibriEndpoint</tt> is used to select the <tt>Endpoint</tt> to
     * update.
     *
     * @param colibriEndpoint a <tt>ColibriConferenceIQ.Endpoint</tt> instance
     * that contains information to be set on an <tt>Endpoint</tt> instance of
     * this <tt>Conference</tt>.
     */
    void updateEndpoint(ColibriConferenceIQ.Endpoint colibriEndpoint)
    {
        String id = colibriEndpoint.getId();

        if (id != null)
        {
            AbstractEndpoint endpoint = getEndpoint(id);

            if (endpoint != null)
            {
                String oldDisplayName = endpoint.getDisplayName();
                String newDisplayName = colibriEndpoint.getDisplayName();

                if ( (oldDisplayName == null && newDisplayName != null)
                        || (oldDisplayName != null
                              && !oldDisplayName.equals(newDisplayName)))
                {
                    endpoint.setDisplayName(newDisplayName);

                    if (isRecording() && endpointRecorder != null)
                    {
                        endpointRecorder.updateEndpoint(endpoint);
                    }

                    EventAdmin eventAdmin = getEventAdmin();
                    if (eventAdmin != null)
                    {
                        eventAdmin.sendEvent(
                                EventFactory.endpointDisplayNameChanged(
                                        endpoint));
                    }
                }

                endpoint.setStatsId(colibriEndpoint.getStatsId());
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
    public String getLoggingId()
    {
        return loggingId;
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
        return
            getContents().length == 0
                && getLastActivityTime() + 1000L * Channel.DEFAULT_EXPIRE
                        < System.currentTimeMillis();
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
     * Holds conference statistics.
     */
    public class Statistics
    {
        /**
         * The total number of channels where the transport failed to connect.
         */
        AtomicInteger totalNoTransportChannels = new AtomicInteger(0);

        /**
         * The total number of channels where there was no payload traffic.
         */
        AtomicInteger totalNoPayloadChannels = new AtomicInteger(0);

        /**
         * The total number of channels.
         */
        AtomicInteger totalChannels = new AtomicInteger(0);

        /**
         * The total number of ICE transport managers of this conference which
         * successfully connected over UDP.
         */
        AtomicInteger totalUdpTransportManagers = new AtomicInteger();

        /**
         * The total number of ICE transport managers of this conference which
         * successfully connected over TCP.
         */
        AtomicInteger totalTcpTransportManagers = new AtomicInteger();

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
         * The total number of bytes received via Octo in this conference. Note
         * that this is only updated when the Octo channels expire.
         */
        public AtomicLong totalBytesReceivedOcto = new AtomicLong();

        /**
         * The total number of bytes sent via Octo in this conference. Note
         * that this is only updated when the Octo channels expire.
         */
        public AtomicLong totalBytesSentOcto = new AtomicLong();

        /**
         * The total number of packets received via Octo in this conference.
         * Note that this is only updated when the Octo channels expire.
         */
        public AtomicLong totalPacketsReceivedOcto = new AtomicLong();

        /**
         * The total number of packets sent via Octo in this conference. Note
         * that this is only updated when the Octo channels expire.
         */
        public AtomicLong totalPacketsSentOcto = new AtomicLong();
    }
}
