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
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.dsi.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.colibri2.*;
import org.jitsi.videobridge.export.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

import static org.jitsi.xmpp.util.ErrorUtilKt.createError;

/**
 * Represents a conference in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Hristo Terezov
 * @author George Politis
 */
public class Conference
     implements AbstractEndpointMessageTransport.EndpointMessageTransportEventHandler
{
    /**
     * The endpoints participating in this {@link Conference}. Although it's a
     * {@link ConcurrentHashMap}, writing to it must be protected by
     * synchronizing on the map itself, because it must be kept in sync with
     * {@link #endpointsCache}.
     */
    private final ConcurrentHashMap<String, AbstractEndpoint> endpointsById = new ConcurrentHashMap<>();

    /**
     * A boolean that indicates whether or not to include RTCStats for this call.
     */
    private final boolean isRtcStatsEnabled;

    public boolean isRtcStatsEnabled()
    {
        return isRtcStatsEnabled;
    }

    /**
     * A read-only cache of the endpoints in this conference. Note that it
     * contains only the {@link Endpoint} instances (and not {@link RelayedEndpoint}s).
     * This is because the cache was introduced for performance reasons only
     * (we iterate over it for each RTP packet) and the relayed endpoints are not always needed.
     */
    private List<Endpoint> endpointsCache = Collections.emptyList();

    private final Object endpointsCacheLock = new Object();

    /**
     * A map of the endpoints in this conference, by their ssrcs.
     */
    private final ConcurrentHashMap<Long, AbstractEndpoint> endpointsBySsrc = new ConcurrentHashMap<>();

    /**
     * The relays participating in this conference.
     */
    private final ConcurrentHashMap<String, Relay> relaysById = new ConcurrentHashMap<>();

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Conference</tt>.
     */
    private final AtomicBoolean expired = new AtomicBoolean(false);

    public long getLocalAudioSsrc()
    {
        return localAudioSsrc;
    }

    public long getLocalVideoSsrc()
    {
        return localVideoSsrc;
    }

    private final long localAudioSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;
    private final long localVideoSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;

    /**
     * The locally unique identifier of this conference (i.e. unique across the
     * conferences on this bridge). It is locally generated and exposed via
     * colibri, and used by colibri clients to reference the conference.
     */
    private final String id;

    /**
     * The world readable name of this instance if any.
     */
    private final @Nullable EntityBareJid conferenceName;

    /**
     * The speech activity (representation) of the <tt>Endpoint</tt>s of this
     * <tt>Conference</tt>.
     */
    private final ConferenceSpeechActivity speechActivity;

    /**
     * The <tt>Videobridge</tt> which has initialized this <tt>Conference</tt>.
     */
    private final Videobridge videobridge;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The time when this {@link Conference} was created.
     */
    private final long creationTime = System.currentTimeMillis();

    /**
     * The shim which handles Colibri v2 related logic for this conference (translates colibri v2 signaling into the
     * native videobridge APIs).
     */
    @NotNull private final Colibri2ConferenceHandler colibri2Handler;

    /**
     * The queue handling colibri (both v1 and v2) messages for this conference.
     */
    @NotNull private final PacketQueue<XmppConnection.ColibriRequest> colibriQueue;

    @NotNull private final EncodingsManager encodingsManager = new EncodingsManager();

    /**
     * Cache here because it's accessed on every packet.
     */
    private final boolean routeLoudestOnly = LoudestConfig.getRouteLoudestOnly();

    /**
     * Audio subscription manager for handling endpoint subscriptions.
     */
    @NotNull
    private final AudioSubscriptionManager audioSubscriptionManager = new AudioSubscriptionManager();

    /**
     * The task of updating the ordered list of endpoints in the conference. It runs periodically in order to adapt to
     * endpoints stopping or starting to their video streams (which affects the order).
     */
    private ScheduledFuture<?> updateLastNEndpointsFuture;

    @NotNull
    private final EndpointConnectionStatusMonitor epConnectionStatusMonitor;

    /**
     * A unique meeting ID optionally set by the signaling server ({@code null} if not explicitly set). It is exposed
     * via ({@link #getDebugState(DebugStateMode, String)} ()} for outside use.
     */
    @Nullable
    private final String meetingId;

    @NotNull
    private final ExporterWrapper exporter;

    /**
     * A regex pattern to trim UUIDs to just their first 8 hex characters.
     */
    private final static Pattern uuidTrimmer = Pattern.compile("(\\p{XDigit}{8})[\\p{XDigit}-]*");

    /**
     * Initializes a new <tt>Conference</tt> instance which is to represent a
     * conference in the terms of Jitsi Videobridge which has a specific
     * (unique) ID.
     *
     * @param videobridge the <tt>Videobridge</tt> on which the new
     * <tt>Conference</tt> instance is to be initialized
     * @param id the (unique) ID of the new instance to be initialized
     * @param conferenceName world readable name of this conference
     */
    public Conference(Videobridge videobridge,
                      String id,
                      @Nullable EntityBareJid conferenceName,
                      @Nullable String meetingId,
                      boolean isRtcStatsEnabled)
    {
        this.meetingId = meetingId;
        this.videobridge = Objects.requireNonNull(videobridge, "videobridge");
        this.isRtcStatsEnabled = isRtcStatsEnabled;
        Map<String, String> context = new HashMap<>(Map.of("confId", id));
        if (conferenceName != null)
        {
            context.put("conf_name", conferenceName.toString());
        }
        if (meetingId != null)
        {
            /* We usually generate meeting IDs as a UUID - include just their first octet. */
            context.put("meeting_id", uuidTrimmer.matcher(meetingId).replaceAll("$1"));
        }

        logger = new LoggerImpl(Conference.class.getName(), new LogContext(context));
        exporter = new ExporterWrapper(logger);
        this.id = Objects.requireNonNull(id, "id");
        this.conferenceName = conferenceName;
        this.colibri2Handler = new Colibri2ConferenceHandler(this, logger);
        colibriQueue = new ColibriQueue(
                request ->
                {
                    try
                    {
                        logger.info( () -> {
                            String reqStr = request.getRequest().toXML().toString();
                            if (VideobridgeConfig.getRedactRemoteAddresses())
                            {
                                reqStr = RedactColibriIp.Companion.redact(reqStr);
                            }
                            return "RECV colibri2 request: " + reqStr;
                        });
                        long start = System.currentTimeMillis();
                        Pair<IQ, Boolean> p = colibri2Handler.handleConferenceModifyIQ(request.getRequest());
                        IQ response = p.getFirst();
                        boolean expire = p.getSecond();
                        long end = System.currentTimeMillis();
                        long processingDelay = end - start;
                        long totalDelay = end - request.getReceiveTime();
                        request.getProcessingDelayStats().addDelay(processingDelay);
                        request.getTotalDelayStats().addDelay(totalDelay);
                        if (processingDelay > 100)
                        {
                            String reqStr = request.getRequest().toXML().toString();
                            if (VideobridgeConfig.getRedactRemoteAddresses())
                            {
                                reqStr = RedactColibriIp.Companion.redact(reqStr);
                            }
                            logger.warn("Took " + processingDelay + " ms to process an IQ (total delay "
                                    + totalDelay + " ms): " + reqStr);
                        }
                        logger.info("SENT colibri2 response: " + response.toXML());
                        request.getCallback().invoke(response);
                        if (expire) videobridge.expireConference(this);
                    }
                    catch (Throwable e)
                    {
                        logger.warn("Failed to handle colibri request: ", e);
                        request.getCallback().invoke(
                                createError(
                                        request.getRequest(),
                                        StanzaError.Condition.internal_server_error,
                                        e.getMessage()));
                    }
                    return true;
                }
        )
        {
        };

        speechActivity = new ConferenceSpeechActivity(new SpeechActivityListener());
        updateLastNEndpointsFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(() -> {
            try
            {
                if (speechActivity.updateLastNEndpoints())
                {
                    lastNEndpointsChangedAsync();
                }
            }
            catch (Exception e)
            {
                logger.warn("Failed to update lastN endpoints:", e);
            }

        }, 3, 3, TimeUnit.SECONDS);

        VideobridgeMetrics.conferencesCreated.inc();
        epConnectionStatusMonitor = new EndpointConnectionStatusMonitor(this, TaskPools.SCHEDULED_POOL, logger);
        epConnectionStatusMonitor.start();
    }

    public void enqueueColibriRequest(XmppConnection.ColibriRequest request)
    {
        colibriQueue.add(request);
    }

    /**
     * Creates a new diagnostic context instance that includes the conference
     * name and the conference creation time.
     *
     * @return the new {@link DiagnosticContext} instance.
     */
    public DiagnosticContext newDiagnosticContext()
    {
        if (conferenceName != null)
        {
            DiagnosticContext diagnosticContext = new DiagnosticContext();
            diagnosticContext.put("conf_name", conferenceName.toString());
            diagnosticContext.put("conf_creation_time_ms", creationTime);
            return diagnosticContext;
        }
        else
        {
            return new NoOpDiagnosticContext();
        }
    }

    /**
     * Sends a message to a subset of endpoints in the call, primary use
     * case being a message that has originated from an endpoint (as opposed to
     * a message originating from the bridge and being sent to all endpoints in
     * the call, for that see {@link #broadcastMessage(BridgeChannelMessage)}.
     *
     * @param msg the message to be sent
     * @param endpoints the list of <tt>Endpoint</tt>s to which the message will
     * be sent.
     */
    public void sendMessage(BridgeChannelMessage msg, List<Endpoint> endpoints, boolean sendToRelays)
    {
        for (Endpoint endpoint : endpoints)
        {
            endpoint.sendMessage(msg);
        }

        if (sendToRelays)
        {
            for (Relay relay: relaysById.values())
            {
                relay.sendMessage(msg);
            }
        }
    }

    /**
     * Sends a message that originated from a relay, forwarding it to local endpoints
     * (if {@param sendToEndpoints} is true) and to those relays with different mesh-id values than the source relay
     *
     * @param msg the message to be sent
     * @param meshId the ID of the mesh from which the message was received.
     */
    public void sendMessageFromRelay(BridgeChannelMessage msg, boolean sendToEndpoints, @Nullable String meshId)
    {
        if (sendToEndpoints)
        {
            for (Endpoint endpoint : getLocalEndpoints())
            {
                endpoint.sendMessage(msg);
            }
        }

        for (Relay relay: relaysById.values())
        {
            if (!Objects.equals(meshId, relay.getMeshId()))
            {
                relay.sendMessage(msg);
            }
        }
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(BridgeChannelMessage msg, boolean sendToRelays)
    {
        sendMessage(msg, getLocalEndpoints(), sendToRelays);
    }

    /**
     * Broadcasts a string message to all endpoints of the conference.
     *
     * @param msg the message to be broadcast.
     */
    public void broadcastMessage(BridgeChannelMessage msg)
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
            logger.debug("Cannot request keyframe because the endpoint was not found.");
        }
    }

    /**
     * Handles a colibri2 {@link ConferenceModifyIQ} for this conference. Exposed for the cases when it needs to be
     * done synchronously (as opposed as by {@link #colibriQueue}).
     * @return the response as an IQ, either an {@link ErrorIQ} or a {@link ConferenceModifiedIQ}.
     */
    IQ handleConferenceModifyIQ(ConferenceModifyIQ conferenceModifyIQ)
    {
        Pair<IQ, Boolean> p = colibri2Handler.handleConferenceModifyIQ(conferenceModifyIQ);
        if (p.getSecond())
        {
            videobridge.expireConference(this);
        }
        return p.getFirst();
    }

    /**
     * Runs {@link #lastNEndpointsChanged()} in an IO pool thread.
     */
    private void lastNEndpointsChangedAsync()
    {
        TaskPools.IO_POOL.execute(() ->
        {
            try
            {
                lastNEndpointsChanged();
            }
            catch (Exception e)
            {
                logger.warn("Failed to handle change in last N endpoints: ", e);
            }
        });
    }

    /**
     * Updates all endpoints with a new list of ordered endpoints in the conference.
     */
    private void lastNEndpointsChanged()
    {
        endpointsCache.forEach(Endpoint::lastNEndpointsChanged);
    }

    /**
     * Notifies this instance that {@link #speechActivity} has identified a speaker switch event and there is now a new
     * dominant speaker.
     * @param recentSpeakers the list of recent speakers (including the dominant speaker at index 0).
     */
    private void recentSpeakersChanged(
            List<AbstractEndpoint> recentSpeakers,
            boolean dominantSpeakerChanged,
            boolean silence)
    {
        if (!recentSpeakers.isEmpty())
        {
            List<String> recentSpeakersIds
                    = recentSpeakers.stream().map(AbstractEndpoint::getId).collect(Collectors.toList());
            logger.info("Recent speakers changed: " + recentSpeakersIds + ", dominant speaker changed: "
                    + dominantSpeakerChanged + " silence:" + silence);
            broadcastMessage(new DominantSpeakerMessage(recentSpeakersIds, silence));

            if (dominantSpeakerChanged && !silence)
            {
                VideobridgeMetrics.dominantSpeakerChanges.inc();
                if (getEndpointCount() > 2)
                {
                    maybeSendKeyframeRequest(recentSpeakers.get(0));
                }
            }
        }
    }

    /**
     * Schedules sending a pre-emptive keyframe request (if necessary) when a new dominant speaker is elected.
     * @param dominantSpeaker the new dominant speaker.
     */
    private void maybeSendKeyframeRequest(AbstractEndpoint dominantSpeaker)
    {
        if (dominantSpeaker == null)
        {
            return;
        }

        boolean anyEndpointInStageView = false;
        Set<String> allOnStageSourceNames = new HashSet<>();
        for (Endpoint otherEndpoint : getLocalEndpoints())
        {
            if (otherEndpoint != dominantSpeaker)
            {
                allOnStageSourceNames.addAll(otherEndpoint.getOnStageSources());
            }
        }

        for (String onStageSourceName : allOnStageSourceNames)
        {
            AbstractEndpoint owner = findSourceOwner(onStageSourceName);
            if (owner != null)
            {
                // Do not anticipate a switch if all on-stage sources are DESKTOP
                MediaSourceDesc onStageSource = owner.findMediaSourceDesc(onStageSourceName);
                if (onStageSource != null && onStageSource.getVideoType() == VideoType.CAMERA)
                {
                    anyEndpointInStageView = true;
                    break;
                }
            }
        }

        if (!anyEndpointInStageView)
        {
            // If all other endpoints are in tile view, there is no switch to anticipate. Don't trigger an unnecessary
            // keyframe.
            VideobridgeMetrics.preemptiveKeyframeRequestsSuppressed.inc();
            return;
        }
        VideobridgeMetrics.preemptiveKeyframeRequestsSent.inc();

        double senderRtt = getRtt(dominantSpeaker);
        double maxReceiveRtt = getMaxReceiverRtt(dominantSpeaker.getId());
        // We add an additional 10ms delay to reduce the risk of the keyframe arriving too early
        double keyframeDelay = maxReceiveRtt - senderRtt + 10;
        if (logger.isDebugEnabled())
        {
            logger.debug("Scheduling keyframe request from " + dominantSpeaker.getId() + " after a delay" +
                    " of " + keyframeDelay + "ms");
        }
        TaskPools.SCHEDULED_POOL.schedule(
                (Runnable)dominantSpeaker::requestKeyframe,
                (long)keyframeDelay,
                TimeUnit.MILLISECONDS
        );
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
            // This is a RelayedEndpoint
            // TODO(brian): we don't currently have a way to get the RTT from this bridge to a remote endpoint, so we
            // hard-code a value here. We could relatively easily get the RTT to the remote relay. Should we take that
            // into account?
            return 100;
        }
    }

    private double getMaxReceiverRtt(String excludedEndpointId)
    {
        return endpointsCache.stream()
                .filter(ep -> !ep.getId().equalsIgnoreCase(excludedEndpointId))
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
     * <p/>
     * NOTE: this should _only_ be called by the Conference "manager" (in this
     * case, Videobridge).  If you need to expire a Conference from elsewhere, use
     * {@link Videobridge#expireConference(Conference)}
     */
    void expire()
    {
        if (!expired.compareAndSet(false, true))
        {
            return;
        }

        logger.info("Expiring.");

        colibriQueue.close();

        epConnectionStatusMonitor.stop();

        if (updateLastNEndpointsFuture != null)
        {
            updateLastNEndpointsFuture.cancel(true);
            updateLastNEndpointsFuture = null;
        }

        logger.debug(() -> "Expiring endpoints.");
        getEndpoints().forEach(AbstractEndpoint::expire);
        getRelays().forEach(Relay::expire);
        exporter.stop();
        speechActivity.expire();

        updateStatisticsOnExpire();
    }

    /**
     * Updates the statistics for this conference when it is about to expire.
     */
    private void updateStatisticsOnExpire()
    {
        long durationSeconds = Math.round((System.currentTimeMillis() - creationTime) / 1000d);

        VideobridgeMetrics.conferencesCompleted.inc();
        VideobridgeMetrics.totalConferenceSeconds.add(durationSeconds);

        logger.info("expire_conf,duration=" + durationSeconds);
    }

    /**
     * Finds an <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with a specific SSRC.
     *
     * @param receiveSSRC the SSRC of an RTP stream received by this
     * <tt>Conference</tt> whose sending <tt>Endpoint</tt> is to be found
     * @return <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with the specified <tt>ssrc</tt>; otherwise, <tt>null</tt>
     */
    AbstractEndpoint findEndpointByReceiveSSRC(long receiveSSRC)
    {
        return getEndpoints().stream()
                .filter(ep -> ep.receivesSsrc(receiveSSRC))
                .findFirst()
                .orElse(null);
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
        return endpointsById.get(Objects.requireNonNull(id, "id must be non null"));
    }

    @Nullable
    public Relay getRelay(@NotNull String id)
    {
        return relaysById.get(id);
    }

    @Nullable
    public AbstractEndpoint findSourceOwner(@NotNull String sourceName)
    {
        for (AbstractEndpoint e : endpointsById.values())
        {
            if (e.findMediaSourceDesc(sourceName) != null)
            {
                return e;
            }
        }

        return null;
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
     * @param doSsrcRewriting whether this endpoint signaled SSRC rewriting support.
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     */
    @NotNull
    public Endpoint createLocalEndpoint(
            String id,
            boolean iceControlling,
            boolean doSsrcRewriting,
            boolean visitor,
            boolean privateAddresses)
    {
        final AbstractEndpoint existingEndpoint = getEndpoint(id);
        if (existingEndpoint != null)
        {
            throw new IllegalArgumentException("Local endpoint with ID = " + id + "already created");
        }

        final Endpoint endpoint = new Endpoint(
                id, this, logger, iceControlling, doSsrcRewriting, visitor, privateAddresses);
        videobridge.localEndpointCreated(visitor);

        endpoint.addEventHandler(() -> endpointSourcesChanged(endpoint));

        addEndpoints(Collections.singleton(endpoint));

        return endpoint;
    }

    @NotNull
    public Relay createRelay(String id, @Nullable String meshId, boolean iceControlling, boolean useUniquePort)
    {
        final Relay existingRelay = getRelay(id);
        if (existingRelay != null)
        {
            throw new IllegalArgumentException("Relay with ID = " + id + "already created");
        }

        final Relay relay = new Relay(id, this, logger, meshId, iceControlling, useUniquePort);

        relaysById.put(id, relay);

        return relay;
    }

    /**
     * One or more endpoints was added or removed.
     * @param includesNonVisitors Whether any of the endpoints changed was not a visitor.
     */
    private void endpointsChanged(boolean includesNonVisitors)
    {
        if (includesNonVisitors)
        {
            speechActivity.endpointsChanged(getNonVisitorEndpoints());
        }
    }

    /**
     * The media sources of one of the endpoints in this conference changed.
     *
     * @param endpoint the endpoint
     */
    private void endpointSourcesChanged(@NotNull Endpoint endpoint)
    {
        // Force an update to be propagated to each endpoint's bitrate controller.
        // We run this async because this method is called in the Colibri critical path.
        lastNEndpointsChangedAsync();
    }

    /**
     * Updates {@link #endpointsCache} with the current contents of
     * {@link #endpointsById}.
     */
    private void updateEndpointsCache()
    {
        synchronized (endpointsCacheLock)
        {
            ArrayList<Endpoint> endpointsList = new ArrayList<>(endpointsById.size());
            endpointsById.values().forEach(e ->
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
     * Returns the number of local AND remote endpoints in this {@link Conference}.
     *
     * @return the number of local AND remote endpoints in this {@link Conference}.
     */
    public int getEndpointCount()
    {
        return endpointsById.size();
    }

    /**
     * @return the number of relays.
     */
    public int getRelayCount()
    {
        return relaysById.size();
    }

    /**
     * Returns the number of local endpoints in this {@link Conference}.
     *
     * @return the number of local endpoints in this {@link Conference}.
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
        return new ArrayList<>(this.endpointsById.values());
    }

    /**
     * Gets the <tt>Endpoint</tt>s participating in this conference that are not visitors.
     */
    public List<AbstractEndpoint> getNonVisitorEndpoints()
    {
        return this.endpointsById.values().stream().filter(ep -> !ep.getVisitor()).collect(Collectors.toList());
    }

    List<AbstractEndpoint> getOrderedEndpoints()
    {
        return speechActivity.getOrderedEndpoints();
    }

    /**
     * Gets the list of endpoints which are local to this bridge (as opposed to {@link RelayedEndpoint}s connected to
     * one of our {@link Relay}s).
     */
    public List<Endpoint> getLocalEndpoints()
    {
        return endpointsCache;
    }

    /**
     * Gets the list of the relays this conference is using.
     */
    public List<Relay> getRelays()
    {
        return new ArrayList<>(this.relaysById.values());
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
     * Gets the signaling-server-defined meetingId of this conference, if set.
     *
     * @return The signaling-server-defined meetingId of this conference, or null.
     */
    public final @Nullable String getMeetingId()
    {
        return meetingId;
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
        return expired.get();
    }

    /**
     * Notifies this conference that one of its endpoints has expired.
     *
     * @param endpoint the <tt>Endpoint</tt> which expired.
     */
    public void endpointExpired(AbstractEndpoint endpoint)
    {
        final AbstractEndpoint removedEndpoint;
        String id = endpoint.getId();
        removedEndpoint = endpointsById.remove(id);
        if (removedEndpoint == null)
        {
            logger.warn("No endpoint found, id=" + id);
            return;
        }

        if (removedEndpoint instanceof Endpoint)
        {
            // The removed endpoint was a local Endpoint as opposed to a RelayedEndpoint.
            updateEndpointsCache();
            endpointsById.values().forEach(e -> e.otherEndpointExpired(removedEndpoint));
            videobridge.localEndpointExpired(removedEndpoint.getVisitor());
        }

        relaysById.forEach((i, relay) -> relay.endpointExpired(id));
        endpoint.getSsrcs().forEach(ssrc -> {
            endpointsBySsrc.remove(ssrc, endpoint);
        });
        audioSubscriptionManager.removeEndpoint(endpoint.getId());
        audioSubscriptionManager.removeSources(new HashSet<>(endpoint.getAudioSources()));
        endpointsChanged(removedEndpoint.getVisitor());
    }

    /**
     * Notifies this conference that one of its relays has expired.
     *
     * @param relay the <tt>Relay</tt> which expired.
     */
    public void relayExpired(Relay relay)
    {
        String id = relay.getId();
        relaysById.remove(id);

        getLocalEndpoints().forEach(senderEndpoint -> senderEndpoint.removeReceiver(id));
    }

    /**
     * Adds a set of {@link AbstractEndpoint} instances to the list of endpoints in this conference.
     */
    public void addEndpoints(Set<AbstractEndpoint> endpoints)
    {
        endpoints.forEach(endpoint -> {
            if (endpoint.getConference() != this)
            {
                throw new IllegalArgumentException("Endpoint belong to other " +
                        "conference = " + endpoint.getConference());
            }
            AbstractEndpoint replacedEndpoint = endpointsById.put(endpoint.getId(), endpoint);
            if (replacedEndpoint != null)
            {
                logger.info("Endpoint with id " + endpoint.getId() + ": " +
                        replacedEndpoint + " has been replaced by new " +
                        "endpoint with same id: " + endpoint);
            }
        });

        updateEndpointsCache();

        boolean hasNonVisitor = endpoints.stream().anyMatch(endpoint -> !endpoint.getVisitor());

        endpointsChanged(hasNonVisitor);
    }

    /**
     * Notifies this {@link Conference} that one of its endpoints'
     * transport channel has become available.
     *
     * @param abstractEndpoint the endpoint whose transport channel has become
     * available.
     */
    @Override
    public void endpointMessageTransportConnected(@NotNull AbstractEndpoint abstractEndpoint)
    {
        /* TODO: this is only called for actual Endpoints, but the API needs cleaning up. */
        Endpoint endpoint = (Endpoint)abstractEndpoint;

        epConnectionStatusMonitor.endpointConnected(endpoint.getId());

        if (!isExpired())
        {
            List<String> recentSpeakers = speechActivity.getRecentSpeakers();

            if (!recentSpeakers.isEmpty())
            {
                endpoint.sendMessage(new DominantSpeakerMessage(recentSpeakers, speechActivity.isInSilence()));
            }
        }
    }

    public AbstractEndpoint getEndpointBySsrc(long ssrc)
    {
        return endpointsBySsrc.get(ssrc);
    }

    public void addEndpointSsrc(@NotNull AbstractEndpoint endpoint, long ssrc)
    {
        AbstractEndpoint oldEndpoint = endpointsBySsrc.put(ssrc, endpoint);
        if (oldEndpoint != null && oldEndpoint != endpoint)
        {
            logger.warn("SSRC " + ssrc + " moved from ep " + oldEndpoint.getId() + " to ep " + endpoint.getId());
        }
    }

    /**
     * Gets local audio SSRCs in the conference
     * @return the list of audio SSRCs in the conference
     */
    public List<AudioSourceDesc> getAudioSourceDescs()
    {
        List<AudioSourceDesc> descs = new ArrayList<>();
        for (Endpoint endpoint : getLocalEndpoints()) {
            descs.addAll(endpoint.getAudioSources());
        }
        return descs;
    }

    /**
     * Gets the conference name.
     *
     * @return the conference name
     */
    public @Nullable EntityBareJid getName()
    {
        return conferenceName;
    }

    /**
     * @return the {@link Logger} used by this instance.
     */
    public Logger getLogger()
    {
        return logger;
    }

    public void addAudioSources(Set<AudioSourceDesc> audioSources)
    {
        audioSubscriptionManager.onSourcesAdded(audioSources);
    }

    public void removeAudioSources(Set<AudioSourceDesc> audioSources)
    {
        audioSubscriptionManager.removeSources(audioSources);
    }

    /**
     * Sets the audio subscription for a given endpoint.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    public void setEndpointAudioSubscription(String endpointId, ReceiverAudioSubscriptionMessage subscription)
    {
        audioSubscriptionManager.setEndpointAudioSubscription(endpointId, subscription, getAudioSourceDescs());
    }

    /**
     * Checks if audio from a given SSRC is wanted by a specific endpoint.
     * @param endpointId the ID of the endpoint
     * @param ssrc the SSRC to check
     * @return true if the audio is wanted, false otherwise
     */
    public boolean isEndpointAudioWanted(String endpointId, long ssrc)
    {
        return audioSubscriptionManager.isEndpointAudioWanted(endpointId, ssrc);
    }

    /**
     * @return {@code true} if this {@link Conference} is in more than one relay mesh.
     */
    private boolean inMultipleMeshes()
    {
        return relaysById.values().stream().map(Relay::getMeshId).collect(Collectors.toSet()).size() > 1;
    }

    /**
     * @return {@code true} if this {@link Conference} is ready to be expired.
     */
    public boolean shouldExpire()
    {
        // Allow a conference to have no endpoints in the first 20 seconds.
        return getEndpointCount() == 0
                && !inMultipleMeshes()
                && (System.currentTimeMillis() - creationTime > 20000);
    }

    /**
     * Determine whether a given endpointId is currently a ranked speaker, if
     * speaker ranking is currently enabled.
     */
    public boolean isRankedSpeaker(AbstractEndpoint ep)
    {
        if (!LoudestConfig.Companion.getRouteLoudestOnly())
        {
            // When "route loudest only" is disabled all speakers should be considered "ranked" (we forward all audio
            // and stats).
            return true;
        }
        return speechActivity.isAmongLoudest(ep.getId());
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
            if (endpoint.getId().equals(sourceEndpointId))
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
        for (Relay relay: relaysById.values())
        {
            if (relay.wants(packetInfo))
            {
                if (prevHandler != null)
                {
                    prevHandler.send(packetInfo.clone());
                }
                prevHandler = relay;
            }
        }
        if (exporter.wants(packetInfo))
        {
            if (prevHandler != null)
            {
                prevHandler.send(packetInfo.clone());
            }
            prevHandler = exporter;
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

    public void setConnects(List<Connect> exports)
    {
        exporter.setConnects(exports);
    }

    public boolean hasRelays()
    {
        return !relaysById.isEmpty();
    }

    /**
     * Handles an RTP/RTCP packet coming from a specific endpoint.
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
        else if (packet instanceof RtcpFbPliPacket || packet instanceof RtcpFbFirPacket)
        {
            AbstractEndpoint targetEndpoint = null;
            boolean rewriter = false;

            long mediaSsrc = (packet instanceof RtcpFbPliPacket)
                ? ((RtcpFbPliPacket) packet).getMediaSourceSsrc()
                : ((RtcpFbFirPacket) packet).getMediaSenderSsrc();

            /* If we are rewriting SSRCs to this endpoint, we must ask
            it to convert back the SSRC to the media sender's SSRC. */
            String endpointId = packetInfo.getEndpointId();
            if (endpointId != null)
            {
                AbstractEndpoint ep = getEndpoint(endpointId);
                if (ep instanceof Endpoint && ((Endpoint) ep).doesSsrcRewriting())
                {
                    rewriter = true;
                    String owner = ((Endpoint) ep).unmapRtcpFbSsrc((RtcpFbPacket) packet);
                    if (owner != null)
                        targetEndpoint = getEndpoint(owner);
                }
            }

            if (!rewriter)
            {
                // XXX we could make this faster with a map
                targetEndpoint = findEndpointByReceiveSSRC(mediaSsrc);
            }

            PotentialPacketHandler pph = null;
            if (targetEndpoint instanceof Endpoint)
            {
                pph = (Endpoint) targetEndpoint;
            }
            else if (targetEndpoint instanceof RelayedEndpoint)
            {
                pph = ((RelayedEndpoint)targetEndpoint).getRelay();
            }

            // This is not a redundant check. With Octo and 3 or more bridges,
            // some PLI or FIR will come from Octo but the target endpoint will
            // also be Octo. We need to filter these out.
            // TODO: does this still apply with Relays?
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
     * Process a new audio level received from an endpoint.
     *
     * @param endpoint the endpoint for which a new audio level was received
     * @param level the new audio level which was received
     * @return Whether the packet providing this audio level should be dropped, according
     * to the current audio filtering configuration.
     */
    public boolean levelChanged(@NotNull AbstractEndpoint endpoint, long level)
    {
        SpeakerRanking ranking = speechActivity.levelChanged(endpoint, level);
        if (ranking == null || !routeLoudestOnly)
            return false;
        if (ranking.isDominant && LoudestConfig.Companion.getAlwaysRouteDominant())
            return false;
        if (ranking.energyRanking < LoudestConfig.Companion.getNumLoudest())
            return false;
        // return false if the source is subscribed with an "Include" subscription by any other endpoint.
        List<AudioSourceDesc> sources = endpoint.getAudioSources();
        if (!sources.isEmpty())
        {
            AudioSourceDesc source = sources.get(0); // assume one source per endpoint
            if (audioSubscriptionManager.isExplicitlySubscribed(source.getSourceName()))
            {
                return false;
            }
        }
        VideobridgeMetrics.tossedPacketsEnergy.getHistogram().observe(ranking.energyScore);
        return true;
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     *
     * @param mode determines how much detail to include in the debug state.
     * @param endpointId the ID of the endpoint to include. If set to {@code null}, all endpoints will be included.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState(@NotNull DebugStateMode mode, @Nullable String endpointId)
    {
        if (mode == DebugStateMode.STATS && !isRtcStatsEnabled)
        {
            return new JSONObject();
        }

        boolean full = mode == DebugStateMode.FULL || mode == DebugStateMode.STATS;
        JSONObject debugState = new JSONObject();
        debugState.put("id", id);

        // Keep this camelCase for compatibility with jvb-rtcstats-push
        debugState.put("rtcstatsEnabled", isRtcStatsEnabled);

        if (conferenceName != null)
        {
            debugState.put("name", conferenceName.toString());
        }
        if (meetingId != null)
        {
            debugState.put("meeting_id", meetingId);
        }

        if (full)
        {
            debugState.put("expired", expired.get());
            debugState.put("creation_time", creationTime);
        }
        if (mode == DebugStateMode.FULL || mode == DebugStateMode.STATS)
        {
            debugState.put("speech_activity", speechActivity.getDebugState(mode));
        }

        JSONObject endpoints = new JSONObject();
        debugState.put("endpoints", endpoints);
        for (Endpoint e : endpointsCache)
        {
            if (endpointId == null || endpointId.equals(e.getId()))
            {
                if (mode == DebugStateMode.SHORT)
                {
                    endpoints.put(e.getId(), e.getStatsId());
                }
                else
                {
                    endpoints.put(e.getId(), e.debugState(mode));
                }
            }
        }

        JSONObject relays = new JSONObject();
        debugState.put("relays", relays);
        for (Relay r : relaysById.values())
        {
            if (mode == DebugStateMode.SHORT)
            {
                relays.put(r.getId(), r.getId());
            }
            else
            {
                relays.put(r.getId(), r.debugState(mode));
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

    @NotNull public EncodingsManager getEncodingsManager()
    {
        return encodingsManager;
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

    private class SpeechActivityListener implements ConferenceSpeechActivity.Listener
    {
        @Override
        public void recentSpeakersChanged(
                List<AbstractEndpoint> recentSpeakers,
                boolean dominantSpeakerChanged,
                boolean silence)
        {
            Conference.this.recentSpeakersChanged(recentSpeakers, dominantSpeakerChanged, silence);
        }

        @Override
        public void lastNEndpointsChanged()
        {
            Conference.this.lastNEndpointsChanged();
        }
    }
}
