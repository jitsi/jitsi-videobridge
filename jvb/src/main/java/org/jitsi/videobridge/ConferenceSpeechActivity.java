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
import org.jitsi.nlj.VideoType;
import org.jitsi.utils.dsi.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.util.*;
import java.util.stream.*;

/**
 * Represents the speech activity of the <tt>Endpoint</tt>s in a
 * <tt>Conference</tt>. Identifies the dominant speaker <tt>Endpoint</tt> in the
 * <tt>Conference</tt> and maintains an ordered list of the <tt>Endpoint</tt>s
 * in the <tt>Conference</tt> sorted by recentness of speaker domination and/or
 * speech activity.
 *
 * @author Lyubomir Marinov
 */
public class ConferenceSpeechActivity
{
    /**
     * The <tt>Logger</tt> used by the <tt>ConferenceSpeechActivity</tt> class
     * and its instances to print debug information.
     */
    private final Logger logger;

    /**
     * The <tt>ActiveSpeakerChangedListener</tt> which listens to
     * {@link #dominantSpeakerIdentification} about changes in the
     * active/dominant speaker in this multipoint conference.
     */
    private final ActiveSpeakerChangedListener<String> activeSpeakerChangedListener
        = ConferenceSpeechActivity.this::activeSpeakerChanged;

    /**
     * The <tt>DominantSpeakerIdentification</tt> instance which detects/identifies the active/dominant speaker in a
     * conference.
     */
    private DominantSpeakerIdentification<String> dominantSpeakerIdentification
            = new DominantSpeakerIdentification<>();

    /**
     * The listener to be notified when the dominant speaker or endpoint order changes.
     */
    private Listener listener;

    /**
     * The list of endpoints ordered by speech activity alone with the dominant speaker at the beginning of the list
     * i.e. the dominant speaker history. This contains all endpoints in the conference, including those that have
     * never been the dominant speaker. Such endpoints are listed after endpoints which have been the dominant speaker,
     * but the order among themselves is not specified.
     * This is used internally when the list of all endpoints is required (e.g. for bandwidth allocation decisions).
     */
    private final List<AbstractEndpoint> endpointsBySpeechActivity = new ArrayList<>();

    /**
     * The list of endpoints in "LastN" order. That is, endpoints currently sending video are at the top of the list,
     * ordered by speech activity, followed by the rest of the endpoints (again in speech activity order).
     */
    private @NotNull List<AbstractEndpoint> endpointsInLastNOrder = new ArrayList<>();

    /**
     * The list of endpoints which have been "dominant speaker" ordered by speech activity (with the current dominant
     * speaker at the beginning). This differs from {@link #endpointsBySpeechActivity} in that it does not necessarily
     * contain all endpoints in the conference.
     *
     * This is used when signaling "recent speakers" to endpoints.
     *
     * We add 1 to the configured number of recent speakers to account for the dominant speaker.
     */
    @NotNull
    private final RecentSpeakersList<AbstractEndpoint> recentSpeakers
            = new RecentSpeakersList<>(ConferenceSpeechActivityConfig.getConfig().getRecentSpeakersCount() + 1);

    /**
     * The <tt>Object</tt> used to synchronize the access to the state of this
     * instance.
     */
    private final Object syncRoot = new Object();

    public ConferenceSpeechActivity(@NotNull Listener listener)
    {
        this(listener, null);
    }

    /**
     * Initializes a new <tt>ConferenceSpeechActivity</tt> instance.
     *
     * @param listener the listener to be notified when the dominant speaker or endpoint order change.
     */
    public ConferenceSpeechActivity(@NotNull Listener listener, Logger parentLogger)
    {
        this.listener = Objects.requireNonNull(listener, "listener");
        logger =
                parentLogger == null ?
                        new LoggerImpl(ConferenceSpeechActivity.class.getName()) :
                        parentLogger.createChildLogger(ConferenceSpeechActivity.class.getName());

        dominantSpeakerIdentification.addActiveSpeakerChangedListener(activeSpeakerChangedListener);
        int numLoudestToTrack = LoudestConfig.Companion.getRouteLoudestOnly() ?
                LoudestConfig.Companion.getNumLoudest() : 0;
        dominantSpeakerIdentification.setLoudestConfig(numLoudestToTrack,
                (int)(LoudestConfig.Companion.getEnergyExpireTime().toMillis()),
                LoudestConfig.Companion.getEnergyAlphaPct());
    }

    /**
     * Notifies this instance that the underlying {@code dominant speaker identification} has elected a new
     * active/dominant speaker.
     *
     * @param id the ID of the new active/dominant speaker.
     */
    protected void activeSpeakerChanged(@NotNull String id)
    {
        final Listener listener = this.listener;
        if (listener == null)
        {
            return;
        }

        Objects.requireNonNull(id);
        logger.trace(() -> "The dominant speaker is now " + id + ".");

        boolean endpointListChanged;
        synchronized (syncRoot)
        {
            AbstractEndpoint endpoint
                    = endpointsBySpeechActivity.stream()
                        .filter(e -> id.equals(e.getId()))
                        .findFirst().orElse(null);
            // Move this endpoint to the top of our sorted list
            if (!endpointsBySpeechActivity.remove(endpoint))
            {
                logger.warn("Got active speaker notification for an unknown endpoint: " + id + ", ignoring");
                return;
            }
            endpointsBySpeechActivity.add(0, endpoint);

            recentSpeakers.promote(endpoint);

            endpointListChanged = updateLastNEndpoints();
        }

        TaskPools.IO_POOL.submit(() -> {
            listener.recentSpeakersChanged(recentSpeakers.getRecentSpeakers(), true);
            if (endpointListChanged)
            {
                listener.lastNEndpointsChanged();
            }
        });
    }

    /**
     * Re-calculates the list of endpoints in LastN order ({@link #endpointsInLastNOrder}) based on the speech activity
     * and video availability.
     *
     * @return {@code true} if the call resulted in a change of the ordered list of endpoints, and {@code false}
     * otherwise.
     */
    boolean updateLastNEndpoints()
    {
        synchronized (syncRoot)
        {
            Map<Boolean, List<AbstractEndpoint>> byVideoAvailable
                    = endpointsBySpeechActivity.stream()
                        .collect(Collectors.groupingBy(ep -> ep.getVideoType() != VideoType.NONE));

            List<AbstractEndpoint> newEndpointsInLastNOrder = new ArrayList<>(endpointsBySpeechActivity.size());
            newEndpointsInLastNOrder.addAll(byVideoAvailable.getOrDefault(true, Collections.emptyList()));
            newEndpointsInLastNOrder.addAll(byVideoAvailable.getOrDefault(false, Collections.emptyList()));

            if (!newEndpointsInLastNOrder.equals(endpointsInLastNOrder))
            {
                endpointsInLastNOrder = Collections.unmodifiableList(newEndpointsInLastNOrder);
                return true;
            }

            return false;
        }
    }

    void expire()
    {
        synchronized (syncRoot)
        {
            if (dominantSpeakerIdentification != null)
            {
                dominantSpeakerIdentification.removeActiveSpeakerChangedListener(activeSpeakerChangedListener);
            }
            this.listener = null;
            this.dominantSpeakerIdentification = null;
            endpointsBySpeechActivity.clear();
            endpointsInLastNOrder = Collections.emptyList();
        }
    }

    /**
     * Gets the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance.
     *
     * @return the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance or <tt>null</tt>
     */
    public AbstractEndpoint getDominantEndpoint()
    {
        synchronized (syncRoot)
        {
            return recentSpeakers.getRecentSpeakers().stream().findFirst().orElse(null);
        }
    }

    /**
     * Gets a read-only list of endpoints in "LastN" order.
     *
     * @return a read-only list of endpoints in "LastN" order.
     */
    public List<AbstractEndpoint> getOrderedEndpoints()
    {
        return endpointsInLastNOrder;
    }

    /**
     * Get a list of recent speakers (including the current dominant one at the top of the list).
     */
    @NotNull
    public List<String> getRecentSpeakers()
    {
        return recentSpeakers.getRecentSpeakers().stream()
                .map(AbstractEndpoint::getId).collect(Collectors.toList());
    }

    /**
     * Query whether an endpoint is a recent speaker.
     */
    public boolean isRecentSpeaker(AbstractEndpoint endpoint)
    {
        return recentSpeakers.isRecentSpeaker(endpoint);
    }

    public DominantSpeakerIdentification<String>.SpeakerRanking getRanking(String endpointId)
    {
        return dominantSpeakerIdentification.getRanking(endpointId);
    }

    /**
     * Notifies this instance that a new audio level was received or measured by an <tt>Endpoint</tt>.
     *
     * @param endpoint the endpoint for which a new audio level was received or measured
     * @param level the new audio level which was received or measured
     */
    public void levelChanged(@NotNull AbstractEndpoint endpoint, long level)
    {
        DominantSpeakerIdentification<String> dsi = this.dominantSpeakerIdentification;
        if (dsi != null)
        {
            dominantSpeakerIdentification.levelChanged(endpoint.getId(), (int) level);
        }
    }

    /**
     * Notifies this instance that the list of endpoints changed.
     */
    public void endpointsChanged(List<AbstractEndpoint> conferenceEndpoints)
    {
        boolean endpointsListChanged;
        boolean dominantSpeakerChanged;
        boolean recentSpeakersChanged;
        // The list of endpoints may have changed, sync our list to make sure it matches.
        synchronized (syncRoot)
        {
            // Remove any endpoints we have that are no longer in the conference
            AbstractEndpoint previousDominantSpeaker
                    = endpointsBySpeechActivity.isEmpty() ? null : endpointsBySpeechActivity.get(0);
            endpointsListChanged = endpointsBySpeechActivity.removeIf(ep -> !conferenceEndpoints.contains(ep));
            recentSpeakersChanged = recentSpeakers.removeAllExcept(conferenceEndpoints);
            // Add any endpoints from the conf we don't have to the end of our list
            for (AbstractEndpoint conferenceEndpoint : conferenceEndpoints)
            {
                if (!endpointsBySpeechActivity.contains(conferenceEndpoint))
                {
                    endpointsBySpeechActivity.add(conferenceEndpoint);
                    endpointsListChanged = true;
                }
            }
            AbstractEndpoint newDominantSpeaker
                    = endpointsBySpeechActivity.isEmpty() ? null : endpointsBySpeechActivity.get(0);
            dominantSpeakerChanged = !Objects.equals(previousDominantSpeaker, newDominantSpeaker);
            recentSpeakersChanged |= dominantSpeakerChanged;

            if (endpointsListChanged)
            {
                endpointsListChanged = updateLastNEndpoints();
            }
        }

        if (recentSpeakersChanged || endpointsListChanged)
        {
            final boolean finalRecentSpeakersChanged = recentSpeakersChanged;
            final boolean finalEndpointsChanged = endpointsListChanged;
            final Listener listener = this.listener;
            if (listener == null)
            {
                return;
            }
            TaskPools.IO_POOL.submit(() -> {
                if (finalRecentSpeakersChanged)
                {
                    listener.recentSpeakersChanged(recentSpeakers.getRecentSpeakers(), dominantSpeakerChanged);
                }
                if (finalEndpointsChanged)
                {
                    listener.lastNEndpointsChanged();
                }
            });
        }
    }

    public void endpointVideoAvailabilityChanged()
    {
        boolean endpointsListChanged;
        synchronized (syncRoot)
        {
            endpointsListChanged = updateLastNEndpoints();
        }
        if (endpointsListChanged)
        {
            TaskPools.IO_POOL.submit(() -> {
                try
                {
                    listener.lastNEndpointsChanged();
                }
                catch (Throwable e)
                {
                    logger.warn("Failed to fire event", e);
                }
            });
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();

        AbstractEndpoint dominantEndpoint = getDominantEndpoint();
        debugState.put("dominantEndpoint", dominantEndpoint == null ? "null" : dominantEndpoint.getId());
        DominantSpeakerIdentification<String> dsi = this.dominantSpeakerIdentification;
        debugState.put("dominantSpeakerIdentification", dsi == null ? null : dsi.doGetJSON());
        synchronized (syncRoot)
        {
            debugState.put(
                    "endpointsBySpeechActivity",
                    endpointsBySpeechActivity.stream().map(AbstractEndpoint::getId).collect(Collectors.toList()));
            debugState.put(
                    "endpointsInLastNOrder",
                    endpointsInLastNOrder.stream().map(AbstractEndpoint::getId).collect(Collectors.toList()));
        }

        return debugState;
    }

    interface Listener
    {
        /**
         * The list of recent speakers changed (either because a new dominant speaker was promoted, or because an
         * endpoint was removed).
         * @param recentSpeakers the new list of recent speakers (including the dominant speaker at index 0).
         * @param dominantSpeakerChanged whether the dominant speaker changed.
         */
        void recentSpeakersChanged(List<AbstractEndpoint> recentSpeakers, boolean dominantSpeakerChanged);
        void lastNEndpointsChanged();
    }
}
