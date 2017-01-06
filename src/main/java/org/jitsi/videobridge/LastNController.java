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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.ratecontrol.*;

import java.util.*;

/**
 * Manages the set of <tt>Endpoint</tt>s whose video streams are being
 * forwarded to a specific <tt>VideoChannel</tt> (i.e. the
 * <tt>VideoChannel</tt>'s <tt>LastN</tt> set).
 *
 * @author Lyubomir Marinov
 * @author George Politis
 * @author Boris Grozev
 */
public class LastNController
{
    /**
     * The {@link Logger} used by the {@link LastNController} class to print
     * debug information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
         = Logger.getLogger(LastNController.class);

    /**
     * An empty list instance.
     */
    protected static final List<String> INITIAL_EMPTY_LIST
            = Collections.unmodifiableList(new LinkedList<String>());

    /**
     * The set of <tt>Endpoints</tt> whose video streams are currently being
     * forwarded.
     */
    private List<String> forwardedEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The list of all <tt>Endpoint</tt>s in the conference, ordered by the
     * last time they were elected dominant speaker.
     */
    private List<String> conferenceSpeechActivityEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The list of endpoints which have been explicitly marked as 'pinned'
     * and whose video streams should always be forwarded.
     */
    private Set<String> pinnedEndpoints = Collections.EMPTY_SET;

    /**
     * The maximum number of endpoints whose video streams will be forwarded
     * to the endpoint, as externally configured (by the client, by the focus
     * agent, or by default configuration). A value of {@code -1} means that
     * there is no limit, and all endpoints' video streams will be forwarded.
     */
    private int lastN = -1;

    /**
     * The current limit to the number of endpoints whose video streams will be
     * forwarded to the endpoint. This value can be changed by videobridge
     * (i.e. when Adaptive Last N is used), but it must not exceed the value
     * of {@link #lastN}. A value of {@code -1} means that there is no limit, and
     * all endpoints' video streams will be forwarded.
     */
    private int currentLastN = -1;

    /**
     * Whether or not adaptive lastN is in use.
     */
    private boolean adaptiveLastN = false;

    /**
     * Whether or not adaptive simulcast is in use.
     */
    private boolean adaptiveSimulcast = false;

    /**
     * The instance which implements <tt>Adaptive LastN</tt> or
     * <tt>Adaptive Simulcast</tt> on our behalf.
     */
    private BitrateController bitrateController = null;

    /**
     * The {@link VideoChannel} which owns this {@link LastNController}.
     */
    private final VideoChannel channel;

    /**
     * The ID of the endpoint of {@link #channel}.
     */
    private String endpointId;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new {@link LastNController} instance which is to belong
     * to a particular {@link VideoChannel}.
     * @param channel the owning {@link VideoChannel}.
     */
    public LastNController(VideoChannel channel)
    {
        this.channel = channel;
        this.logger
            = Logger.getLogger(
                    classLogger,
                    channel.getContent().getConference().getLogger());
    }

    /**
     * @return the maximum number of endpoints whose video streams will be
     * forwarded to the endpoint. A value of {@code -1} means that there is no
     * limit.
     */
    public int getLastN()
    {
        return lastN;
    }

    /**
     * @return the set of <tt>Endpoints</tt> whose video streams are currently
     * being forwarded.
     */
    public List<String> getForwardedEndpoints()
    {
        return forwardedEndpoints;
    }

    /**
     * Sets the value of {@code lastN}, that is, the maximum number of endpoints
     * whose video streams will be forwarded to the endpoint. A value of
     * {@code -1} means that there is no limit.
     * @param lastN the value to set.
     */
    public void setLastN(int lastN)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Setting lastN=" + lastN);
        }

        List<String> endpointsToAskForKeyframe = null;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.

            if (this.lastN != lastN)
            {
                // If we're just now enabling lastN, we don't need to ask for
                // keyframes as all streams were being forwarded already.
                boolean update = this.lastN != -1;

                this.lastN = lastN;

                if (lastN >= 0 && (currentLastN < 0 || currentLastN > lastN))
                {
                    currentLastN = lastN;
                }

                if (update)
                {
                    endpointsToAskForKeyframe = update();
                }
            }
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * Closes this {@link LastNController}.
     */
    public void close()
    {
        if (bitrateController != null)
        {
            try
            {
                bitrateController.close();
            }
            finally
            {
                bitrateController = null;
            }
        }
    }

    /**
     * Sets the list of "pinned" endpoints (i.e. endpoints for which video
     * should always be forwarded, regardless of {@code lastN}).
     * @param newPinnedEndpointIds the list of endpoint IDs to set.
     */
    public void setPinnedEndpointIds(Set<String> newPinnedEndpointIds)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Setting pinned endpoints: "
                                 + newPinnedEndpointIds.toString());
        }
        List<String> endpointsToAskForKeyframe = null;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.
            if (!pinnedEndpoints.equals(newPinnedEndpointIds))
            {
                pinnedEndpoints
                        = Collections.unmodifiableSet(newPinnedEndpointIds);

                endpointsToAskForKeyframe = update();
            }
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * Checks whether RTP packets from {@code sourceChannel} should be forwarded
     * to {@link #channel}.
     * @param sourceChannel the channel.
     * @return {@code true} iff RTP packets from {@code sourceChannel} should
     * be forwarded to {@link #channel}.
     */
    public boolean isForwarded(RtpChannel sourceChannel)
    {
        if (lastN < 0 && currentLastN < 0)
        {
            // If Last-N is disabled, we forward everything.
            return true;
        }

        if (sourceChannel == null)
        {
            logger.warn("Invalid sourceChannel: null.");
            return false;
        }

        Endpoint channelEndpoint = sourceChannel.getEndpoint();
        if (channelEndpoint == null)
        {
            logger.warn("sourceChannel has no endpoint.");
            return false;
        }

        if (forwardedEndpoints == INITIAL_EMPTY_LIST)
        {
            // LastN is enabled, but we haven't yet initialized the list of
            // endpoints in the conference.
            initializeConferenceEndpoints();
        }

        // This may look like a place to optimize, because we query an unordered
        // list (in O(n)) and it executes on each video packet if lastN is
        // enabled. However, the size of  forwardedEndpoints is restricted to
        // lastN and so small enough that it is not worth optimizing.
        return forwardedEndpoints.contains(channelEndpoint.getID());
    }

    /**
     * @return the number of streams currently being forwarded.
     */
    public int getN()
    {
        return forwardedEndpoints.size();
    }

    /**
     * @return the list of "pinned" endpoints.
     */
    public Set<String> getPinnedEndpoints()
    {
        return pinnedEndpoints;
    }

    /**
     * Notifies this instance that the ordered list of endpoints in the
     * conference has changed.
     *
     * @param endpoints the new ordered list of endpoints in the conference.
     * @return the list of endpoints which were added to the list of forwarded
     * endpoints as a result of the call, or {@code null} if none were added.
     */
    public List<Endpoint> speechActivityEndpointsChanged(
            List<Endpoint> endpoints)
    {
        List<String> newEndpointIdList = getIDs(endpoints);
        List<String> enteringEndpointIds
            = speechActivityEndpointIdsChanged(newEndpointIdList);

        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "New list of conference endpoints: "
                    + newEndpointIdList.toString() + "; entering endpoints: "
                    + (enteringEndpointIds == null ? "none" :
                        enteringEndpointIds.toString()));
        }

        List<Endpoint> ret = new LinkedList<>();
        if (enteringEndpointIds != null)
        {
            for (Endpoint endpoint : endpoints)
            {
                if (enteringEndpointIds.contains(endpoint.getID()))
                {
                    ret.add(endpoint);
                }
            }
        }

        return ret;
    }

    /**
     * Notifies this instance that the ordered list of endpoints (specified
     * as a list of endpoint IDs) in the conference has changed.
     *
     * @param endpointIds the new ordered list of endpoints (specified as a list
     * of endpoint IDs) in the conference.
     * @return the list of IDs of endpoints which were added to the list of
     * forwarded endpoints as a result of the call.
     */
    private synchronized List<String> speechActivityEndpointIdsChanged(
            List<String> endpointIds)
    {
        // This comparison needs to care about order because you could have the same set of active endpoints,
        //  but have one that moved from outside the last-n range to inside the last-n range, so there needs to
        //  be an update.
        if (conferenceSpeechActivityEndpoints.equals(endpointIds))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Conference endpoints have not changed.");
            }
            return null;
        }
        else
        {
            List<String> newEndpoints = new LinkedList<>(endpointIds);
            newEndpoints.removeAll(conferenceSpeechActivityEndpoints);

            conferenceSpeechActivityEndpoints = endpointIds;

            return update(newEndpoints);
        }
    }

    /**
     * Enables or disables the "adaptive last-n" mode, depending on the value of
     * {@code adaptiveLastN}.
     * @param adaptiveLastN {@code true} to enable, {@code false} to disable
     */
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        if (this.adaptiveLastN != adaptiveLastN)
        {
            if (adaptiveLastN && adaptiveSimulcast)
            {
                //adaptive LastN and adaptive simulcast cannot be used together.
                logger.error("Not enabling adaptive lastN, because adaptive" +
                                     " simulcast is in use.");
                return;
            }
            else if (adaptiveLastN && bitrateController == null)
            {
                bitrateController = new LastNBitrateController(this, channel);
            }

            this.adaptiveLastN = adaptiveLastN;
        }
    }

    /**
     * Enables or disables the "adaptive simulcast" mod, depending on the value
     * of {@code adaptiveLastN}.
     * @param adaptiveSimulcast {@code true} to enable, {@code false} to
     * disable.
     */
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {
        if (this.adaptiveSimulcast != adaptiveSimulcast)
        {
            if (adaptiveSimulcast && adaptiveLastN)
            {
                //adaptive LastN and adaptive simulcast cannot be used together.
                logger.error("Not enabling adaptive simulcast, because " +
                             "adaptive lastN is in use.");
                return;
            }
            else if (adaptiveSimulcast && bitrateController == null)
            {
                bitrateController
                    = new AdaptiveSimulcastBitrateController(this, channel);
            }

            this.adaptiveSimulcast = adaptiveSimulcast;
        }
    }

    /**
     * @return {@code true} iff the "adaptive last-n" mode is enabled.
     */
    public boolean getAdaptiveLastN()
    {
        return adaptiveLastN;
    }

    /**
     * @return {@code true} iff the "adaptive simulcast" mode is enabled.
     */
    public boolean getAdaptiveSimulcast()
    {
        return adaptiveSimulcast;
    }

    /**
     * Recalculates the list of forwarded endpoints based on the current values
     * of the various parameters of this instance ({@link #lastN},
     * {@link #conferenceSpeechActivityEndpoints}, {@link #pinnedEndpoints}).
     *
     * @return the list of IDs of endpoints which were added to
     * {@link #forwardedEndpoints} (i.e. of endpoints * "entering last-n") as a
     * result of this call. Returns {@code null} if no endpoints were added.
     */
    private synchronized List<String> update()
    {
        return update(null);
    }

    /**
     * Determine the list of endpoints that should be forwarded to the receiver
     *
     * @param newConferenceEndpoints A list of endpoints which entered the
     * conference since the last call to this method. They need not be asked
     * for keyframes, because they were never filtered by this
     * {@link #LastNController(VideoChannel)}. Used by extending classes.
     *
     * @return list of endpoints that should be forwarded, not necessarily in any
     * particular order
     */
    @SuppressWarnings("unused")
    protected List<String> determineLastNList(List<String> newConferenceEndpoints)
    {
        List<String> newForwardedEndpoints = new LinkedList<>();
        String ourEndpointId = getEndpointId();

        if (conferenceSpeechActivityEndpoints == INITIAL_EMPTY_LIST)
        {
            conferenceSpeechActivityEndpoints
                    = getIDs(channel.getConferenceSpeechActivity().getEndpoints());
        }

        if (lastN < 0 && currentLastN < 0)
        {
            // Last-N is disabled, we forward everything.
            newForwardedEndpoints.addAll(conferenceSpeechActivityEndpoints);
            if (ourEndpointId != null)
            {
                newForwardedEndpoints.remove(ourEndpointId);
            }
        }
        else
        {
            // Here we have lastN >= 0 || currentLastN >= 0 which implies
            // currentLastN >= 0.

            // Pinned endpoints are always forwarded.
            newForwardedEndpoints.addAll(getPinnedEndpoints());
            // As long as they are still endpoints in the conference.
            newForwardedEndpoints.retainAll(conferenceSpeechActivityEndpoints);

            // Don't exceed the last-n value no matter what the client has
            // pinned.
            while (newForwardedEndpoints.size() > currentLastN)
            {
                newForwardedEndpoints.remove(newForwardedEndpoints.size() - 1);
            }

            if (newForwardedEndpoints.size() < currentLastN)
            {
                for (String endpointId : conferenceSpeechActivityEndpoints)
                {
                    if (newForwardedEndpoints.size() < currentLastN)
                    {
                        if (!endpointId.equals(ourEndpointId)
                                && !newForwardedEndpoints.contains(endpointId))
                        {
                            newForwardedEndpoints.add(endpointId);
                        }
                    }
                    else
                    {
                        break;
                    }
                }
            }
        }
        return newForwardedEndpoints;
    }

    /**
     * Recalculates the list of forwarded endpoints based on the current values
     * of the various parameters of this instance ({@link #lastN},
     * {@link #conferenceSpeechActivityEndpoints}, {@link #pinnedEndpoints}).
     *
     * @param newConferenceEndpoints A list of endpoints which entered the
     * conference since the last call to this method. They need not be asked
     * for keyframes, because they were never filtered by this
     * {@link #LastNController(VideoChannel)}.
     *
     * @return the list of IDs of endpoints which were added to
     * {@link #forwardedEndpoints} (i.e. of endpoints * "entering last-n") as a
     * result of this call. Returns {@code null} if no endpoints were added.
     */
    private synchronized List<String> update(List<String> newConferenceEndpoints)
    {
        List<String> newForwardedEndpoints = determineLastNList(newConferenceEndpoints);

        newForwardedEndpoints
            = orderBy(newForwardedEndpoints, conferenceSpeechActivityEndpoints);

        List<String> enteringEndpoints;
        if (equalAsSets(forwardedEndpoints,newForwardedEndpoints))
        {
            // We want forwardedEndpoints != INITIAL_EMPTY_LIST
            forwardedEndpoints = newForwardedEndpoints;

            enteringEndpoints = null;
        }
        else
        {
            enteringEndpoints = new ArrayList<>(newForwardedEndpoints);
            enteringEndpoints.removeAll(forwardedEndpoints);

            List<String> leavingEndpoints
                = new ArrayList<>(forwardedEndpoints);
            leavingEndpoints.removeAll(newForwardedEndpoints);

            updateInLastN(enteringEndpoints, leavingEndpoints);

            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Forwarded endpoints changed: "
                        + forwardedEndpoints.toString() + " -> "
                        + newForwardedEndpoints.toString()
                        + ". Entering: " + enteringEndpoints.toString());
            }

            forwardedEndpoints
                    = Collections.unmodifiableList(newForwardedEndpoints);

            if (lastN >= 0 || currentLastN >= 0)
            {
                List<String> conferenceEndpoints
                    = conferenceSpeechActivityEndpoints.
                        subList(
                            0,
                            Math.min(
                                lastN,
                                conferenceSpeechActivityEndpoints.size()));

                // TODO: we may want to do this asynchronously.
                channel.sendLastNEndpointsChangeEventOnDataChannel(
                        forwardedEndpoints, enteringEndpoints, conferenceEndpoints);
            }
        }

        // If lastN is disabled, the endpoints entering forwardedEndpoints were
        // never filtered, so they don't need to be asked for keyframes.
        if (lastN < 0 && currentLastN < 0)
        {
            enteringEndpoints = null;
        }

        if (enteringEndpoints != null && newConferenceEndpoints != null)
        {
            // Endpoints just entering the conference need not be asked for
            // keyframes.
            enteringEndpoints.removeAll(newConferenceEndpoints);
        }

        return enteringEndpoints;
    }

    /**
     * Updates the value of the "inLastN" property of the {@link VideoChannel}s
     * of the given endpoints after {@code enteringEndpoints} have entered
     * the {@code forwardedEndpoints} set and {@code leavingEndpoints} have
     * left the {@code forwardedEndpoints} set.
     *
     * @param enteringEndpoints the list of IDs of endpoints which entered our
     * {@code forwardedEndpoints} set.
     * @param leavingEndpoints the list of IDs of endpoints which left our
     * {@code forwardedEndpoints} set.
     */
    private void updateInLastN(List<String> enteringEndpoints,
                               List<String> leavingEndpoints)
    {
        for (String endpointId : enteringEndpoints)
        {
            for (VideoChannel videoChannel : getVideoChannels(endpointId))
            {
                // This endpoint just entered our forwardedEndpoints set, so
                // it is streamed to *someone*.
                videoChannel.setInLastN(true);
            }
        }

        for (String endpointId : leavingEndpoints)
        {
            for (VideoChannel videoChannel : getVideoChannels(endpointId))
            {
                // We are not forwarding this channel anymore. It should
                // re-evaluate its 'inLastN'.
                videoChannel.updateInLastN();
            }
        }
    }

    /**
     * Gets the list of {@link VideoChannel}s belonging to an endpoint with a
     * given ID.
     * @param id the ID of the endpoint.
     * @return the {@link VideoChannel}s of the endpoint.
     */
    private List<VideoChannel> getVideoChannels(String id)
    {
        Endpoint endpoint = null;

        Content content = channel.getContent();
        if (content != null)
        {
            Conference conference = content.getConference();
            if (conference != null)
            {
                endpoint = conference.getEndpoint(id);
            }
        }

        return getVideoChannels(endpoint);
    }

    /**
     * Gets the list of {@link VideoChannel}s belonging to an endpoint.
     *
     * @param endpoint the endpoint.
     * @return the {@link VideoChannel}s of the endpoint.
     */
    private List<VideoChannel> getVideoChannels(Endpoint endpoint)
    {
        List<VideoChannel> videoChannels = new LinkedList<>();

        if (endpoint != null)
        {
            for (Channel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                if (channel instanceof VideoChannel)
                {
                    videoChannels.add((VideoChannel)channel);
                }
            }
        }

        return videoChannels;
    }

    /**
     * Sends a keyframe request to the endpoints specified in
     * {@code endpointIds}
     * @param endpointIds the list of IDs of endpoints to which to send a
     * request for a keyframe.
     */
    private void askForKeyframes(List<String> endpointIds)
    {
        // TODO: Execute asynchronously.
        if (endpointIds != null && !endpointIds.isEmpty())
        {
            channel.getContent().askForKeyframesById(endpointIds);
        }
    }

    /**
     * @return the ID of the endpoint of our channel.
     */
    private String getEndpointId()
    {
        if (endpointId == null)
        {
            Endpoint endpoint = channel.getEndpoint();
            if (endpoint != null)
            {
                endpointId = endpoint.getID();
            }
        }
        return endpointId;
    }

    /**
     * Initializes the local list of endpoints
     * ({@link #speechActivityEndpointsChanged(List)}) with the current
     * endpoints from the conference.
     */
    public synchronized void initializeConferenceEndpoints()
    {
        speechActivityEndpointsChanged(
                channel.getConferenceSpeechActivity().getEndpoints());

        if (logger.isDebugEnabled())
        {
            logger.debug("Initialized the list of endpoints: "
                                 + conferenceSpeechActivityEndpoints.toString());
        }
    }

    /**
     * Extracts a list of endpoint IDs from a list of {@link Endpoint}s.
     * @param endpoints the list of {@link Endpoint}s.
     * @return the list of IDs of endpoints in {@code endpoints}.
     */
    private List<String> getIDs(List<Endpoint> endpoints)
    {
        if (endpoints != null && !endpoints.isEmpty())
        {
            List<String> endpointIds = new LinkedList<>();
            for (Endpoint endpoint : endpoints)
            {
                endpointIds.add(endpoint.getID());
            }
            return endpointIds;
        }

        return null;
    }

    public int getCurrentLastN()
    {
        return currentLastN;
    }

    public int setCurrentLastN(int currentLastN)
    {
        List<String> endpointsToAskForKeyframe;

        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.

            if (lastN >= 0 && lastN < currentLastN)
            {
                currentLastN = lastN;
            }

            this.currentLastN = currentLastN;

            endpointsToAskForKeyframe = update();
        }

        askForKeyframes(endpointsToAskForKeyframe);

        return currentLastN;
    }

    /**
     * @return true if and only if the two lists {@code l1} and {@code l2}
     * contains the same elements (regardless of their order).
     */
    private boolean equalAsSets(List<?> l1, List<?> l2)
    {
        Set<Object> s1 = new HashSet<>();
        s1.addAll(l1);
        Set<Object> s2 = new HashSet<>();
        s2.addAll(l2);

        return s1.equals(s2);
    }

    /**
     * Returns a list which consists of the elements of {@code toOrder}, with
     * duplicates removed, and ordered by their appearance in {@code template}.
     * If {@code toOrder} contains elements which do not appear in
     * {@code template}, they are added to the end of the list, in their
     * original order from {@code toOrder}.
     * @param toOrder the list to order.
     * @param template the list which specifies how to order the elements of
     * {@code toOrder}.
     */
    private List<String> orderBy(List<String> toOrder, List<String> template)
    {
        if (template == null || template.isEmpty())
            return toOrder;

        List<String> result = new LinkedList<>();
        for (String s : template)
        {
            if (toOrder.contains(s) && !result.contains(s))
                result.add(s);
            if (result.size() == toOrder.size())
                break;
        }

        for (String s : toOrder)
        {
            if (!result.contains(s))
                result.add(s);
        }

        return result;
    }
}
