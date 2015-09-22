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
     * The <tt>Logger</tt> used by the <tt>Endpoint</tt> class and its instances
     * to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Endpoint.class);

    /**
     * The name of the <tt>Endpoint</tt> property <tt>pinnedEndpoint</tt> which
     * specifies the JID of the currently pinned <tt>Endpoint</tt> of this
     * <tt>Endpoint</tt>.
     */
    public static final String PINNED_ENDPOINT_PROPERTY_NAME
        = Endpoint.class.getName() + ".pinnedEndpoint";

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
    public static final String SELECTED_ENDPOINT_PROPERTY_NAME
        = Endpoint.class.getName() + ".selectedEndpoint";

    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels
        = new LinkedList<WeakReference<RtpChannel>>();

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
     * The <tt>pinnedEndpointID</tt> SyncRoot.
     */
    private final Object pinnedEndpointSyncRoot = new Object();

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<SctpConnection>(null);

    /**
     * The <tt>selectedEndpointID</tt> SyncRoot.
     */
    private final Object selectedEndpointSyncRoot = new Object();

    /**
     * A weak reference to the <tt>Conference</tt> this <tt>Endpoint</tt>
     * belongs to.
     */
    private final WeakReference<Conference> weakConference;

    /**
     * A weak reference to the currently pinned <tt>Endpoint</tt> at this
     * <tt>Endpoint</tt>.
     */
    private WeakReference<Endpoint> weakPinnedEndpoint;

    /**
     * A weak reference to the currently selected <tt>Endpoint</tt> at this
     * <tt>Endpoint</tt>.
     */
    private WeakReference<Endpoint> weakSelectedEndpoint;

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
        if (id == null)
            throw new NullPointerException("id");

        this.weakConference = new WeakReference<Conference>(conference);
        this.id = id;
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
                channels.add(new WeakReference<RtpChannel>(channel));
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
     * Gets a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt>.
     * @return a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     */
    public List<RtpChannel> getChannels(MediaType mediaType)
    {
        boolean removed = false;
        List<RtpChannel> channels = new LinkedList<RtpChannel>();

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
     * Gets the currently effectively selected <tt>Endpoint</tt> at this
     * <tt>Endpoint</tt>.
     *
     * @return the currently effectively selected <tt>Endpoint</tt> at this
     * <tt>Endpoint</tt>.
     */
    public Endpoint getEffectivelySelectedEndpoint()
    {
        Endpoint pinned = getPinnedEndpoint();
        if (pinned == null)
        {
            WeakReference<Endpoint> wr = this.weakSelectedEndpoint;
            Endpoint e = wr == null ? null : wr.get();

            return e == null || e.expired ? null : e;
        }
        else
        {
            return pinned;
        }
    }

    /**
     * Gets the currently selected <tt>Endpoint</tt> at this <tt>Endpoint</tt>.
     *
     * @return the currently selected <tt>Endpoint</tt> at this
     * <tt>Endpoint</tt>.
     */
    private Endpoint getSelectedEndpoint()
    {
        WeakReference<Endpoint> wr = this.weakSelectedEndpoint;
        Endpoint e = wr == null ? null : wr.get();

        return e == null || e.expired ? null : e;
    }

    /**
     * Gets the currently pinned <tt>Endpoint</tt> at this <tt>Endpoint</tt>.
     *
     * @return the currently pinned <tt>Endpoint</tt> at this <tt>Endpoint</tt>.
     */
    public Endpoint getPinnedEndpoint()
    {
        WeakReference<Endpoint> wr = weakPinnedEndpoint;
        Endpoint e = (wr == null) ? null : wr.get();

        return e == null || e.expired ? null : e;
    }

    /**
     * Gets the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     *
     * @return the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     */
    public Conference getConference()
    {
        WeakReference<Conference> wr = weakConference;

        return (wr == null) ? null : wr.get();
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
        if ("SelectedEndpointChangedEvent".equals(colibriClass))
            onSelectedEndpointChangedEvent(src, jsonObject);
        else if ("PinnedEndpointChangedEvent".equals(colibriClass))
            onPinnedEndpointChangedEvent(src, jsonObject);
        else if ("ClientHello".equals(colibriClass))
            onClientHello(src, jsonObject);
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

        if (logger.isDebugEnabled())
        {
            StringCompiler sc = new StringCompiler();
            sc.bind("pinnedId", newPinnedEndpointID);
            sc.bind("this", this);
            logger.debug(sc.c(
                    "Endpoint {this.id} notified us that it has pinned"
                        + " {pinnedId}."));
        }

        Conference conference = weakConference.get();

        Endpoint newPinnedEndpoint;
        if (!StringUtils.isNullOrEmpty(newPinnedEndpointID)
                && conference != null)
        {
            newPinnedEndpoint = conference.getEndpoint(newPinnedEndpointID);
        }
        else
        {
            newPinnedEndpoint = null;
        }

        // Check if that's different to what we think the pinned endpoint is.
        boolean changed;
        Endpoint oldPinnedEndpoint = this.getPinnedEndpoint();
        synchronized (pinnedEndpointSyncRoot)
        {
            changed = newPinnedEndpoint != oldPinnedEndpoint;
            if (changed)
            {
                if (newPinnedEndpoint == null)
                {
                    this.weakPinnedEndpoint = null;
                }
                else
                {
                    this.weakPinnedEndpoint
                        = new WeakReference<Endpoint>(newPinnedEndpoint);
                }
            }
        }

        // NOTE(gp) This won't guarantee that property change events are fired
        // in the correct order. We should probably call the
        // firePropertyChange() method from inside the synchronized _and_ the
        // underlying PropertyChangeNotifier should have a dedicated events
        // queue and a thread for firing PropertyChangeEvents from the queue.

        if (changed)
        {
            if (logger.isDebugEnabled())
            {
                StringCompiler sc = new StringCompiler();
                sc.bind("pinned", newPinnedEndpoint);
                sc.bind("this", this);
                logger.debug(sc.c("Endpoint {this.id} pinned {pinned.id}."));
            }
            firePropertyChange(PINNED_ENDPOINT_PROPERTY_NAME,
                    oldPinnedEndpoint, newPinnedEndpoint);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a
     * {@code SelectedEndpointChangedEvent} has been received by the associated
     * {@code SctpConnection}.
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
        String newSelectedEndpointID
            = (String) jsonObject.get("selectedEndpoint");

        if (logger.isDebugEnabled())
        {
            StringCompiler sc = new StringCompiler();
            sc.bind("selectedId", newSelectedEndpointID);
            sc.bind("this", this);
            logger.debug(sc.c(
                    "Endpoint {this.id} notified us that its big screen"
                        + " displays endpoint {selectedId}."));
        }

        Conference conference = weakConference.get();

        Endpoint newSelectedEndpoint;
        if (!StringUtils.isNullOrEmpty(newSelectedEndpointID)
                && conference != null)
        {
            newSelectedEndpoint = conference.getEndpoint(newSelectedEndpointID);
        }
        else
        {
            newSelectedEndpoint = null;
        }

        boolean changed;
        Endpoint oldSelectedEndpoint = this.getSelectedEndpoint();
        synchronized (selectedEndpointSyncRoot)
        {
            changed = newSelectedEndpoint != oldSelectedEndpoint;
            if (changed)
            {
                if (newSelectedEndpoint == null)
                {
                    this.weakSelectedEndpoint = null;
                }
                else
                {
                    this.weakSelectedEndpoint
                        = new WeakReference<Endpoint>(newSelectedEndpoint);
                }
            }
        }

        // NOTE(gp) This won't guarantee that property change events are fired
        // in the correct order. We should probably call the
        // firePropertyChange() method from inside the synchronized _and_ the
        // underlying PropertyChangeNotifier should have a dedicated events
        // queue and a thread for firing PropertyChangeEvents from the queue.

        if (changed)
        {
            if (logger.isDebugEnabled())
            {
                StringCompiler sc = new StringCompiler();
                sc.bind("selected", newSelectedEndpoint);
                sc.bind("this", this);
                logger.debug(sc.c(
                        "Endpoint {this.id} selected {selected.id}."));
            }
            firePropertyChange(SELECTED_ENDPOINT_PROPERTY_NAME,
                oldSelectedEndpoint, newSelectedEndpoint);
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

        if(sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId + ".");
        }
        else if(sctpConnection.isReady())
        {
            try
            {
                WebRtcDataStream dataStream
                    = sctpConnection.getDefaultDataStream();

                if(dataStream == null)
                {
                    logger.warn(
                            "WebRtc data channel with " + endpointId
                                + " not opened yet.");
                }
                else
                {
                    dataStream.sendString(msg);
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

        if ((sctpConnection == null)
                ? (oldValue != null)
                : !sctpConnection.equals(oldValue))
        {
            this.sctpConnection
                = new WeakReference<SctpConnection>(sctpConnection);

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
}
