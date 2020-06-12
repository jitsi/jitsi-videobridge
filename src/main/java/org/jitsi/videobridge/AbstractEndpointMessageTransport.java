/*
 * Copyright @ 2017 - Present, 8x8 Inc
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

import com.google.common.collect.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.util.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. An abstract implementation.
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpointMessageTransport
{
    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     */
    protected final AbstractEndpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    protected final @NotNull Logger logger;

    /**
     * The compatibility layer that translates selected, pinned and max
     * resolution messages into video constraints.
     */
    private final VideoConstraintsCompatibility
        videoConstraintsCompatibility = new VideoConstraintsCompatibility();

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     * @param endpoint the endpoint to which this transport belongs
     */
    public AbstractEndpointMessageTransport(AbstractEndpoint endpoint, @NotNull Logger parentLogger)
    {
        this.endpoint = endpoint;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    /**
     *
     * @return true if this message transport is 'connected', false otherwise
     */
    public abstract boolean isConnected();

    /**
     * Fires the message transport ready event for the associated endpoint.
     */
    protected void notifyTransportChannelConnected()
    {
    }

    /**
     * Notifies this {@link AbstractEndpointMessageTransport} that a
     * {@code ClientHello} has been received.
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated SCTP connection
     */
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
    }

    /**
     * Notifies this {@code Endpoint} that a specific JSON object has been
     * received.
     *
     * @param src the transport channel by which the specified
     * {@code jsonObject} has been received.
     * @param jsonObject the JSON data received by {@code src}.
     * @param colibriClass the non-{@code null} value of the mandatory JSON
     * property {@link Videobridge#COLIBRI_CLASS} required of all JSON objects
     * received.
     */
    private void onJSONData(
        Object src,
        JSONObject jsonObject,
        String colibriClass)
    {
        TaskPools.IO_POOL.submit(() -> {
            switch (colibriClass)
            {
                case COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED:
                    onSelectedEndpointChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED:
                    onSelectedEndpointsChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED:
                    onPinnedEndpointChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED:
                    onPinnedEndpointsChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_CLIENT_HELLO:
                    onClientHello(src, jsonObject);
                    break;
                case COLIBRI_CLASS_ENDPOINT_MESSAGE:
                    onClientEndpointMessage(src, jsonObject);
                    break;
                case COLIBRI_CLASS_LASTN_CHANGED:
                    onLastNChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT:
                    onReceiverVideoConstraintEvent(src, jsonObject);
                    break;
            case COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINTS:
                    onReceiverVideoConstraintsEvent(src, jsonObject);
                    break;
                default:
                    logger.info(
                            "Received a message with unknown colibri class: "
                                    + colibriClass);
                    break;
            }
        });
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be
     * forwarded to either: a) another client in this conference (1:1
     * message) or b) all other clients in this conference (broadcast message)
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code EndpointMessage} which has been received.
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint
     * in this conference, the message will be treated as a 1:1 message and
     * forwarded just to that endpoint. If the 'to' field is an empty
     * string, the message will be treated as a broadcast and sent to all other
     * endpoints in this conference.
     * 'msgPayload': An opaque payload. The bridge does not need to know or
     * care what is contained in the 'msgPayload' field, it will just forward
     * it blindly.
     *
     * NOTE: This message is designed to allow endpoints to pass their own
     * application-specific messaging to one another without requiring the
     * bridge to know of or understand every message type. These messages
     * will be forwarded by the bridge using the same transport channel as other
     * jitsi messages (e.g. active speaker and last-n notifications).
     * It is not recommended to send high-volume message traffic on this
     * channel (e.g. file transfer), such that it may interfere with other
     * jitsi messages.
     */
    @SuppressWarnings("unchecked")
    protected void onClientEndpointMessage(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        String to = (String)jsonObject.get("to");

        // First insert the "from" to prevent spoofing.
        String from = getId(jsonObject.get("from"));
        jsonObject.put("from", from);
        Conference conference = getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return;
        }

        AbstractEndpoint sourceEndpoint = conference.getEndpoint(from);

        if (sourceEndpoint == null)
        {
            logger.warn("Can not forward message, source endpoint not found.");
            // The source endpoint might have expired. If it was an Octo
            // endpoint and we forward the message, we may mistakenly forward
            // it back through Octo and cause a loop.
            return;
        }

        List<AbstractEndpoint> targets;
        if ("".equals(to))
        {
            // Broadcast message
            targets = new LinkedList<>(conference.getEndpoints());
            targets.removeIf(e -> e.getID().equalsIgnoreCase(getId()));
        }
        else
        {
            // 1:1 message
            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null)
            {
                targets = Collections.singletonList(targetEndpoint);
            }
            else
            {
                logger.warn(
                    "Unable to find endpoint " + to
                        + " to send EndpointMessage");
                return;
            }
        }

        boolean sendToOcto
            = !(sourceEndpoint instanceof OctoEndpoint)
              && targets.stream().anyMatch(e -> (e instanceof OctoEndpoint));

        conference.sendMessage(
                jsonObject.toString(),
                targets,
                sendToOcto);
    }

    /**
     * @return the associated {@link Conference} or {@code null}.
     */
    protected Conference getConference()
    {
        return endpoint != null ? endpoint.getConference() : null;
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     */
    private String getId()
    {
        return getId(null);
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     * @param id a suggested ID.
     */
    protected String getId(Object id)
    {
        return endpoint != null ? endpoint.getID() : null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    protected void onPinnedEndpointChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newPinnedEndpointID = (String) jsonObject.get("pinnedEndpoint");

        Set<String> newPinnedIDs = Collections.emptySet();
        if (newPinnedEndpointID != null && !"".equals(newPinnedEndpointID))
        {
            newPinnedIDs = Collections.singleton(newPinnedEndpointID);
        }

        onPinnedEndpointsChangedEvent(jsonObject, newPinnedIDs);
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    protected void onPinnedEndpointsChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("pinnedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newPinnedEndpoints = filterStringsToSet(jsonArray);

        if (logger.isDebugEnabled())
        {
            logger.debug("Pinned " + newPinnedEndpoints);
        }

        onPinnedEndpointsChangedEvent(jsonObject, newPinnedEndpoints);
    }

    /**
     * Notifies this {@code Endpoint} that a {@code SelectedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    protected void onSelectedEndpointChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newSelectedEndpointID
            = (String) jsonObject.get("selectedEndpoint");

        Set<String> newSelectedIDs = Collections.emptySet();
        if (newSelectedEndpointID != null && !"".equals(newSelectedEndpointID))
        {
            newSelectedIDs = Collections.singleton(newSelectedEndpointID);
        }

        onSelectedEndpointsChangedEvent(jsonObject, newSelectedIDs);
    }

    /**
     * Notifies this {@code Endpoint} that a
     * {@code SelectedEndpointsChangedEvent} has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    protected void onSelectedEndpointsChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("selectedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newSelectedEndpoints = filterStringsToSet(jsonArray);
        onSelectedEndpointsChangedEvent(jsonObject, newSelectedEndpoints);
    }

    private Set<String> filterStringsToSet(JSONArray jsonArray)
    {
        Set<String> strings = new HashSet<>();
        for (Object element : jsonArray)
        {
            if (element instanceof String)
            {
                strings.add((String)element);
            }
        }
        return strings;
    }

    /**
     * Notifies local or remote endpoints that a pinned event has been received.
     * If it is a local endpoint that has received the message, then this method
     * propagates the message to all proxies of the local endpoint.
     *
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     * @param newPinnedEndpoints the new pinned endpoints
     */
    protected void onPinnedEndpointsChangedEvent(
        JSONObject jsonObject,
        Set<String> newPinnedEndpoints)
    {
        videoConstraintsCompatibility.setPinnedEndpoints(newPinnedEndpoints);
        onVideoConstraintsChangedEvent(
            videoConstraintsCompatibility.computeVideoConstraints());
    }

    /**
     * Notifies local or remote endpoints that a selected event has been received.
     * If it is a local endpoint that has received the message, then this method
     * propagates the message to all proxies of the local endpoint.
     *
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     * @param newSelectedEndpoints the new selected endpoints
     */
    protected void onSelectedEndpointsChangedEvent(
        JSONObject jsonObject,
        Set<String> newSelectedEndpoints)
    {
        videoConstraintsCompatibility.setSelectedEndpoints(newSelectedEndpoints);
        onVideoConstraintsChangedEvent(
            videoConstraintsCompatibility.computeVideoConstraints());
    }

    protected void onVideoConstraintsChangedEvent(Map<String, VideoConstraints> videoConstraintMap)
    {
        // Don't "pollute" the video constraints map with constraints for this
        // endpoint.
        videoConstraintMap.remove(endpoint.getID());
        endpoint.setSenderVideoConstraints(ImmutableMap.copyOf(videoConstraintMap));
    }

    /**
     * Notifies this {@code Endpoint} that a {@code LastNChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code LastNChangedEvent} which has been received.
     */
    protected void onLastNChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new value for LastN.
        Object o = jsonObject.get("lastN");
        if (!(o instanceof Number))
        {
            return;
        }
        int lastN = ((Number) o).intValue();

        if (endpoint != null)
        {
            endpoint.setLastN(lastN);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a {@code ReceiverVideoConstraints}
     * event has been received
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ReceiverVideoConstraints} which has been received.
     */
    protected abstract void onReceiverVideoConstraintsEvent(
        Object src,
        JSONObject jsonObject);
    
    /**
     * Notifies this {@code Endpoint} that a {@code ReceiverVideoConstraint}
     * event has been received
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ReceiverVideoConstraint} which has been received.
     */
    protected void onReceiverVideoConstraintEvent(
        Object src,
        JSONObject jsonObject)
    {
        Object o = jsonObject.get("maxFrameHeight");
        if (!(o instanceof Number))
        {
            logger.warn(
                "Received a non-number maxFrameHeight video constraint from "
                    + getId() + ": " + o.toString());
            return;
        }
        int maxFrameHeight = ((Number) o).intValue();
        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Received a maxFrameHeight video constraint from "
                    + getId() + ": " + maxFrameHeight);
        }

        videoConstraintsCompatibility.setMaxFrameHeight(maxFrameHeight);
        onVideoConstraintsChangedEvent(videoConstraintsCompatibility.computeVideoConstraints());
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific message
     * has been received on a specific transport channel.
     * @param src the transport channel on which the message has been received.
     * @param msg the message which has been received.
     */
    public void onMessage(Object src, String msg)
    {
        Object obj;
        JSONParser parser = new JSONParser(); // JSONParser is NOT thread-safe.

        try
        {
            obj = parser.parse(msg);
        }
        catch (ParseException ex)
        {
            logger.warn(
                    "Malformed JSON received from endpoint " + getId(),
                    ex);
            obj = null;
        }

        // We utilize JSONObjects only.
        if (obj instanceof JSONObject)
        {
            JSONObject jsonObject = (JSONObject) obj;
            // We utilize JSONObjects with colibriClass only.
            String colibriClass
                = (String)jsonObject.get(Videobridge.COLIBRI_CLASS);

            if (colibriClass != null)
            {
                onJSONData(src, jsonObject, colibriClass);
            }
            else
            {
                logger.warn(
                    "Malformed JSON received from endpoint " + getId()
                        + ". JSON object does not contain the colibriClass"
                        + " field.");
            }
        }
    }

    /**
     * Sends a specific message over the active transport channels of this
     * {@link EndpointMessageTransport}.
     *
     * @param msg message text to send.
     */
    protected void sendMessage(String msg)
    {
    }

    /**
     * Closes this {@link EndpointMessageTransport}.
     */
    protected void close()
    {
    }

    public JSONObject getDebugState() {
        return new JSONObject();
    }

    /**
     * Events generated by {@link AbstractEndpointMessageTransport} types which
     * are of interest to other entities.
     */
    interface EndpointMessageTransportEventHandler {
        void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint);
    }
}
