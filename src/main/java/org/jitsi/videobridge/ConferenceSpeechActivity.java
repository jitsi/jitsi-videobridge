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

import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.beans.*;
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
    extends PropertyChangeNotifier
    implements PropertyChangeListener
{
    /**
     * The name of the <tt>ConferenceSpeechActivity</tt> property
     * <tt>dominantEndpoint</tt> which identifies the dominant speaker in a
     * multipoint conference.
     */
    public static final String DOMINANT_ENDPOINT_PROPERTY_NAME
        = ConferenceSpeechActivity.class.getName() + ".dominantEndpoint";

    /**
     * The name of the <tt>ConferenceSpeechActivity</tt> property
     * <tt>endpoints</tt> which lists the <tt>Endpoint</tt>s
     * participating in/contributing to a <tt>Conference</tt>.
     */
    public static final String ENDPOINTS_PROPERTY_NAME
        = ConferenceSpeechActivity.class.getName() + ".endpoints";

    private static final Logger classLogger
            = Logger.getLogger(ConferenceSpeechActivity.class);
    /**
     * The <tt>Logger</tt> used by the <tt>ConferenceSpeechActivity</tt> class
     * and its instances to print debug information.
     */
    private final Logger logger;

    /**
     * Parses an <tt>Object</tt> as a synchronization source identifier (SSRC).
     *
     * @param obj the <tt>Object</tt> to parse as an SSRC
     * @return the SSRC represented by <tt>obj</tt> or <tt>-1</tt> if
     * <tt>obj</tt> could not be parsed as an SSRC
     */
    private static long parseSSRC(Object obj)
    {
        long l;

        if (obj == null)
        {
            l = -1L;
        }
        else if (obj instanceof Number)
        {
            l = ((Number) obj).longValue();
        }
        else
        {
            String s = obj.toString();

            if (s == null)
            {
                l = -1L;
            }
            else
            {
                try
                {
                    l = Long.parseLong(s);
                }
                catch (NumberFormatException ex)
                {
                    l = -1L;
                }
            }
        }
        return l;
    }

    /**
     * Resolves a synchronization source identifier (SSRC) of a received RTP
     * stream as an <tt>Endpoint</tt> identifier (ID).
     *
     * @param jsonObject the <tt>JSONObject</tt> from which the SSRC is to be
     * read and into which the <tt>Endpoint</tt> ID is to be written
     * @param ssrcKey the key in <tt>jsonObject</tt> with which the SSRC to be
     * resolved is associated
     * @param conference
     * @param endpointKey the key in <tt>jsonObject</tt> with which the resolved
     * <tt>Endpoint</tt> ID is to be associated
     */
    @SuppressWarnings("unchecked")
    private static void resolveSSRCAsEndpoint(
            JSONObject jsonObject,
            String ssrcKey,
            Conference conference,
            String endpointKey)
    {
        long ssrc = parseSSRC(jsonObject.get(ssrcKey));

        if (ssrc != -1)
        {
            AbstractEndpoint endpoint
                = conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);

            if (endpoint != null)
            {
                jsonObject.put(endpointKey, endpoint.getID());
            }
        }
    }

    /**
     * The <tt>ActiveSpeakerChangedListener</tt> which listens to
     * {@link #activeSpeakerDetector} about changes in the active/dominant
     * speaker in this multipoint conference.
     */
    private final ActiveSpeakerChangedListener activeSpeakerChangedListener
        = ConferenceSpeechActivity.this::activeSpeakerChanged;

    /**
     * The <tt>ActiveSpeakerDetector</tt> which detects/identifies the
     * active/dominant speaker in {@link #conference}.
     */
    private final ActiveSpeakerDetector activeSpeakerDetector =
            new ActiveSpeakerDetectorImpl();

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
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to {@link #conference} in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s.
     */
    private final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

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
        logger = Logger.getLogger(classLogger, conference.getLogger());

         // The PropertyChangeListener will weakly reference this instance and
         // will unregister itself from the conference sooner or later.
        conference.addPropertyChangeListener(propertyChangeListener);
        activeSpeakerDetector
                .addActiveSpeakerChangedListener(activeSpeakerChangedListener);
    }

    /**
     * Notifies this multipoint conference that the active/dominant speaker has
     * changed to one identified by a specific synchronization source
     * identifier/SSRC.
     * 
     * @param ssrc the synchronization source identifier/SSRC of the new
     * active/dominant speaker
     */
    private void activeSpeakerChanged(long ssrc)
    {
        Conference conference = getConference();

        if (conference != null)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace(
                        "The dominant speaker in conference "
                            + conference.getID() + " is now the SSRC " + ssrc
                            + ".");
            }

            AbstractEndpoint endpoint
                = conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);

            synchronized (syncRoot)
            {
                // Move this endpoint to the top of our sorted list
                if (!endpoints.remove(endpoint))
                {
                    logger.warn("Got active speaker notification for an unknown"
                            + " endpoint! Ignoring");
                    return;
                }
                endpoints.add(0, endpoint);
                postPropertyChange(
                        DOMINANT_ENDPOINT_PROPERTY_NAME,
                        null, null);
            }
        }
    }

    /**
     * Retrieves a JSON representation of the dominant speaker
     * for the purposes of the REST API of Videobridge.
     *
     * @return a <tt>JSONObject</tt> which represents
     * <tt>dominantSpeakerIdentification</tt> for the purposes of the REST API
     * of Videobridge
     */
    public JSONObject doGetDominantSpeakerIdentificationJSON()
    {
        DominantSpeakerIdentification dominantSpeakerIdentification
            = getDominantSpeakerIdentification();
        JSONObject jsonObject;

        if (dominantSpeakerIdentification == null)
        {
            // We do not know how to represent ActiveSpeakerDetector at the time
            // of this writing, we know how to represent
            // DominantSpeakerIdentification only.
            jsonObject = null;
        }
        else
        {
            Conference conference = getConference();

            if (conference == null)
            {
                jsonObject = null;
            }
            else
            {
                jsonObject = dominantSpeakerIdentification.doGetJSON();
                if (jsonObject != null)
                {
                    // Resolve the dominantSpeaker of
                    // DominantSpeakerIdentification which is a synchronization
                    // source identifier (SSRC) as an Endpoint.
                    resolveSSRCAsEndpoint(
                            jsonObject,
                            "dominantSpeaker",
                            conference,
                            "dominantEndpoint");

                    // Resolve the ssrc of each one of the speakers of
                    // DominantSpeakerIdentification as an Endpoint.
                    Object speakers = jsonObject.get("speakers");

                    if (speakers != null)
                    {
                        if (speakers instanceof JSONObject[])
                        {
                            for (JSONObject speaker : (JSONObject[]) speakers)
                            {
                                resolveSSRCAsEndpoint(
                                        speaker,
                                        "ssrc",
                                        conference,
                                        "endpoint");
                            }
                        }
                        else if (speakers instanceof JSONArray)
                        {
                            for (Object speaker : (JSONArray) speakers)
                            {
                                if (speaker instanceof JSONObject)
                                {
                                    resolveSSRCAsEndpoint(
                                            (JSONObject) speaker,
                                            "ssrc",
                                            conference,
                                            "endpoint");
                                }
                            }
                        }
                    }
                }
            }
        }
        return jsonObject;
    }

    /**
     * Gets the <tt>Conference</tt> whose speech activity is represented by this
     * instance.
     *
     * @return the <tt>Conference</tt> whose speech activity is represented by
     * this instance or <tt>null</tt> if the <tt>Conference</tt> has expired.
     */
    private Conference getConference()
    {
        Conference conference = this.conference;

        //TODO(brian): remove this and just have the conference shut this down when it
        // expires
        if ((conference != null) && conference.isExpired())
        {
            this.conference = conference = null;

            /*
             * The Conference has expired so there is no point to listen to
             * ActiveSpeakerDetector. Remove the activeSpeakerChangedListener
             * for the purposes of completeness, not because it is strictly
             * necessary.
             */
            ActiveSpeakerDetector activeSpeakerDetector
                = this.activeSpeakerDetector;

            activeSpeakerDetector.removeActiveSpeakerChangedListener(
                    activeSpeakerChangedListener);

            DominantSpeakerIdentification dominantSpeakerIdentification
                = getDominantSpeakerIdentification();

            if (dominantSpeakerIdentification != null)
            {
                dominantSpeakerIdentification.removePropertyChangeListener(
                        propertyChangeListener);
            }
        }

        return conference;
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
     * Gets the <tt>DominantSpeakerIdentification</tt> instance, if any,
     * employed by {@link #activeSpeakerDetector}.
     *
     * @return the <tt>DominantSpeakerIdentification</tt> instance, if any,
     * employed by <tt>activeSpeakerDetector</tt>
     */
    private DominantSpeakerIdentification getDominantSpeakerIdentification()
    {
        if (activeSpeakerDetector instanceof ActiveSpeakerDetectorImpl)
        {
            ActiveSpeakerDetectorImpl asdi
                    = (ActiveSpeakerDetectorImpl)activeSpeakerDetector;
            if (asdi.getImpl() instanceof DominantSpeakerIdentification)
            {
                return (DominantSpeakerIdentification)asdi.getImpl();
            }
        }
        return null;
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
            //TODO(brian): make a copy?
            return endpoints;
        }
    }

    /**
     * Notifies this instance that a new audio level was received or measured by
     * an <tt>Endpoint</tt> for an RTP stream with a specific synchronization
     * source identifier/SSRC.
     *
     * @param ssrc the synchronization source identifier/SSRC of the RTP stream
     * for which a new audio level was received or measured by the specified
     * <tt>channel</tt>
     * @param level the new audio level which was received or measured by the
     * specified <tt>channel</tt> for the RTP stream with the specified
     * <tt>ssrc</tt> 
     */
    public void levelChanged(long ssrc, int level)
    {
        activeSpeakerDetector.levelChanged(ssrc, level);
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
        // Cease to execute as soon as the Conference expires.
        Conference conference = getConference();

        if (conference == null)
        {
            return;
        }

        String propertyName = ev.getPropertyName();

        if (Conference.ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            if (conference.equals(ev.getSource()))
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
                        = !Objects.equals(
                                previousDominantSpeaker, newDominantSpeaker);
                }
                if (dominantSpeakerChanged)
                {
                    // This implies that the list of endpoints changed, too.
                    postPropertyChange(
                            DOMINANT_ENDPOINT_PROPERTY_NAME, null, null);
                }
                else if (endpointsListChanged)
                {
                    postPropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
                }
            }
        }
    }

    /**
     * Fires a property thread in one of the {@code IO_POOL} threads.
     */
    private void postPropertyChange(String property, Object oldValue, Object newValue)
    {
        TaskPools.IO_POOL.submit(
                () -> firePropertyChange(property, oldValue, newValue));
    }
}
