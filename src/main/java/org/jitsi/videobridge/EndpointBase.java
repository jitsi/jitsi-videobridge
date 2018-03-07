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
import org.jitsi.service.neomedia.*;
import org.jitsi.util.event.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.stream.*;

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a "remote" endpoint connected to another bridge in the
 * same conference (if Octo is being used).
 *
 * @author Boris Grozev
 */
public abstract class EndpointBase extends PropertyChangeNotifier
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
     * Caches the list of video {@link MediaStreamTrackDesc} associated with
     * this endpoint. We cache this to avoid re-creating it because it is
     * accessed often.
     */
    private List<MediaStreamTrackDesc> mediaStreamTracks = new LinkedList<>();

    /**
     * Initializes a new {@link EndpointBase} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    EndpointBase(Conference conference, String id)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        this.id = Objects.requireNonNull(id, "id");
        loggingId = conference.getLoggingId() + ",endp_id=" + id;
    }

    /**
     * Adds a specific {@link RtpChannel} to the list of channels
     * associated with this {@link EndpointBase}.
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
     * Expires this {@link EndpointBase}.
     */
    public void expire()
    {
        mediaStreamTracks = new LinkedList<>();
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
     * @return the list of all video {@link MediaStreamTrackDesc} associated
     * this {@link EndpointBase}. Always return non-null.
     */
    public List<MediaStreamTrackDesc> getMediaStreamTracks()
    {
        return mediaStreamTracks;
    }

    /**
     * Updates the cached list of {@link MediaStreamTrackDesc} associated
     * with this endpoint.
     */
    void updateMediaStreamTracks()
    {
        List<RtpChannel> channels = getChannels(MediaType.VIDEO);

        if (channels == null || channels.isEmpty())
        {
            mediaStreamTracks = new LinkedList<>();
            return;
        }

        List<MediaStreamTrackDesc> allTracks = new LinkedList<>();
        channels.stream()
            .map(channel -> channel.getStream().getMediaStreamTrackReceiver())
            .forEach(
                trackReceiver -> allTracks.addAll(
                    Arrays.asList(trackReceiver.getMediaStreamTracks())));

        mediaStreamTracks
            = allTracks.stream()
                .filter(track -> getID().equals(track.getOwner()))
                .collect(Collectors.toList());
    }

    /**
     * Sends a specific {@link String} {@code msg} to the remote end of this
     * endpoint.
     *
     * @param msg message text to send.
     */
    public abstract void sendMessage(String msg)
        throws IOException;
}
