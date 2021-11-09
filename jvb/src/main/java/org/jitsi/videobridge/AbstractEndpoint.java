/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.allocation.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.json.simple.*;

import java.io.*;
import java.time.*;
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
public abstract class AbstractEndpoint
    implements MediaSourceContainer
{
    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * The {@link Logger} used by the {@link AbstractEndpoint} class to print debug
     * information.
     */
    protected final Logger logger;

    /**
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The map of receiver endpoint id -> video constraints.
     */
    private final ReceiverConstraintsMap receiverVideoConstraintsMap = new ReceiverConstraintsMap();

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
     * The maximum set of constraints applied by all receivers of this endpoint
     * in the conference. The client needs to send _at least_ this to satisfy
     * all receivers.
     */
    protected VideoConstraints maxReceiverVideoConstraints = new VideoConstraints(0, 0.0);

    protected final EventEmitter<EventHandler> eventEmitter = new SyncEventEmitter<>();

    /**
     * The latest {@link VideoType} signaled by the endpoint (defaulting to {@code CAMERA} if nothing has been
     * signaled).
     *
     * Deprecated: the video type has been moved to {@link MediaSourceDesc}.
     */
    @Deprecated
    private VideoType videoType = VideoType.CAMERA;

    /**
     * Initializes a new {@link AbstractEndpoint} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    protected AbstractEndpoint(Conference conference, String id, Logger parentLogger)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        Map<String, String> context = new HashMap<>();
        context.put("epId", id);
        logger = parentLogger.createChildLogger(this.getClass().getName(), context);
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Return the {@link VideoType} that the endpoint has advertised as available. This reflects the type of stream
     * even when the stream is suspended (e.g. due to no receivers being subscribed to it).
     *
     * In other words, CAMERA or SCREENSHARE means that the endpoint has an available stream, which may be suspended.
     * NONE means that the endpoint has signaled that it has no available streams.
     *
     * Note that when the endpoint has not advertised any video sources, the video type is necessarily {@code NONE}.
     *
     * Deprecated: use {@link MediaSourceDesc#getVideoType()} instead.
     */
    @NotNull
    @Deprecated
    public VideoType getVideoType()
    {
        if (getMediaSource() == null)
        {
            return VideoType.NONE;
        }
        return videoType;
    }

    public void setVideoType(String sourceName, VideoType videoType) {
        MediaSourceDesc mediaSourceDesc = findMediaSourceDesc(sourceName);

        if (mediaSourceDesc != null)
        {
            if (mediaSourceDesc.getVideoType() != videoType)
            {
                mediaSourceDesc.setVideoType(videoType);
                conference.getSpeechActivity().endpointVideoAvailabilityChanged();
            }
        }
        else
        {
            logger.error("setVideoType - source description not found for: " + sourceName);
        }
    }

    // Use setVideoType(String sourceName, VideoType videoType) instead.
    @Deprecated
    public void setVideoType(VideoType videoType)
    {
        if (this.videoType != videoType)
        {
            this.videoType = videoType;
            conference.getSpeechActivity().endpointVideoAvailabilityChanged();
        }
    }

    /**
     * Checks whether a specific SSRC belongs to this endpoint.
     * @param ssrc
     * @return
     */
    public abstract boolean receivesSsrc(long ssrc);

    /**
     * Adds an SSRC to this endpoint.
     * @param ssrc the receive SSRC being added
     * @param mediaType the {@link MediaType} of the added SSRC
     */
    public abstract void addReceiveSsrc(long ssrc, MediaType mediaType);

    /**
     * @return the {@link AbstractEndpointMessageTransport} associated with
     * this endpoint.
     */
    public AbstractEndpointMessageTransport<?> getMessageTransport()
    {
        return null;
    }

    /**
     * Gets the description of the video {@link MediaSourceDesc} that this endpoint has advertised, or {@code null} if
     * it hasn't advertised any video sources.
     */
    @Nullable
    public abstract MediaSourceDesc getMediaSource();

    protected MediaSourceDesc findMediaSourceDesc(String sourceName)
    {
        for (MediaSourceDesc desc : getMediaSources())
        {
            if (sourceName.equals(desc.getSourceName()))
            {
                return desc;
            }
        }

        return null;
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
    public final String getId()
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

    void addEventHandler(EventHandler eventHandler)
    {
        eventEmitter.addHandler(eventHandler);
    }

    void removeEventHandler(EventHandler eventHandler)
    {
        eventEmitter.removeHandler(eventHandler);
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
     * Sets the stats Id of this <tt>Endpoint</tt>.
     *
     * @param value the stats Id value to set on this <tt>Endpoint</tt>.
     */
    public void setStatsId(String value)
    {
        this.statsId = value;
        if (value != null)
        {
            logger.addContext("stats_id", value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return getClass().getName() + " " + getId();
    }

    /**
     * Expires this {@link AbstractEndpoint}.
     */
    public void expire()
    {
        logger.info("Expiring.");
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
     * Get the last 'incoming activity' (packets received) this endpoint has seen
     * @return the timestamp, in milliseconds, of the last activity of this endpoint
     */
    public Instant getLastIncomingActivity()
    {
        return ClockUtils.NEVER;
    }

    /**
     * Sends a specific {@link String} {@code msg} to the remote end of this
     * endpoint.
     *
     * @param msg message text to send.
     */
    public abstract void sendMessage(BridgeChannelMessage msg)
        throws IOException;


    /**
     * Requests a keyframe from this endpoint for the specified media SSRC.
     *
     * @param mediaSsrc the media SSRC to request a keyframe from.
     */
    public abstract void requestKeyframe(long mediaSsrc);

    /**
     * Requests a keyframe from this endpoint on the first video SSRC
     * it finds.  Being able to request a  keyframe without passing a specific
     * SSRC is useful for things like requesting a pre-emptive keyframes when a new
     * active speaker is detected (where it isn't convenient to try and look up
     * a particular SSRC).
     */
    public abstract void requestKeyframe();

    /**
     * Recreates this {@link AbstractEndpoint}'s media sources based
     * on the sources (and source groups) described in its video channel.
     */
    public void recreateMediaSources()
    {
    }

    /**
     * Describes this endpoint's transport in the given channel bundle XML
     * element.
     *
     * @param channelBundle the channel bundle element to describe in.
     */
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
    {
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("receiverVideoConstraints", receiverVideoConstraintsMap.getDebugState());
        debugState.put("maxReceiverVideoConstraints", maxReceiverVideoConstraints);
        debugState.put("expired", expired);
        debugState.put("statsId", statsId);

        return debugState;
    }

    /**
     * Computes and sets the {@link #maxReceiverVideoConstraints} from the
     * specified video constraints.
     *
     * @param newMaxHeight the maximum height resulting from the current set of constraints.
     *                     (Currently we only support constraining the height, and not frame rate.)
     */
    private void receiverVideoConstraintsChanged(int newMaxHeight)
    {
        VideoConstraints oldReceiverMaxVideoConstraints = this.maxReceiverVideoConstraints;


        VideoConstraints newReceiverMaxVideoConstraints = new VideoConstraints(newMaxHeight, -1.0);

        if (!newReceiverMaxVideoConstraints.equals(oldReceiverMaxVideoConstraints))
        {
            maxReceiverVideoConstraints = newReceiverMaxVideoConstraints;
            sendVideoConstraints(newReceiverMaxVideoConstraints);
        }
    }

    /**
     * Whether the remote endpoint is currently sending (non-silence) audio.
     */
    public abstract boolean isSendingAudio();

    /**
     * Whether the remote endpoint is currently sending video.
     */
    public abstract boolean isSendingVideo();

    /**
     * Adds a payload type to this endpoint.
     */
    public abstract void addPayloadType(PayloadType payloadType);

    /**
     * Adds an RTP extension to this endpoint
     */
    public abstract void addRtpExtension(RtpExtension rtpExtension);

    /**
     * Notifies this instance that the max video constraints that the bridge
     * needs to receive from this endpoint has changed. Each implementation
     * handles this notification differently.
     *
     * @param maxVideoConstraints the max video constraints that the bridge
     * needs to receive from this endpoint
     */
    protected abstract void
    sendVideoConstraints(@NotNull VideoConstraints maxVideoConstraints);

    /**
     * Notifies this instance that a specified received wants to receive
     * the specified video constraints from the endpoint attached to this
     * instance (the sender).
     *
     * The receiver can be either another endpoint, or a remote bridge.
     *
     * @param receiverId the id that specifies the receiver endpoint
     * @param newVideoConstraints the video constraints that the receiver
     * wishes to receive.
     */
    public void addReceiver(String receiverId, VideoConstraints newVideoConstraints)
    {
        VideoConstraints oldVideoConstraints = receiverVideoConstraintsMap.put(receiverId, newVideoConstraints);
        if (oldVideoConstraints == null || !oldVideoConstraints.equals(newVideoConstraints))
        {
            logger.debug(
                () -> "Changed receiver constraints: " + receiverId + ": " + newVideoConstraints.getMaxHeight());
            receiverVideoConstraintsChanged(receiverVideoConstraintsMap.getMaxHeight());
        }
    }

    /**
     * Notifies this instance that the specified receiver no longer wants or
     * needs to receive anything from the endpoint attached to this
     * instance (the sender).
     *
     * @param receiverId the id that specifies the receiver endpoint
     */
    public void removeReceiver(String receiverId)
    {
        if (receiverVideoConstraintsMap.remove(receiverId) != null)
        {
            logger.debug(() -> "Removed receiver " + receiverId);
            receiverVideoConstraintsChanged(receiverVideoConstraintsMap.getMaxHeight());
        }
    }

    interface EventHandler {
        void iceSucceeded();
        void iceFailed();
        void sourcesChanged();
    }
}
