package org.jitsi.videobridge;

import org.json.simple.*;

import java.util.*;

public class EndpointMessageBuilder
{
    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ClientHello} message.
     */
    public static final String COLIBRI_CLASS_CLIENT_HELLO
        = "ClientHello";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a dominant speaker
     * change event.
     */
    public static final String COLIBRI_CLASS_DOMINANT_SPEAKER_CHANGE
        = "DominantSpeakerEndpointChangeEvent";

    /**
     * Constant value defines the name of "colibriClass" for connectivity status
     * notifications sent over the data channels.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS
        = "EndpointConnectivityStatusChangeEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code EndpointMessage}.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_MESSAGE
        = "EndpointMessage";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code LastNChangedEvent}.
     */
    public static final String COLIBRI_CLASS_LASTN_CHANGED
        = "LastNChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code LastNEndpointsChangedEvent}.
     */
    public static final String COLIBRI_CLASS_LASTN_ENDPOINTS_CHANGED
        = "LastNEndpointsChangeEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED
        = "PinnedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointsChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED
        = "PinnedEndpointsChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ReceiverVideoConstraint} message.
     */
    public static final String COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT
        = "ReceiverVideoConstraint";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED
        = "SelectedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED
        = "SelectedEndpointsChangedEvent";

    /**
     * The string which encodes a COLIBRI {@code ServerHello} message.
     */
    public static final String COLIBRI_CLASS_SERVER_HELLO = "ServerHello";

    /**
     * @param dominantSpeaker the dominant speaker in this multipoint conference
     *
     * @return a new <tt>String</tt> which represents a message to be sent
     * to an endpoint in order to notify it that the dominant speaker in its
     * multipoint conference has changed to a specific endpoint.
     */
    public static String createDominantSpeakerEndpointChangeEvent(
        Endpoint dominantSpeaker)
    {
        return
            "{\"colibriClass\":\""
                + COLIBRI_CLASS_DOMINANT_SPEAKER_CHANGE + "\","
                + "\"dominantSpeakerEndpoint\":\""
                + JSONValue.escape(dominantSpeaker.getID()) + "\"}";
    }

    /**
     * Creates a string which represents a message of type
     * {@link #COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS}.
     * in order to notify it
     * @param endpointId ?
     * @param connected ?
     */
    public static String createEndpointConnectivityStatusChangeEvent(
        String endpointId, boolean connected)
    {
        return
            "{\"colibriClass\":\""
                + COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS
                + "\",\"endpoint\":\"" + JSONValue.escape(endpointId)
                +"\", \"active\":\"" + String.valueOf(connected)
                + "\"}";
    }

    /**
     * Creates a Colibri ServerHello message.
     */
    public static String createServerHelloEvent()
    {
        return "{\"colibriClass\":\"" + COLIBRI_CLASS_SERVER_HELLO + "\"}";
    }

    /**
     * Creates a LastNEndpointsChanged message.
     */
    public static String createLastNEndpointsChangeEvent(
            Collection<String> forwardedEndpoints,
            Collection<String> endpointsEnteringLastN,
            Collection<String> conferenceEndpoints)
    {
        StringBuilder msg
            = new StringBuilder(
                "{\"colibriClass\":\""
                + COLIBRI_CLASS_LASTN_ENDPOINTS_CHANGED + "\"");

        // lastNEndpoints
        msg.append(",\"lastNEndpoints\":");
        msg.append(getJsonString(forwardedEndpoints));

        // endpointsEnteringLastN
        msg.append(",\"endpointsEnteringLastN\":");
        msg.append(getJsonString(endpointsEnteringLastN));

        // conferenceEndpoints
        msg.append(",\"conferenceEndpoints\":");
        msg.append(getJsonString(conferenceEndpoints));

        msg.append('}');

        return msg.toString();
    }

    private static String getJsonString(Collection<String> strings)
    {
        JSONArray array = new JSONArray();
        if (strings != null && !strings.isEmpty())
        {
            array.addAll(strings);
        }
        return array.toString();
    }
}
