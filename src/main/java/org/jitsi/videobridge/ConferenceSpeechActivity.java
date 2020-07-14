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
import org.jitsi.utils.dsi.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.util.*;

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
    private final ActiveSpeakerChangedListener activeSpeakerChangedListener
        = ConferenceSpeechActivity.this::activeSpeakerChanged;

    /**
     * The <tt>DominantSpeakerIdentification</tt> instance which
     * detects/identifies the active/dominant speaker in {@link #conference}.
     */
    private DominantSpeakerIdentification dominantSpeakerIdentification
            = new DominantSpeakerIdentification();

    /**
     * The listener to be notified when the dominant speaker or endpoint order changes.
     */
    private Listener listener;

    /**
     * The ordered list of <tt>Endpoint</tt>s participating in
     * {@link #conference} with the dominant (speaker) <tt>Endpoint</tt> at the
     * beginning of the list i.e. the dominant speaker history.
     */
    private final List<String> endpoints = new ArrayList<>();

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
     * @param listener the listener to be notified when the dominant speaker or enpoint order change.
     * represented by the new instance
     */
    public ConferenceSpeechActivity(@NotNull Listener listener, Logger parentLogger)
    {
        this.listener = Objects.requireNonNull(listener, "conference");
        logger =
                parentLogger == null ?
                        new LoggerImpl(ConferenceSpeechActivity.class.getName()) :
                        parentLogger.createChildLogger(ConferenceSpeechActivity.class.getName());

        dominantSpeakerIdentification.addActiveSpeakerChangedListener(activeSpeakerChangedListener);
    }

    /**
     * Notifies this instance that the underlying {@code dominant speaker identification} has elected a new
     * active/dominant speaker.
     *
     * @param id the ID of the new active/dominant speaker.
     */
    protected void activeSpeakerChanged(Object id)
    {
        final Listener listener = this.listener;
        if (listener == null)
        {
            return;
        }

        if (!(id instanceof String))
        {
            throw new IllegalStateException("Invalid speaker ID: " + id);
        }
        String endpoint = (String) id;

        logger.trace(() -> "The dominant speaker is now " + id + ".");

        synchronized (syncRoot)
        {
            // Move this endpoint to the top of our sorted list
            if (!endpoints.remove(endpoint))
            {
                logger.warn("Got active speaker notification for an unknown endpoint: " + endpoint + ", ignoring");
                return;
            }
            endpoints.add(0, endpoint);

            TaskPools.IO_POOL.submit(listener::dominantSpeakerChanged);
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
        }
    }

    /**
     * Gets the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance.
     *
     * @return the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance or <tt>null</tt>
     */
    public String getDominantEndpoint()
    {
        synchronized (syncRoot)
        {
            return endpoints.isEmpty() ? null : endpoints.get(0);
        }
    }

    /**
     * Gets the ordered list of <tt>Endpoint</tt>s participating in the
     * multipoint conference represented by this instance with the dominant
     * (speaker) <tt>Endpoint</tt> at the beginning of the list i.e. the
     * dominant speaker history.
     *
     * @return the ordered list of <tt>Endpoint</tt>s participating in the
     * multipoint conference represented by this instance with the dominant
     * (speaker) <tt>Endpoint</tt> at the beginning of the list
     */
    public List<String> getEndpoints()
    {
        synchronized (syncRoot)
        {
            return new LinkedList<>(endpoints);
        }
    }

    /**
     * Notifies this instance that a new audio level was received or measured by an <tt>Endpoint</tt>.
     *
     * @param endpointId the ID of he endpoint for which a new audio level was received or measured
     * @param level the new audio level which was received or measured
     */
    public void levelChanged(String endpointId, long level)
    {
        DominantSpeakerIdentification dsi = this.dominantSpeakerIdentification;
        if (dsi != null)
        {
            dominantSpeakerIdentification.levelChanged(endpointId, (int) level);
        }
    }

    /**
     * Notifies this instance that the list of endpoints changed.
     */
    public void endpointsChanged(List<String> conferenceEndpoints)
    {
        boolean endpointsListChanged = false;
        boolean dominantSpeakerChanged = false;
        // The list of endpoints may have changed, sync our list to make
        // sure it matches.
        synchronized (syncRoot)
        {
            // Remove any endpoints we have that are no longer in the conference
            String previousDominantSpeaker = endpoints.isEmpty() ? null : endpoints.get(0);
            endpointsListChanged = endpoints.removeIf(ep -> !conferenceEndpoints.contains(ep));
            // Add any endpoints from the conf we don't have to the end of our list
            for (String conferenceEndpoint : conferenceEndpoints)
            {
                if (!endpoints.contains(conferenceEndpoint))
                {
                    endpoints.add(conferenceEndpoint);
                    endpointsListChanged = true;
                }
            }
            String newDominantSpeaker = endpoints.isEmpty() ? null : endpoints.get(0);
            dominantSpeakerChanged = !Objects.equals(previousDominantSpeaker, newDominantSpeaker);
        }

        if (dominantSpeakerChanged || endpointsListChanged)
        {
            final boolean finalDominantSpeakerChanged = dominantSpeakerChanged;
            final boolean finalEndpointsChanged = endpointsListChanged;
            final Listener listener = this.listener;
            if (listener == null)
            {
                return;
            }
            TaskPools.IO_POOL.submit(() -> {
                if (finalDominantSpeakerChanged)
                {
                    listener.dominantSpeakerChanged();
                }
                // Dominant speaker changed implies that the list changed.
                if (finalEndpointsChanged && !finalDominantSpeakerChanged)
                {
                    listener.speechActivityEndpointsChanged();
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

        String dominantEndpoint = getDominantEndpoint();
        debugState.put("dominantEndpoint", dominantEndpoint);
        DominantSpeakerIdentification dsi = this.dominantSpeakerIdentification;
        debugState.put(
                "dominantSpeakerIdentification",
                dsi == null ? null : dsi.doGetJSON());

        return debugState;
    }

    interface Listener
    {
        void dominantSpeakerChanged();
        void speechActivityEndpointsChanged();
    }
}
