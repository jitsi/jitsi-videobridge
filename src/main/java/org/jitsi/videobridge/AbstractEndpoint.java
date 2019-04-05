/*
 * Copyright @ 2015-2018 Atlassian Pty Ltd
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

import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a "remote" endpoint connected to another bridge in the
 * same conference (if Octo is being used).
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpoint extends PropertyChangeNotifier
{
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
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels = new LinkedList<>();

    /**
     * The (human readable) display name of this <tt>Endpoint</tt>.
     */
    private String displayName;

    /**
     * The statistic Id of this <tt>Endpoint</tt>.
     */
    private String statsId;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Endpoint</tt>.
     */
    private boolean expired = false;

    /**
     * Initializes a new {@link AbstractEndpoint} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    protected AbstractEndpoint(Conference conference, String id)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        this.id = Objects.requireNonNull(id, "id");
        loggingId = conference.getLoggingId() + ",endp_id=" + id;
    }

    /**
     * @return the {@link AbstractEndpointMessageTransport} associated with
     * this endpoint.
     */
    public AbstractEndpointMessageTransport getMessageTransport()
    {
        return null;
    }

    /**
     * Adds a specific {@link RtpChannel} to the list of channels
     * associated with this {@link AbstractEndpoint}.
     *
     * @param channel the {@link RtpChannel }to add.
     * @return {@code true} if the list of {@link RtpChannel}s associated with
     * this endpoint changed as a result of the method invocation; otherwise,
     * {@code false}.
     */
    public boolean addChannel(RtpChannel channel)
    {
        Objects.requireNonNull(channel, "channel");

        // The expire state of Channel is final. Adding an expired Channel to
        // an Endpoint is a no-op.
        if (channel.isExpired())
        {
            return false;
        }

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

        if (removed)
        {
            maybeExpire();
        }

        return added;
    }

    /**
     * Gets the number of {@link RtpChannel}s of this endpoint which,
     * optionally, are of a specific {@link MediaType}.
     *
     * @param mediaType the {@link MediaType} of the {@link RtpChannel}s to
     * count or {@code null} to count all {@link RtpChannel}s.
     * @return the number of {@link RtpChannel}s of this endpoint which,
     * , optionally, are of the specified {@link MediaType}.
     */
    int getChannelCount(MediaType mediaType)
    {
        return getChannels(mediaType).size();
    }

    /**
     * @return a list with all {@link RtpChannel}s of this {@link Endpoint}.
     */
    public List<RtpChannel> getChannels()
    {
        return getChannels(null);
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
        {
            maybeExpire();
        }

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
     * Returns the stats Id of this <tt>Endpoint</tt>.
     *
     * @return the stats Id of this <tt>Endpoint</tt>.
     */
    public String getStatsId()
    {
        return statsId;
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
     * Gets the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     *
     * @return the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     */
    public Conference getConference()
    {
        return conference;
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
        {
            return false;
        }

        boolean removed;

        synchronized (channels)
        {
            removed = channels.removeIf(w -> {
                Channel c = w.get();
                return c == null || c.equals(channel) || c.isExpired();
            });
        }

        if (removed)
        {
            maybeExpire();
        }

        return removed;
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
     * Sets the stats Id of this <tt>Endpoint</tt>.
     *
     * @param value the stats Id value to set on this <tt>Endpoint</tt>.
     */
    public void setStatsId(String value)
    {
        this.statsId = value;
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
     * Expires this {@link AbstractEndpoint}.
     */
    public void expire()
    {
        this.expired = true;
        getConference().endpointExpired(this);
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
     * Expires this {@link Endpoint} if it has no channels and no SCTP
     * connection.
     */
    protected void maybeExpire()
    {}

    /**
     * @return the {@link Set} of selected endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getSelectedEndpoints()
    {
        return Collections.EMPTY_SET;
    }

    /**
     * @return the {@link Set} of pinned endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getPinnedEndpoints()
    {
        return Collections.EMPTY_SET;
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
        return
            getAllMediaStreamTracks(mediaType)
                .toArray(new MediaStreamTrackDesc[0]);
    }

    /**
     * @return all {@link MediaStreamTrackDesc} of all channels of type
     * {@code mediaType} associated with this endpoint. Note that this may
     * include {@link MediaStreamTrackDesc}s which do not belong to this
     * endpoint.
     * @param mediaType the media type of the {@link MediaStreamTrackDesc} to
     * get.
     */
    protected List<MediaStreamTrackDesc> getAllMediaStreamTracks(
        MediaType mediaType)
    {
        List<RtpChannel> channels = getChannels(mediaType);

        if (channels == null || channels.isEmpty())
        {
            return Collections.EMPTY_LIST;
        }

        List<MediaStreamTrackDesc> allTracks = new LinkedList<>();
        channels.stream()
            .map(channel -> channel.getStream().getMediaStreamTrackReceiver())
            .filter(Objects::nonNull)
            .forEach(
                trackReceiver -> allTracks.addAll(
                    Arrays.asList(trackReceiver.getMediaStreamTracks())));
        return allTracks;
    }


    /**
     * Sends a specific {@link String} {@code msg} to the remote end of this
     * endpoint.
     *
     * @param msg message text to send.
     */
    public abstract void sendMessage(String msg)
        throws IOException;

    /**
     * Notify this endpoint that another endpoint has set it
     * as a 'selected' endpoint, meaning its HD stream has another
     * consumer.
     */
    public void incrementSelectedCount()
    {
        // No-op
    }

    /**
     * Notify this endpoint that another endpoint has stopped consuming
     * its HD stream.
     */
    public void decrementSelectedCount()
    {
        // No-op
    }
}
