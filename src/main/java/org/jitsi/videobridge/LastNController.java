package org.jitsi.videobridge;

import org.jitsi.util.*;

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
     * The <tt>Logger</tt> used by the <tt>VideoChannel</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(VideoChannel.class);

    /**
     * The set of <tt>Endpoints</tt> whose video streams are currently being
     * forwarded.
     */
    private List<String> forwardedEndpoints
            = Collections.unmodifiableList(new LinkedList<String>());

    /**
     * The list of all <tt>Endpoint</tt>s in the conference, ordered by the
     * last time they were elected dominant speaker.
     */
    private List<String> conferenceSpeechActivityEndpoints = new LinkedList<>();

    /**
     * The list of endpoints which have been explicitly marked as 'pinned'
     * and whose video streams should always be forwarded.
     */
    private List<String> pinnedEndpoints
        = Collections.unmodifiableList(new LinkedList<String>());

    /**
     * The maximum number of endpoints whose video streams will be forwarded
     * to the endpoint. A value of {@code -1} means that there is no limit, and
     * all endpoints' video streams will be forwarded.
     */
    private int lastN = -1;

    /**
     * Whether or not adaptive lastN is in use.
     */
    private boolean adaptiveLastN = false;

    /**
     * Whether or not adaptive simulcast is in use.
     */
    private boolean adaptiveSimulcast = false;

    /**
     * The {@link VideoChannel} which owns this {@link LastNController}.
     */
    private final VideoChannel channel;

    /**
     * The ID of the endpoint of {@link #channel}.
     */
    private String endpointId;

    /**
     * Initializes a new {@link LastNController} instance which is to belong
     * to a particular {@link VideoChannel}.
     * @param channel the owning {@link VideoChannel}.
     */
    public LastNController(VideoChannel channel)
    {
        this.channel = channel;
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
        List<String> endpointsToAskForKeyframe;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.

            this.lastN = lastN;

            endpointsToAskForKeyframe = update();
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * Sets the list of "pinned" endpoints (i.e. endpoints for which video
     * should always be forwarded, regardless of {@code lastN}).
     * @param newPinnedEndpoints the list of endpoints to set.
     */
    public void setPinnedEndpoints(
            Collection<Endpoint> newPinnedEndpoints)
    {
        List<String> newPinnedEndpointIds = new LinkedList<>();
        for (Endpoint endpoint : newPinnedEndpoints)
        {
            newPinnedEndpointIds.add(endpoint.getID());
        }

        List<String> endpointsToAskForKeyframe;
        synchronized (this)
        {
            // Since we have the lock anyway, call update() inside, so it
            // doesn't have to obtain it again. But keep the call to
            // askForKeyframes() outside.
            this.pinnedEndpoints
                    = Collections.unmodifiableList(newPinnedEndpointIds);

            endpointsToAskForKeyframe = update();
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
    public boolean isForwarded(Channel sourceChannel)
    {
        if (lastN < 0)
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
            return false;
        }

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
    public List<String> getPinnedEndpoints()
    {
        return pinnedEndpoints;
    }

    /**
     * Notifies this instance that the ordered list of endpoints in the
     * conference has changed.
     *
     * @param endpoints the new ordered list of endpoints in the conference.
     * @return the list of endpoints which were added to the list of forwarded
     * endpoints as a result of the call.
     */
    public List<Endpoint> speechActivityEndpointsChanged(
            List<Endpoint> endpoints)
    {
        List<String> newEndpointIdList = new LinkedList<>();
        for (Endpoint endpoint : endpoints)
        {
            newEndpointIdList.add(endpoint.getID());
        }

        List<String> enteringEndpointIds
            = speechActivityEndpointIdsChanged(newEndpointIdList);

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
        boolean change = false;
        if (endpointIds.size() != conferenceSpeechActivityEndpoints.size())
        {
            change = true;
        }
        else
        {
            for (int i = 0; i < endpointIds.size(); i++)
            {
                if (!(conferenceSpeechActivityEndpoints.get(i)
                        .equals(endpointIds.get(i))))
                {
                    change = true;
                    break;
                }
            }
        }

        if (!change)
        {
            return null;
        }

        conferenceSpeechActivityEndpoints = endpointIds;

        return update();
    }

    /**
     * Enables or disables the "adaptive last-n" mode, depending on the value of
     * {@code adaptiveLastN}.
     * @param adaptiveLastN {@code true} to enable, {@code false} to disable
     */
    public void setAdaptiveLastN(boolean adaptiveLastN)
    {
        this.adaptiveLastN = adaptiveLastN;
        // TODO: actually enable/disable
    }

    /**
     * Enables or disables the "adaptive simulcast" mod, depending on the value
     * of {@code adaptiveLastN}.
     * @param adaptiveSimulcast {@code true} to enable, {@code false} to
     * disable.
     */
    public void setAdaptiveSimulcast(boolean adaptiveSimulcast)
    {
        this.adaptiveSimulcast = adaptiveSimulcast;
        // TODO: actually enable/disable
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
     * result of this call.
     */
    private synchronized List<String> update()
    {
        List<String> newForwardedEndpoints = new LinkedList<>();
        String ourEndpointId = getEndpointId();

        if (lastN < 0)
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
            // Pinned endpoints are always forwarded.
            newForwardedEndpoints.addAll(getPinnedEndpoints());
            // As long as they are still endpoints in the conference.
            newForwardedEndpoints.retainAll(conferenceSpeechActivityEndpoints);

            if (newForwardedEndpoints.size() > lastN)
            {
                // What do we want in this case? It looks like a contradictory
                // request from the client, but maybe it makes for a good API
                // on the client to allow the pinned to override last-n.
                // Unfortunately, this will not play well with Adaptive-Last-N
                // or changes to Last-N for other reasons.
            }
            else if (newForwardedEndpoints.size() < lastN)
            {
                for (String endpointId : conferenceSpeechActivityEndpoints)
                {
                    if (newForwardedEndpoints.size() < lastN)
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

        List<String> enteringEndpoints = new ArrayList<>(newForwardedEndpoints);
        enteringEndpoints.removeAll(forwardedEndpoints);

        forwardedEndpoints
                = Collections.unmodifiableList(newForwardedEndpoints);

        return enteringEndpoints;
    }

    /**
     * Sends a keyframe request to the endpoints specified in
     * {@code endpointIds}
     * @param endpointIds the list of IDs of endpoints to which to send a
     * request for a keyframe.
     */
    private void askForKeyframes(List<String> endpointIds)
    {
        channel.getContent().askForKeyframesById(endpointIds);
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
}
