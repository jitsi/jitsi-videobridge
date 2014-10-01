/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<SctpConnection>(null);

    /**
     * the (unique) identifier/ID of the currently selected <tt>Endpoint</tt>
     * at this <tt>Endpoint</tt>.
     */
    private String selectedEndpointID;

    /**
     * The <tt>selectedEndpointID</tt> SyncRoot.
     */
    private final Object selectedEndpointSyncRoot = new Object();

    /**
     * A constant that means that an endpoint is not watching video from any
     * other endpoint.
     */
    public static final String SELECTED_ENDPOINT_NOT_WATCHING_VIDEO
        = "SELECTED_ENDPOINT_NOT_WATCHING_VIDEO";

    /**
     * Gets the (unique) identifier/ID of the currently selected
     * <tt>Endpoint</tt> at this <tt>Endpoint</tt>.
     *
     * @return the (unique) identifier/ID of the currently selected
     * <tt>Endpoint</tt> at this <tt>Endpoint</tt>.
     */
    public String getSelectedEndpointID()
    {
        return selectedEndpointID;
    }

    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     */
    public Endpoint(String id)
    {
        if (id == null)
            throw new NullPointerException("id");

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
        /*
         * The expire state of Channel is final. Adding an expired Channel to
         * an Endpoint is a no-op.
         */
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
        // TODO Auto-generated method stub
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
        List<RtpChannel> channels = new LinkedList<RtpChannel>();

        synchronized (this.channels)
        {
            for (WeakReference<RtpChannel> wr : this.channels)
            {
                RtpChannel channel = wr.get();

                if ((channel != null)
                        && !channel.isExpired()
                        && ((mediaType == null)
                                || mediaType.equals(
                                        channel.getContent().getMediaType())))
                {
                    channels.add(channel);
                }
            }
        }
        return channels;
    }

    /**
     * Returns the display name of this <tt>Endpoint</tt>.
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
     * @return an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>
     *         or <tt>null</tt> otherwise.
     */
    public SctpConnection getSctpConnection()
    {
        return sctpConnection.get();
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
     */
    public void sendMessageOnDataChannel(String msg)
            throws IOException
    {
        SctpConnection sctpConnection = getSctpConnection();
        String endpointId = getID();

        if(sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId);
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
                            "WebRtc data channel not opened yet " + endpointId);
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
                    "SCTP connection with " + endpointId + " not ready yet");
        }
    }

    /**
     * Sets the display name of this <tt>Endpoint</tt>.
     * @param displayName the display name to set.
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

    @Override
    public void onStringData(WebRtcDataStream src, String msg)
    {
        // JSONParser is NOT thread-safe.
        JSONParser parser = new JSONParser();
        JSONObject jsonObject;

        try
        {
            Object obj = parser.parse(msg);

            // We utilize JSONObjects only.
            if (obj instanceof JSONObject)
                jsonObject = (JSONObject) obj;
            else
                return;
        }
        catch (ParseException e)
        {
            logger.warn("Malformed JSON received from endpoint " + getID(), e);
            return;
        }

        // We utilize JSONObjects with colibriClass only.
        Object colibriClass = jsonObject.get(Videobridge.COLIBRI_CLASS);

        if (colibriClass != null)
        {
            if ("SelectedEndpointChangedEvent".equals(colibriClass))
            {
                String oldSelectedEndpoint, newSelectedEndpoint;
                boolean changed;

                synchronized (selectedEndpointSyncRoot)
                {
                    oldSelectedEndpoint = this.selectedEndpointID;
                    newSelectedEndpoint
                        = (String) jsonObject.get("selectedEndpoint");
                    if (newSelectedEndpoint == null
                            || newSelectedEndpoint.length() == 0)
                    {
                        newSelectedEndpoint
                                = SELECTED_ENDPOINT_NOT_WATCHING_VIDEO;
                    }

                    changed = !newSelectedEndpoint.equals(oldSelectedEndpoint);
                    if (changed)
                    {
                        this.selectedEndpointID = newSelectedEndpoint;
                    }
                }

                // NOTE(gp) This won't guarantee that property change events are
                // fired in the correct order. We should probably call the
                // firePropertyChange() method from inside the synchronized
                // _and_ the underlying PropertyChangeNotifier should have a
                // dedicated events queue and a thread for firing
                // PropertyChangeEvents from the queue.

                if (changed)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(
                                "Endpoint " + getID() + " selected "
                                    + newSelectedEndpoint);
                    }
                    firePropertyChange(
                            SELECTED_ENDPOINT_PROPERTY_NAME,
                            oldSelectedEndpoint, newSelectedEndpoint);
                }
            }
        }
        else
        {
            logger.warn(
                    "Malformed JSON received from endpoint " + getID()
                        + ". JSON object does not contain the colibriClass"
                        + " field.");
        }
    }

    @Override
    public void onBinaryData(WebRtcDataStream src, byte[] data)
    {
        // Nothing to do here for the time being.
    }
}
