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

import java.io.*;
import java.lang.ref.*;
import java.util.*;

import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.json.simple.*;
import org.json.simple.parser.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 * @author George Politis
 */
public class Endpoint
    extends PropertyChangeNotifier
    implements WebRtcDataStream.DataCallback
{
    /**
     * The name of the <tt>Endpoint</tt> property <tt>channels</tt> which lists
     * the <tt>RtpChannel</tt>s associated with the <tt>Endpoint</tt>.
     */
    public static final String CHANNELS_PROPERTY_NAME
        = Endpoint.class.getName() + ".channels";

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger = Logger.getLogger(Endpoint.class);

    /**
     * The name of the <tt>Endpoint</tt> property <tt>pinnedEndpoint</tt> which
     * specifies the JID of the currently pinned <tt>Endpoint</tt> of this
     * <tt>Endpoint</tt>.
     */
    public static final String PINNED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".pinnedEndpoints";

    /**
     * The name of the <tt>Endpoint</tt> property <tt>sctpConnection</tt> which
     * specifies the <tt>SctpConnection</tt> associated with the
     * <tt>Endpoint</tt>.
     */
    public static final String SCTP_CONNECTION_PROPERTY_NAME
        = Endpoint.class.getName() + ".sctpConnection";

    /**
     * The name of the <tt>Endpoint</tt> property <tt>selectedEndpoint</tt>
     * which specifies the JID of the currently selected <tt>Endpoint</tt> of
     * this <tt>Endpoint</tt>.
     */
    public static final String SELECTED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".selectedEndpoints";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    private static final String COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED
        = "SelectedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    private static final String COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED
        = "SelectedEndpointsChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointChangedEvent}.
     */
    private static final String COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED
        = "PinnedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointsChangedEvent}.
     */
    private static final String COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED
        = "PinnedEndpointsChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ClientHello} message.
     */
    private static final String COLIBRI_CLASS_CLIENT_HELLO
        = "ClientHello";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code EndpointMessage}.
     */
    private static final String COLIBRI_CLASS_ENDPOINT_MESSAGE
        = "EndpointMessage";

    /**
     * Configuration property for number of streams to cache
     */
    @Deprecated
    public final static String ENABLE_LIPSYNC_HACK_PNAME
        = Endpoint.class.getName() + ".ENABLE_LIPSYNC_HACK";

    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels = new LinkedList<>();

    /**
     * The (human readable) display name of this <tt>Endpoint</tt>.
     */
    private String displayName;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Endpoint</tt>.
     */
    private boolean expired = false;

    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * The string used to identify this endpoint for the purposes of logging.
     */
    private final String loggingId;

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<>(null);

    /**
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The set of IDs of the pinned endpoints of this {@code Endpoint}.
     */
    private Set<String> pinnedEndpoints = new HashSet<>();

    /**
     * The set of currently selected <tt>Endpoint</tt>s at this
     * <tt>Endpoint</tt>.
     */
    private Set<String> selectedEndpoints = new HashSet<>();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     * @param conference
     */
    public Endpoint(String id, Conference conference)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        this.id = Objects.requireNonNull(id, "id");
        loggingId = conference.getLoggingId() + ",endp_id=" + id;

        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * Adds a specific <tt>Channel</tt> to the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to add to the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean addChannel(RtpChannel channel)
    {
        if (channel == null)
            throw new NullPointerException("channel");

        // The expire state of Channel is final. Adding an expired Channel to
        // an Endpoint is a no-op.
        if (channel.isExpired())
            return false;

        boolean added = false;
        boolean removed = false;

        synchronized (channels)
        {
            boolean add = true;

            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if (c == null)
                {
                    i.remove();
                    removed = true;
                }
                else if (c.equals(channel))
                {
                    add = false;
                }
                else if (c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
            }
            if (add)
            {
                channels.add(new WeakReference<>(channel));
                added = true;
            }
        }

        if (added || removed)
            firePropertyChange(CHANNELS_PROPERTY_NAME, null, null);

        return added;
    }

    /**
     * Notifies this <tt>Endpoint</tt> that an associated <tt>Channel</tt> has
     * received or measured a new audio level for a specific (contributing)
     * synchronization source identifier/SSRC.
     *
     * @param channel the <tt>Channel</tt> which has received or measured the
     * specified <tt>audioLevel</tt> for the specified <tt>ssrc</tt>
     * @param ssrc the synchronization source identifier/SSRC of the RTP stream
     * received within the specified <tt>channel</tt> for which the specified
     * <tt>audioLevel</tt> was received or measured
     * @param audioLevel the audio level which was received or measured for the
     * specified <tt>ssrc</tt> received within the specified <tt>channel</tt>
     */
    void audioLevelChanged(Channel channel, long ssrc, int audioLevel)
    {
    }

    /**
     * Gets the number of <tt>RtpChannel</tt>s of this <tt>Endpoint</tt> which,
     * optionally, are of a specific <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> of the <tt>RtpChannel</tt>s to
     * count or <tt>null</tt> to count all <tt>RtpChannel</tt>s of this
     * <tt>Endpoint</tt>
     * @return the number of <tt>RtpChannel</tt>s of this <tt>Endpoint</tt>
     * which, optionally, are of the specified <tt>mediaType</tt>
     */
    public int getChannelCount(MediaType mediaType)
    {
        return getChannels(mediaType).size();
    }

    /**
     * Gets a list with the {@link RtpChannel}s of this {@link Endpoint} with a
     * particular {@link MediaType} (or all of them, if {@code mediaType} is
     * {@code null}).
     *
     * @param mediaType the {@link MediaType} to match. If {@code null}, all
     * channels of this endpoint will be returned.
     * @return a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     */
    public List<RtpChannel> getChannels(MediaType mediaType)
    {
        boolean removed = false;
        List<RtpChannel> channels = new LinkedList<>();

        synchronized (this.channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i
                        = this.channels.iterator();
                    i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if ((c == null) || c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
                else if ((mediaType == null)
                        || mediaType.equals(c.getContent().getMediaType()))
                {
                    channels.add(c);
                }
            }
        }

        if (removed)
            firePropertyChange(CHANNELS_PROPERTY_NAME, null, null);

        return channels;
    }

    /**
     * Returns the display name of this <tt>Endpoint</tt>.
     *
     * @return the display name of this <tt>Endpoint</tt>.
     */
    public String getDisplayName()
    {
        return displayName;
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
     * Returns an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>.
     *
     * @return an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt> or
     * <tt>null</tt> otherwise.
     */
    public SctpConnection getSctpConnection()
    {
        return sctpConnection.get();
    }

    /**
     * @return the {@link Set} of selected endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getSelectedEndpoints()
    {
        return selectedEndpoints;
    }

    /**
     * @return the {@link Set} of pinned endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getPinnedEndpoints()
    {
        return pinnedEndpoints;
    }

    /**
     * Gets the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     *
     * @return the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     */
    public Conference getConference()
    {
        return this.conference;
    }

    /**
     * Checks whether or not this <tt>Endpoint</tt> is considered "expired"
     * ({@link #expire()} method has been called).
     *
     * @return <tt>true</tt> if this instance is "expired" or <tt>false</tt>
     * otherwise.
     */
    public boolean isExpired()
    {
        return expired;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onBinaryData(WebRtcDataStream src, byte[] data)
    {
    }

    /**
     * Notifies this {@code Endpoint} that a {@code ClientHello} has been
     * received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated
     * {@code SctpConnection}
     */
    private void onClientHello(WebRtcDataStream src, JSONObject jsonObject)
    {
        // ClientHello was introduced for (functional) testing purposes. It
        // triggers a ServerHello (response) from Videobridge. The exchange
        // reveals (to the client) that the WebRTC data channel between the
        // (remote) endpoint and the Videobridge is operational.
        try
        {
            src.sendString("{\"colibriClass\":\"ServerHello\"}");
        }
        catch (IOException ioex)
        {
            logger.error(
                    "Failed to respond to a ClientHello over the WebRTC data"
                        + " channel of endpoint " + getID() + "!",
                    ioex);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a specific JSON object has been
     * received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which the specified
     * {@code jsonObject} has been received
     * @param jsonObject the JSON data received by {@code src}
     * @param colibriClass the non-{@code null} value of the mandatory JSON
     * property {@link Videobridge#COLIBRI_CLASS} required of all JSON objects
     * received by the associated {@code SctpConnection}
     */
    private void onJSONData(
            WebRtcDataStream src,
            JSONObject jsonObject,
            Object colibriClass)
    {
        getConference().getVideobridge().getStatistics().
            totalDataChannelMessagesReceived.incrementAndGet();

        if (COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED.equals(colibriClass))
            onSelectedEndpointChangedEvent(src, jsonObject);
        else if (COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED.equals(colibriClass))
            onSelectedEndpointsChangedEvent(src, jsonObject);
        else if (COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED.equals(colibriClass))
            onPinnedEndpointChangedEvent(src, jsonObject);
        else if (COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED.equals(colibriClass))
            onPinnedEndpointsChangedEvent(src, jsonObject);
        else if (COLIBRI_CLASS_CLIENT_HELLO.equals(colibriClass))
            onClientHello(src, jsonObject);
        else if (COLIBRI_CLASS_ENDPOINT_MESSAGE.equals(colibriClass))
            onClientEndpointMessage(src, jsonObject);
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be
     * forwarded to either: a) another client in this conference (1:1 
     * message) or b) all other clients in this conference (broadcast message)
     *
     * @param src the {@link WebRtcDataStream) by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with 
     * {@link Videobridge#COLIBRI_CLASS} EndpointMessage which has been 
     * received by the associated {@code SctpConnection}
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint 
     * in this conference, the message will be treated as a 1:1 message and 
     * forwarded just to that endpoint.  If the 'to' field is an empty 
     * string, the message will be treated as a broadcast and sent to all other
     * endpoints in this conference.
     * 'msgPayload': An opaque payload.  The bridge does not need to know or 
     * care what is contained in the 'msgPayload' field, it will just forward 
     * it blindly.
     *
     * NOTE: This message is designed to allow endpoints to pass their own 
     * application-specific messaging to one another without requiring the 
     * bridge to know of or understand every message type.  These messages 
     * will be forwarded by the bridge using the same DataChannel as other 
     * jitsi messages (e.g. active speaker and last-n notifications).  
     * It is not recommended to send high-volume message traffic on this 
     * channel (e.g. file transfer), such that it may interfere with other 
     * jitsi-messages.
     */
    @SuppressWarnings("unchecked")
    private void onClientEndpointMessage(
            WebRtcDataStream src,
            JSONObject jsonObject)
    {
        String to = (String)jsonObject.get("to");
        jsonObject.put("from", getID());
        if (conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage - the conference has expired");
            return;
        }

        if ("".equals(to))
        {
            // Broadcast message
            List<Endpoint> endpointSubset = new ArrayList<>();
            for (Endpoint endpoint : conference.getEndpoints())
            {
                if (!endpoint.getID().equalsIgnoreCase(getID()))
                {
                    endpointSubset.add(endpoint);
                }
            }
            conference.sendMessageOnDataChannels(
                    jsonObject.toString(), endpointSubset);
        }
        else
        {
            // 1:1 message
            Endpoint ep = conference.getEndpoint(to);
            if (ep != null)
            {
                List<Endpoint> endpointSubset = new ArrayList<>();
                endpointSubset.add(ep);
                conference.sendMessageOnDataChannels(
                        jsonObject.toString(), endpointSubset);
            }
            else
            {
                logger.warn(
                    "Unable to find endpoint " + to
                    + " to send EndpointMessage");
            }
        }
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointChangedEvent}
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onPinnedEndpointChangedEvent(
            WebRtcDataStream src,
            JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newPinnedEndpointID = (String) jsonObject.get("pinnedEndpoint");

        Set<String> newPinnedIDs = Collections.EMPTY_SET;
        if (newPinnedEndpointID != null && !"".equals(newPinnedEndpointID))
        {
            newPinnedIDs = Collections.singleton(newPinnedEndpointID);
        }

        pinnedEndpointsChanged(newPinnedIDs);
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onPinnedEndpointsChangedEvent(
            WebRtcDataStream src,
            JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("pinnedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON ("
                            + getLoggingId() + "):" + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newPinnedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray)
        {
            if (endpointId != null && endpointId instanceof String)
            {
                newPinnedEndpoints.add((String)endpointId);
            }
        }

        if (logger.isDebugEnabled())
        {
            logger.debug(Logger.Category.STATISTICS,
                         "pinned," + getLoggingId()
                             + " pinned=" + newPinnedEndpoints);
        }
        pinnedEndpointsChanged(newPinnedEndpoints);
    }

    private void pinnedEndpointsChanged(Set<String> newPinnedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldPinnedEndpoints = this.pinnedEndpoints;
        if (!oldPinnedEndpoints.equals(newPinnedEndpoints))
        {
            this.pinnedEndpoints = newPinnedEndpoints;

            firePropertyChange(PINNED_ENDPOINTS_PROPERTY_NAME,
                oldPinnedEndpoints, pinnedEndpoints);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a {@code SelectedEndpointChangedEvent}
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onSelectedEndpointChangedEvent(
        WebRtcDataStream src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newSelectedEndpointID = (String) jsonObject.get("selectedEndpoint");

        Set<String> newSelectedIDs = Collections.EMPTY_SET;
        if (newSelectedEndpointID != null && !"".equals(newSelectedEndpointID))
        {
            newSelectedIDs = Collections.singleton(newSelectedEndpointID);
        }

        selectedEndpointsChanged(newSelectedIDs);
    }

    /**
     * Notifies this {@code Endpoint} that a
     * {@code SelectedEndpointsChangedEvent} has been received by the associated
     * {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onSelectedEndpointsChangedEvent(
            WebRtcDataStream src,
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
        Set<String> newSelectedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray)
        {
            if (endpointId != null && endpointId instanceof String)
            {
                newSelectedEndpoints.add((String)endpointId);
            }
        }

        selectedEndpointsChanged(newSelectedEndpoints);
    }

    private void selectedEndpointsChanged(Set<String> newSelectedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldSelectedEndpoints = this.selectedEndpoints;
        if (!oldSelectedEndpoints.equals(newSelectedEndpoints))
        {
            this.selectedEndpoints = newSelectedEndpoints;

            firePropertyChange(SELECTED_ENDPOINTS_PROPERTY_NAME,
                oldSelectedEndpoints, selectedEndpoints);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStringData(WebRtcDataStream src, String msg)
    {
        Object obj;
        JSONParser parser = new JSONParser(); // JSONParser is NOT thread-safe.

        try
        {
            obj = parser.parse(msg);
        }
        catch (ParseException ex)
        {
            logger.warn("Malformed JSON received from endpoint " + getID(), ex);
            obj = null;
        }

        // We utilize JSONObjects only.
        if (obj instanceof JSONObject)
        {
            JSONObject jsonObject = (JSONObject) obj;
            // We utilize JSONObjects with colibriClass only.
            Object colibriClass = jsonObject.get(Videobridge.COLIBRI_CLASS);

            if (colibriClass != null)
            {
                onJSONData(src, jsonObject, colibriClass);
            }
            else
            {
                logger.warn(
                        "Malformed JSON received from endpoint " + getID()
                            + ". JSON object does not contain the colibriClass"
                            + " field.");
            }
        }
    }

    /**
     * Removes a specific <tt>Channel</tt> from the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to remove from the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean removeChannel(RtpChannel channel)
    {
        if (channel == null)
            return false;

        boolean removed = false;

        synchronized (channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                Channel c = i.next().get();

                if ((c == null) || c.equals(channel) || c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
            }
        }

        if (removed)
            firePropertyChange(CHANNELS_PROPERTY_NAME, null, null);

        return removed;
    }

    /**
     * Notifies this <tt>Endpoint</tt> that its associated
     * <tt>SctpConnection</tt> has become ready i.e. connected to the remote
     * peer and operational.
     *
     * @param sctpConnection the <tt>SctpConnection</tt> which has become ready
     * and is the cause of the method invocation
     */
    void sctpConnectionReady(SctpConnection sctpConnection)
    {
        if (sctpConnection.equals(getSctpConnection())
                && !sctpConnection.isExpired()
                && sctpConnection.isReady())
        {
            for (RtpChannel channel : getChannels(null))
                channel.sctpConnectionReady(this);

            WebRtcDataStream dataStream;

            try
            {
                dataStream = sctpConnection.getDefaultDataStream();
                dataStream.setDataCallback(this);
            }
            catch (IOException e)
            {
                logger.error("Could not get the default data stream.", e);
            }
        }
    }

    /**
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     * @throws IOException
     */
    public void sendMessageOnDataChannel(String msg)
        throws IOException
    {
        SctpConnection sctpConnection = getSctpConnection();
        String endpointId = getID();

        if (sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId + ".");
        }
        else if (sctpConnection.isReady())
        {
            try
            {
                WebRtcDataStream dataStream
                    = sctpConnection.getDefaultDataStream();

                if (dataStream == null)
                {
                    logger.warn(
                            "WebRtc data channel with " + endpointId
                                + " not opened yet.");
                }
                else
                {
                    dataStream.sendString(msg);
                    getConference().getVideobridge().getStatistics()
                            .totalDataChannelMessagesSent.incrementAndGet();
                }
            }
            catch (IOException e)
            {
                // We _don't_ want to silently fail to deliver a message because
                // some functions of the bridge depends on being able to
                // reliably deliver a message through data channels.
                throw e;
            }
        }
        else
        {
            logger.warn(
                    "SCTP connection with " + endpointId + " not ready yet.");
        }
    }

    /**
     * Sets the display name of this <tt>Endpoint</tt>.
     *
     * @param displayName the display name to set on this <tt>Endpoint</tt>.
     */
    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    /**
     * Sets the <tt>SctpConnection</tt> associated with this <tt>Endpoint</tt>.
     *
     * @param sctpConnection the <tt>SctpConnection</tt> to be bound to this
     * <tt>Endpoint</tt>.
     */
    public void setSctpConnection(SctpConnection sctpConnection)
    {
        Object oldValue = getSctpConnection();

        if (!Objects.equals(oldValue, sctpConnection))
        {
            if (oldValue != null && sctpConnection != null)
            {
                // This is not necessarily invalid, but with the current
                // codebase it likely indicates a problem. If we start to
                // actually use it, this warning should be removed.
                logger.warn("Replacing an Endpoint's SctpConnection.");
            }

            this.sctpConnection = new WeakReference<>(sctpConnection);

            firePropertyChange(
                    SCTP_CONNECTION_PROPERTY_NAME,
                    oldValue, getSctpConnection());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return getClass().getName() + " " + getID();
    }

    /**
     * Expires this <tt>Endpoint</tt>.
     */
    public void expire()
    {
        this.expired = true;
    }

    /**
     * @return a string which identifies this {@link Endpoint} for the
     * purposes of logging. The string is a comma-separated list of "key=value"
     * pairs.
     */
    public String getLoggingId()
    {
        return loggingId;
    }

    /**
     * Gets an array that contains all the {@link MediaStreamTrackDesc} of the
     * specified media type associated with this {@link Endpoint}.
     *
     * @param mediaType the media type of the {@link MediaStreamTrackDesc} to
     * get.
     * @return an array that contains all the {@link MediaStreamTrackDesc} of
     * the specified media type associated with this {@link Endpoint}, or null.
     */
    public MediaStreamTrackDesc[] getMediaStreamTracks(MediaType mediaType)
    {
        List<RtpChannel> videoChannels = getChannels(mediaType);

        if (videoChannels == null || videoChannels.isEmpty())
        {
            return null;
        }

        MediaStreamTrackDesc[] ret = videoChannels.get(0)
            .getStream().getMediaStreamTrackReceiver().getMediaStreamTracks();

        if (videoChannels.size() > 1)
        {
            // XXX At the time of this writing each endpoint has a single
            // video channel.
            for (int i = 1; i < videoChannels.size(); i++)
            {
                ret = ArrayUtils.concat(ret, videoChannels.get(i).getStream()
                    .getMediaStreamTrackReceiver().getMediaStreamTracks());
            }
        }

        return ret;
    }
}
