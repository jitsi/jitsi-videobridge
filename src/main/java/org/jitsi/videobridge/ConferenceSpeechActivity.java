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
     * The <tt>Conference</tt> for which this instance represents the speech
     * activity of its <tt>Endpoint</tt>s. The reference will be set to
     * <tt>null</tt> once the <tt>Conference</tt> gets expired.
     * <tt>ConferenceSpeechActivity</tt> is a part of <tt>Conference</tt> and
     * the operation of the former in the absence of the latter is useless.
     */
    private Conference conference;

    /**
     * The ordered list of <tt>Endpoint</tt>s participating in
     * {@link #conference} with the dominant (speaker) <tt>Endpoint</tt> at the
     * beginning of the list i.e. the dominant speaker history.
     */
    private final List<AbstractEndpoint> endpoints = new ArrayList<>();

    /**
     * The <tt>Object</tt> used to synchronize the access to the state of this
     * instance.
     */
    private final Object syncRoot = new Object();

    /**
     * Initializes a new <tt>ConferenceSpeechActivity</tt> instance which is to
     * represent the speech activity in a specific <tt>Conference</tt>.
     *
     * @param conference the <tt>Conference</tt> whose speech activity is to be
     * represented by the new instance
     */
    public ConferenceSpeechActivity(Conference conference)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        logger = conference.getLogger().createChildLogger(ConferenceSpeechActivity.class.getName());

        dominantSpeakerIdentification
                .addActiveSpeakerChangedListener(activeSpeakerChangedListener);
    }

    /**
     * Notifies this multipoint conference that the active/dominant speaker has
     * changed to one identified by a specific synchronization source
     * identifier/SSRC.
     * 
     * @param id the synchronization source identifier/SSRC of the new
     * active/dominant speaker
     */
    private void activeSpeakerChanged(Object id)
    {
        Conference conference = this.conference;
        if (conference == null)
        {
            return;
        }

        if (id != null && !(id instanceof String))
        {
            throw new IllegalStateException("Invalid speaker ID: " + id);
        }

        logger.trace(() -> "The dominant speaker in conference " + conference.getID() + " is now " + id + ".");

        AbstractEndpoint endpoint = conference.getEndpoint((String) id);
        if (endpoint == null)
        {
            logger.warn("Unable to find endpoint with id " + id);
            return;
        }

        synchronized (syncRoot)
        {
            // Move this endpoint to the top of our sorted list
            if (!endpoints.remove(endpoint))
            {
                logger.warn("Got active speaker notification for an unknown"
                        + " endpoint (ssrc: " + id + ", epId "
                        + endpoint.getID() + ")! Ignoring");
                return;
            }
            endpoints.add(0, endpoint);

            TaskPools.IO_POOL.submit(() ->
            {
                if (conference != null)
                {
                    conference.dominantSpeakerChanged();
                }
            });
        }
    }

    void expire()
    {
        synchronized (syncRoot)
        {
            if (dominantSpeakerIdentification != null)
            {
                dominantSpeakerIdentification
                        .removeActiveSpeakerChangedListener(
                                activeSpeakerChangedListener);
            }
            this.conference = null;
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
    public AbstractEndpoint getDominantEndpoint()
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
    public List<AbstractEndpoint> getEndpoints()
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
     * Notifies this instance that the
     */
    public void endpointsChanged()
    {
        boolean endpointsListChanged = false;
        boolean dominantSpeakerChanged = false;
        // The list of endpoints may have changed, sync our list to make
        // sure it matches.
        List<AbstractEndpoint> conferenceEndpointsCopy
                = conference.getEndpoints();
        synchronized (syncRoot)
        {
            // Remove any endpoints we have that are no longer in the
            // conference
            String previousDominantSpeaker
                = endpoints.isEmpty() ? null : endpoints.get(0).getID();
            endpointsListChanged
                = endpoints.removeIf(
                        ep -> !conferenceEndpointsCopy.contains(ep));
            // Add any endpoints from the conf we don't have to the end
            // of our list
            for (AbstractEndpoint ep : conferenceEndpointsCopy)
            {
                if (!endpoints.contains(ep))
                {
                    endpoints.add(ep);
                    endpointsListChanged = true;
                }
            }
            String newDominantSpeaker
                = endpoints.isEmpty() ? null : endpoints.get(0).getID();
            dominantSpeakerChanged
                = !Objects.equals(previousDominantSpeaker, newDominantSpeaker);
        }

        if (dominantSpeakerChanged || endpointsListChanged)
        {
            final boolean finalDominantSpeakerChanged = dominantSpeakerChanged;
            final boolean finalEndpointsChanged = endpointsListChanged;
            TaskPools.IO_POOL.submit(() -> {
                final Conference conference = this.conference;
                if (conference == null)
                {
                    return;
                }
                if (finalDominantSpeakerChanged)
                {
                    conference.dominantSpeakerChanged();
                }
                // Dominant speaker changed implies that the list changed.
                if (finalEndpointsChanged && !finalDominantSpeakerChanged)
                {
                    conference.speechActivityEndpointsChanged();
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
        debugState.put(
                "dominantEndpoint",
                dominantEndpoint == null ? null : dominantEndpoint.getID());
        DominantSpeakerIdentification dsi = this.dominantSpeakerIdentification;
        debugState.put(
                "dominantSpeakerIdentification",
                dsi == null ? null : dsi.doGetJSON());

        return debugState;
    }
}
