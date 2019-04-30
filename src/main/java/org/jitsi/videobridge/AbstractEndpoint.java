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

import org.jitsi.nlj.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;

import java.beans.*;
import java.io.*;
import java.util.*;

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a "remote" endpoint connected to another bridge in the
 * same conference (if Octo is being used).
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
public abstract class AbstractEndpoint extends PropertyChangeNotifier
    implements EncodingsManager.EncodingsUpdateListener
{
    public static final String ENDPOINT_CHANGED_PROPERTY_NAME =
            AbstractEndpoint.class.getName() + ".endpoint_changed";
    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
            = Logger.getLogger(AbstractEndpoint.class);

    /**
     * The instance logger.
     */
    private final Logger logger;

    /**
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The last-N filter for this endpoint.
     */
    private final LastNFilter lastNFilter;

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
     * The string used to identify this endpoint for the purposes of logging.
     */
    protected final String logPrefix;

    /**
     * Initializes a new {@link AbstractEndpoint} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    protected AbstractEndpoint(Conference conference, String id)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        logPrefix
            = "[id=" + id + " conference=" + conference.getID() + "] ";
        logger = Logger.getLogger(classLogger, conference.getLogger());
        this.id = Objects.requireNonNull(id, "id");
        this.lastNFilter = new LastNFilter(id);
    }

    /**
     * Notifies this {@code Endpoint} that the list of {@code Endpoint}s ordered
     * by speech activity (i.e. the dominant speaker history) has changed.
     */
    void speechActivityEndpointsChanged(List<String> endpoints)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix +
                    "Got notified about active endpoints: " + endpoints);
        }
        lastNFilter.setEndpointsSortedByActivity(endpoints);
    }

    /**
     * Sets the last-n value for this endpoint.
     * @param lastN
     */
    public void setLastN(Integer lastN)
    {
        lastNFilter.setLastNValue(lastN);
    }

    /**
     * Set the maximum frame height, in pixels, of video streams that can be
     * forwarded to this participant.
     *
     * @param maxReceiveFrameHeightPx the maximum frame height, in pixels, of
     * video streams that can be forwarded to this participant.
     */
    public void setMaxReceiveFrameHeightPx(int maxReceiveFrameHeightPx) { }

    /**
     * Gets the LastN value for this endpoint (how many endpoints this
     * receiver is willing to receive video for).
     */
    public Integer getLastN()
    {
        return lastNFilter.getLastNValue();
    }

    /**
     * Checks whether this {@link Endpoint} wants to receive a specific
     * packet coming from a specific source endpoint.
     *
     * This method is called before the packet is actually forwarded to the
     * endpoint in order to avoid making a copy (when {@code wants} returns
     * false).
     *
     * If, instead, we had a scheme where we pass a packet reference and the
     * egress pipeline copies once it decides the packet should be accepted (or,
     * even better, a packet is automatically copied on first modification) then
     * we could avoid this separate call and just have the pipeline
     *
     * @param packetInfo the packet.
     * @param sourceEndpointId the ID of the source endpoint.
     * @return {@code true} if this endpoints wants to receive the packet, and
     * {@code false} otherwise.
     */
    protected boolean wants(PacketInfo packetInfo, String sourceEndpointId)
    {
        // We always want audio packets
        if (packetInfo.getPacket() instanceof AudioRtpPacket)
        {
            return true;
        }
        // Video packets require more checks:
        // First check if this endpoint fits in lastN
        //TODO(brian): this doesn't take into account adaptive-last-n
        return lastNFilter.wants(sourceEndpointId);
    }

    /**
     * Checks whether a specific SSRC belongs to this endpoint.
     * @param ssrc
     * @return
     */
    public abstract boolean receivesSsrc(long ssrc);

    /**
     * Gets the media type of an SSRC which belongs to this endpoint.
     */
    public abstract MediaType getMediaType(long ssrc);

    /**
     * Adds an SSRC to this endpoint.
     * @param ssrc
     */
    public abstract void addReceiveSsrc(long ssrc, MediaType mediaType);

    /**
     * @return the {@link AbstractEndpointMessageTransport} associated with
     * this endpoint.
     */
    public AbstractEndpointMessageTransport getMessageTransport()
    {
        return null;
    }

    /**
     * Gets the list of media stream tracks that belong to this endpoint.
     */
    abstract public MediaStreamTrackDesc[] getMediaStreamTracks();

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
        logger.info(logPrefix + "Expiring.");
        this.expired = true;

        Conference conference = getConference();
        if (conference != null)
        {
            conference.endpointExpired(this);
        }
    }

    /**
     * Return true if this endpoint should expire (based on whatever logic is
     * appropriate for that endpoint implementation.
     *
     * NOTE(brian): Currently the bridge will automatically expire an endpoint
     * if all of its channel shims are removed. Maybe we should instead have
     * this logic always be called before expiring instead? But that would mean
     * that expiration in the case of channel removal would take longer.
     *
     * @return true if this endpoint should expire, false otherwise
     */
    public abstract boolean shouldExpire();

    /**
     * Get the last 'activity' (packets received or packets sent) this endpoint has seen
     * @return the timestamp, in milliseconds, of the last activity of this endpoint
     */
    public long getLastActivity()
    {
        return 0;
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

    /**
     * Recreates this {@link AbstractEndpoint}'s media stream tracks based
     * on the sources (and source groups) described in it's video channel.
     */
    public void recreateMediaStreamTracks()
    {
    }

    /**
     * Describes this endpoint's transport in the given channel bundle XML
     * element.
     *
     * @param channelBundle the channel bundle element to describe in.
     */
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
            throws IOException
    {
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("displayName", displayName);
        debugState.put("expired", expired);
        debugState.put("statsId", statsId);

        return debugState;
    }
}
