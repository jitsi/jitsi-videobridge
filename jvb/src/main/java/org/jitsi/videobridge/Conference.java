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
import org.jitsi.mediajson.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.utils.LRUCache;
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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * Source names for which an injected (translated) synthetic source has already been created, so we log its
     * creation only once. Cleared when the source is removed, so a re-created source logs again.
     */
    private final Set<String> loggedInjectedSources = ConcurrentHashMap.newKeySet();

    /**
     * Tags of received translated-media events that didn't match any synthetic source, so we warn about an
     * unrecognized tag only once rather than on every packet. Bounded (an unknown tag is never removed via
     * {@link #removeAudioSources}, since it corresponds to no known source): the worst case of eviction is warning
     * again for a tag we saw long ago.
     */
    private final Set<String> loggedUnknownMediaTags =
        Collections.synchronizedSet(LRUCache.lruSet(SYNTHETIC_SOURCE_LOG_CACHE_SIZE, true));

    /**
     * Names of synthetic sources that have been removed (unsubscribed) and not since re-added. Media for these can
     * still arrive briefly -- the translator may have sent it before it saw the unsubscribe -- so it's an expected
     * transient rather than an unrecognized tag, and is dropped without a warning. Bounded (an entry is removed only
     * when a source with the same name is re-added, so uniquely-named sources would otherwise accumulate for the
     * conference's lifetime): the worst case of eviction is warning once for late media of a long-removed source.
     */
    private final Set<String> removedSyntheticSources =
        Collections.synchronizedSet(LRUCache.lruSet(SYNTHETIC_SOURCE_LOG_CACHE_SIZE, true));

    /**
     * The conference's negotiated Opus payload type, resolved lazily by {@link #getOpusPayloadType()} and cached
     * (payload types are never rewritten, so it is consistent across all endpoints for the conference's lifetime).
     */
    private volatile PayloadType opusPayloadType = null;

    /**
     * Whether we have warned about translated media arriving before any Opus payload type is negotiated, so the
     * warning is logged once rather than per media event.
     */
    private final AtomicBoolean loggedNoOpusPayloadType = new AtomicBoolean(false);

    /**
     * A regex pattern to trim UUIDs to just their first 8 hex characters.
     */
    private final static Pattern uuidTrimmer = Pattern.compile("(\\p{XDigit}{8})[\\p{XDigit}-]*");

    /**
     * The maximum size of the bounded sets that suppress repeated synthetic-source log messages
     * ({@link #loggedUnknownMediaTags}, {@link #removedSyntheticSources}).
     */
    private static final int SYNTHETIC_SOURCE_LOG_CACHE_SIZE = 1000;

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
        exporter = new ExporterWrapper(
            logger,
            j -> {
                handleTranscriptionMessage(j);
                return Unit.INSTANCE;
            },
            m -> {
                handleMediaMessage(m);
                return Unit.INSTANCE;
            },
            (sourceName, sending, timestamp) -> {
                handleSyntheticSourceSendingChange(sourceName, sending, timestamp);
                return Unit.INSTANCE;
            },
            ssrc -> getAudioSourceName(ssrc),
            ssrc -> getDiarize(ssrc));
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
                            if (VideobridgeConfig.getRedactColibriHttpHeaders())
                            {
                                reqStr = RedactColibri.Companion.redactHttpHeaderValues(reqStr);
                            }
                            if (VideobridgeConfig.getRedactRemoteAddresses())
                            {
                                reqStr = RedactColibri.Companion.redactIp(reqStr);
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
                            if (VideobridgeConfig.getRedactColibriHttpHeaders())
                            {
                                reqStr = RedactColibri.Companion.redactHttpHeaderValues(reqStr);
                            }
                            if (VideobridgeConfig.getRedactRemoteAddresses())
                            {
                                reqStr = RedactColibri.Companion.redactIp(reqStr);
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
     * @param requesterID the id of the endpoint requesting a keyframe
     * @param endpointID the id of the endpoint to request a keyframe from.
     * @param mediaSsrc the primary SSRC of the source for which to request a keyframe
     */
    public void requestKeyframe(String requesterID, String endpointID, long mediaSsrc)
    {
        AbstractEndpoint remoteEndpoint = getEndpoint(endpointID);

        if (remoteEndpoint != null)
        {
            remoteEndpoint.requestKeyframe(requesterID, mediaSsrc);
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
     * @param diarize whether diarization is requested for this endpoint's audio (colibri2 {@code diarize} attribute).
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     */
    @NotNull
    public Endpoint createLocalEndpoint(
            String id,
            boolean iceControlling,
            boolean doSsrcRewriting,
            boolean doMidDemux,
            boolean visitor,
            boolean privateAddresses,
            boolean diarize)
    {
        final AbstractEndpoint existingEndpoint = getEndpoint(id);
        if (existingEndpoint != null)
        {
            throw new IllegalArgumentException("Local endpoint with ID = " + id + "already created");
        }

        final Endpoint endpoint = new Endpoint(
                id, this, logger, iceControlling, doSsrcRewriting, doMidDemux, visitor, privateAddresses, diarize);
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

    /**
     * Resolves an audio SSRC to the name of the source it belongs to, or {@code null} if it is not known. Used by the
     * exporter to filter outbound audio by a connect's exported source names.
     */
    @Nullable
    private String getAudioSourceName(long ssrc)
    {
        AbstractEndpoint endpoint = getEndpointBySsrc(ssrc);
        if (endpoint == null)
        {
            return null;
        }
        AudioSourceDesc source = endpoint.getAudioSource(ssrc);
        return source != null ? source.getSourceName() : null;
    }

    /**
     * Resolves an audio SSRC to whether diarization is requested for its owning endpoint (the colibri2 {@code diarize}
     * attribute), defaulting to {@code false} if the SSRC's endpoint is not known. Used by the exporter to set the
     * {@code diarize} flag on the transcriber start event per source.
     */
    private boolean getDiarize(long ssrc)
    {
        AbstractEndpoint endpoint = getEndpointBySsrc(ssrc);
        return endpoint != null && endpoint.getDiarize();
    }

    /**
     * Whether the packet belongs to a synthetic source (bridge-generated audio, e.g. translated audio). Such packets
     * are exempt from echo-suppression in {@link #sendOut}: a synthetic source is delivered purely by audio
     * subscription, including to its owner endpoint, on every bridge.
     */
    private boolean isSyntheticSource(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        return packet instanceof RtpPacket
                && audioSubscriptionManager.isSynthetic(((RtpPacket) packet).getSsrc());
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
        audioSources.forEach(source -> removedSyntheticSources.remove(source.getSourceName()));
    }

    public void removeAudioSources(Set<AudioSourceDesc> audioSources)
    {
        audioSubscriptionManager.removeSources(audioSources);
        audioSources.forEach(source ->
        {
            loggedInjectedSources.remove(source.getSourceName());
            loggedUnknownMediaTags.remove(source.getSourceName());
            if (source.getSynthetic())
            {
                removedSyntheticSources.add(source.getSourceName());
            }
        });
    }

    /**
     * Sets the audio subscription for a given endpoint.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    public void setEndpointAudioSubscription(String endpointId, ReceiverAudioSubscriptionMessage subscription)
    {
        audioSubscriptionManager.setEndpointAudioSubscription(endpointId, subscription);
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

        boolean syntheticSource = isSyntheticSource(packetInfo);
        for (Endpoint endpoint : endpointsCache)
        {
            // Skip echoing a packet back to its source endpoint -- except for synthetic sources, which are
            // bridge-generated (not the endpoint's own live audio) and whose delivery, including to their owner, is
            // governed entirely by audio subscriptions. This holds whether the synthetic audio was injected locally
            // or received over a relay (where the relay attributes the packet to the owner endpoint).
            if (!syntheticSource && endpoint.getId().equals(sourceEndpointId))
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
        for (PotentialPacketHandler exporterHandler : exporter.getPacketHandlers())
        {
            // Never export synthetic (bridge-generated) audio, e.g. don't feed translated audio back to the
            // translator that produced it, or to a transcriber/recorder that exports all audio (empty exports list).
            if (!syntheticSource && exporterHandler.wants(packetInfo))
            {
                if (prevHandler != null)
                {
                    prevHandler.send(packetInfo.clone());
                }
                prevHandler = exporterHandler;
            }
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

    public void applyConnects(List<Connect> connects)
    {
        exporter.applyConnects(connects);
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
     * @param ssrc the SSRC of the source for which the audio level was received
     * @param level the new audio level which was received
     * @return Whether the packet providing this audio level should be dropped, according
     * to the current audio filtering configuration.
     */
    public boolean levelChanged(@NotNull AbstractEndpoint endpoint, long ssrc, long level)
    {
        // Find the specific source this level belongs to. An endpoint may have more than one audio source (e.g. a
        // real source plus a synthetic translated one), so we can't assume a single source per endpoint. The lookup
        // is O(1) (indexed by SSRC) since this runs for every audio packet.
        AudioSourceDesc source = endpoint.getAudioSource(ssrc);
        if (source == null)
        {
            // The per-SSRC index is refreshed just after the endpoint's audioSources list, so a level packet arriving
            // during a source update can miss the index while the list already has it. Fall back to a scan so we
            // still classify the source (its synthetic flag and name gate the decisions below); missing it would
            // wrongly subject a synthetic or explicitly-subscribed source to loudest-only filtering.
            for (AudioSourceDesc s : endpoint.getAudioSources())
            {
                if (s.getSsrc() == ssrc)
                {
                    source = s;
                    break;
                }
            }
        }

        // Synthetic sources (e.g. bridge-generated translated audio) don't participate in speech activity /
        // loudest-speaker selection, and must not be dropped by loudest-only filtering -- they're still forwarded
        // to relays and to endpoints that explicitly subscribe to them.
        if (source != null && source.getSynthetic())
        {
            return false;
        }

        SpeakerRanking ranking = speechActivity.levelChanged(endpoint, level);
        if (ranking == null || !routeLoudestOnly)
            return false;
        if (ranking.isDominant && LoudestConfig.Companion.getAlwaysRouteDominant())
            return false;
        if (ranking.energyRanking < LoudestConfig.Companion.getNumLoudest())
            return false;
        // return false if the source is subscribed with an "Include" subscription by any other endpoint.
        if (source != null && audioSubscriptionManager.isExplicitlySubscribed(source.getSourceName()))
        {
            return false;
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
    public ObjectNode getDebugState(@NotNull DebugStateMode mode, @Nullable String endpointId)
    {
        if (mode == DebugStateMode.STATS && !isRtcStatsEnabled)
        {
            return JsonNodeFactory.instance.objectNode();
        }

        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
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

        if (mode == DebugStateMode.FULL)
        {
            debugState.put("expired", expired.get());
            debugState.put("creation_time", creationTime);
        }
        if (mode == DebugStateMode.FULL || mode == DebugStateMode.STATS)
        {
            debugState.set("speech_activity", speechActivity.getDebugState(mode));
        }

        ObjectNode endpoints = JsonNodeFactory.instance.objectNode();
        debugState.set("endpoints", endpoints);
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
                    endpoints.set(e.getId(), e.debugState(mode));
                }
            }
        }

        ObjectNode relays = JsonNodeFactory.instance.objectNode();
        debugState.set("relays", relays);
        for (Relay r : relaysById.values())
        {
            if (mode == DebugStateMode.SHORT)
            {
                relays.put(r.getId(), r.getId());
            }
            else
            {
                relays.set(r.getId(), r.debugState(mode));
            }
        }

        if (mode != DebugStateMode.SHORT)
        {
            debugState.set("exporter", exporter.debugState());
            debugState.set("audio_subscriptions", audioSubscriptionManager.debugState());
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
     * Handles transcription messages received from the websocket.
     *
     * @param transcriptionMessage the transcription message as JsonNode
     */
    private void handleTranscriptionMessage(TranscriptionResultEvent transcriptionMessage)
    {
        try
        {
            EndpointMessage endpointMessage = new EndpointMessage("");
            endpointMessage.put("msgPayload", transcriptionMessage);
            endpointMessage.put("from", "transcriber");

            broadcastMessage(endpointMessage, true);
        }
        catch (Exception e)
        {
            logger.warn("Failed to broadcast transcription message", e);
        }
    }

    /**
     * Handles a translated-audio "media" event received from a translator over the export websocket. The event's
     * tag names the synthetic source the translated audio belongs to; we build an Opus RTP packet on that source's
     * SSRC and inject it into the conference, where the synthetic-source routing delivers it (only to endpoints that
     * explicitly subscribed to that source, and on to relays).
     */
    private void handleMediaMessage(MediaEvent mediaEvent)
    {
        org.jitsi.mediajson.Media media = mediaEvent.getMedia();
        String sourceName = media.getTag();

        AudioSourceDesc source = findSyntheticAudioSource(sourceName);
        if (source == null)
        {
            if (removedSyntheticSources.contains(sourceName))
            {
                // Expected transient: the translator sent this before it saw our unsubscribe of the source.
                logger.debug(() -> "Dropping translated media for removed synthetic source: " + sourceName);
            }
            else if (loggedUnknownMediaTags.add(sourceName))
            {
                logger.warn("Received translated media for unknown synthetic source: " + sourceName);
            }
            return;
        }

        PayloadType opusPayloadType = getOpusPayloadType();
        if (opusPayloadType == null)
        {
            if (loggedNoOpusPayloadType.compareAndSet(false, true))
            {
                logger.warn("Received translated media but no Opus payload type is negotiated; dropping.");
            }
            return;
        }

        if (loggedInjectedSources.add(sourceName))
        {
            logger.info("Creating injected synthetic source " + sourceName + " (ssrc " + source.getSsrc() + ")");
        }

        try
        {
            byte[] payload = java.util.Base64.getDecoder().decode(media.getPayload());
            int headerSize = RtpHeader.FIXED_HEADER_SIZE_BYTES;
            int offset = RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET;
            // Use a pooled buffer with the standard head and tail room, like other packet-construction sites, so
            // downstream stages (header extensions, the SRTP auth tag) don't need to reallocate and the buffer
            // round-trips through the pool.
            byte[] buffer = ByteBufferPool.getBuffer(
                offset + headerSize + payload.length + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET);
            // The pooled buffer isn't zeroed, and the setters below don't cover every header field (e.g. the
            // padding/extension/CSRC-count and marker bits), so clear the header region first.
            Arrays.fill(buffer, offset, offset + headerSize, (byte) 0);
            System.arraycopy(payload, 0, buffer, offset + headerSize, payload.length);

            AudioRtpPacket rtpPacket = new AudioRtpPacket(buffer, offset, headerSize + payload.length);
            rtpPacket.setVersion(2);
            rtpPacket.setPayloadType(opusPayloadType.getPt());
            rtpPacket.setSequenceNumber(media.getChunk());
            // The translator sends a monotonic, unwrapped timestamp, but the RTP timestamp is a 32-bit wrapping
            // field. setTimestamp() truncates to 32 bits on the wire, but caches the raw Long, so mask here to keep
            // the packet's cached timestamp consistent with its bytes (and with the sending-change notification).
            rtpPacket.setTimestamp(media.getTimestamp() & 0xFFFFFFFFL);
            rtpPacket.setSsrc(source.getSsrc());

            PacketInfo packetInfo = new PacketInfo(rtpPacket);
            packetInfo.setPayloadType(opusPayloadType);
            // Deliberately leave the endpoint ID unset: this is bridge-generated audio, not an endpoint's own live
            // audio, so there is no self-echo to suppress. Delivery (including to the source's owner) is governed
            // entirely by the audio subscriptions, so an endpoint can subscribe to a synthetic source of its own.

            handleIncomingPacket(packetInfo);
        }
        catch (Exception e)
        {
            logger.warn("Failed to handle translated media for source " + sourceName, e);
        }
    }

    /**
     * Handles a synthetic source's sending-state change, derived from the {@code start}/{@code stop} mediajson events
     * a translator sends to bracket a "talk" of translated audio. Resolves the named synthetic source (dropping the
     * change if it isn't a known synthetic source, like {@link #handleMediaMessage}) and broadcasts a
     * {@link SyntheticSourceSendingChangeEvent} to the conference's clients (and relays).
     *
     * @param sourceName the synthetic source whose sending state changed
     * @param sending    true if the source started sending, false if it stopped
     * @param timestamp  the RTP timestamp (media clock) at which the change takes effect, on the same timeline as the
     *                   source's media; the low 32 bits are used (RTP timestamps are 32-bit and wrap)
     */
    private void handleSyntheticSourceSendingChange(String sourceName, boolean sending, long timestamp)
    {
        AudioSourceDesc source = findSyntheticAudioSource(sourceName);
        if (source == null)
        {
            if (removedSyntheticSources.contains(sourceName))
            {
                // Expected transient: the translator sent this before it saw our unsubscribe of the source.
                logger.debug(() -> "Dropping sending-change for removed synthetic source: " + sourceName);
            }
            else if (loggedUnknownMediaTags.add(sourceName))
            {
                logger.warn("Received sending-change for unknown synthetic source: " + sourceName);
            }
            return;
        }

        try
        {
            // The translator sends a monotonic, unwrapped timestamp; the RTP timestamp is 32-bit and wraps, and the
            // client (lib-jitsi-meet) validates 0..0xFFFFFFFF, so send the low 32 bits. This also matches the
            // wrapped timestamp the client sees on this source's injected media.
            long rtpTimestamp = timestamp & 0xFFFFFFFFL;
            broadcastMessage(new SyntheticSourceSendingChangeEvent(sourceName, sending, rtpTimestamp), true);
        }
        catch (Exception e)
        {
            logger.warn("Failed to broadcast synthetic source sending change for " + sourceName, e);
        }
    }

    /**
     * Finds the synthetic audio source with the given name, or {@code null} if there is none.
     */
    @Nullable
    private AudioSourceDesc findSyntheticAudioSource(String sourceName)
    {
        // Resolve via the subscription manager, which owns the authoritative set of sources under the same lock that
        // guards the synthetic-SSRC set used at routing time. Scanning each endpoint's source field here instead
        // would read state that is updated out of step with the manager (e.g. a local endpoint updates the manager
        // before its field, a relayed endpoint after), so a just-added synthetic source could be missed.
        return audioSubscriptionManager.findSyntheticSource(sourceName);
    }

    /**
     * Returns the Opus payload type negotiated in this conference, or {@code null} if none is found. Payload types
     * are never rewritten, so the Opus PT is consistent across all endpoints; the first one found is cached, since
     * this runs per injected media event.
     */
    @Nullable
    private PayloadType getOpusPayloadType()
    {
        PayloadType cached = opusPayloadType;
        if (cached != null)
        {
            return cached;
        }
        for (Endpoint endpoint : getLocalEndpoints())
        {
            PayloadType payloadType = endpoint.getOpusPayloadType();
            if (payloadType != null)
            {
                opusPayloadType = payloadType;
                return payloadType;
            }
        }
        return null;
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
