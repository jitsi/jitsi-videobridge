/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.shim;

import org.jetbrains.annotations.*;
import org.jitsi.utils.*;
import org.jitsi.utils.collections.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Handles Colibri-related logic for a {@code Content}, e.g.
 * creates and expires channels.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ContentShim
{
    /**
     * Generates a new <tt>Channel</tt> ID which is not guaranteed to be unique.
     *
     * @return a new <tt>Channel</tt> ID which is not guaranteed to be unique.
     */
    private static String generateChannelID()
    {
        return
            Long.toHexString(
                System.currentTimeMillis() + Videobridge.RANDOM.nextLong());
    }

    /**
     * The {@link Logger}
     */
    private final Logger logger;

    /**
     * The media type of this content.
     */
    private final MediaType mediaType;

    /**
     * The parent conference.
     */
    @NotNull private final Conference conference;

    /**
     * This {@link ContentShim}'s channels.
     */
    private final Map<String, ChannelShim> channels = new ConcurrentHashMap<>();

    /**
     * TODO(brian): this used to be called 'initialLocalSSRC' in the content.
     * 'Initial' presumably because it could be changed in the event of a
     * collision (I think by the other lib?). Since we send it out in the
     * initial offer, assuming it's up to the other side not to conflict with us
     * and therefore it won't need to be changed.
     */
    private final long localSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;

    /**
     * Initializes a new {@link ContentShim} instance.
     * @param conference the parent conference.
     * @param mediaType the media type (audio/video).
     */
    public ContentShim(@NotNull Conference conference, MediaType mediaType, Logger parentLogger)
    {
        this.mediaType = mediaType;
        this.conference = conference;
        this.logger = parentLogger.createChildLogger(
                ContentShim.class.getName(),
                JMap.of("type", mediaType.toString())
        );
    }

    /**
     * @return a new channel ID, unique in the list of this {@link ContentShim}'s
     * channels.
     */
    private String generateUniqueChannelID()
    {
        synchronized (channels)
        {
            String id;
            do
            {
                id = generateChannelID();
            }
            while (channels.containsKey(id));

            return id;
        }
    }

    /**
     * @return  this {@link ContentShim}'s media type.
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    /**
     * Creates a new {@code RtpChannel} and adds it to the list of channels.
     * @param endpointId the ID of the endpoint the channel belongs to.
     * @return the created channel.
     */
    private ChannelShim createRtpChannel(String endpointId)
    {
        synchronized (channels)
        {
            String channelId = generateUniqueChannelID();

            ChannelShim channelShim = new ChannelShim(
                channelId,
                conference.getLocalEndpoint(endpointId),
                localSsrc,
                this,
                logger
            );
            channelShim.getEndpoint().setLocalSsrc(mediaType, localSsrc);
            channels.put(channelId, channelShim);
            return channelShim;
        }
    }

    /**
     * Creates a new {@link SctpConnectionShim} and adds it to the list of
     * channels.
     * @param endpointId the ID of the endpoint that the sctp connection
     * belongs to.
     * @return the created {@link SctpConnectionShim}.
     */
    private SctpConnectionShim createSctpConnection(String endpointId)
    {
        synchronized (channels)
        {
            Endpoint endpoint
                    = conference.getLocalEndpoint(endpointId);
            if (endpoint != null)
            {
                String sctpConnId = generateUniqueChannelID();
                SctpConnectionShim connection
                        = new SctpConnectionShim(sctpConnId, endpoint, this, logger);
                channels.put(sctpConnId, connection);

                // Trigger the creation of the actual new SCTP connection
                endpoint.createSctpConnection();

                return connection;
            }
            else
            {
                throw new Error(
                    "Could not find the Endpoint for a new SctpConnectionShim: "
                            + endpointId);
            }
        }
    }

    /**
     * @return The channel from this content's list with the given ID, or
     * {@code null}.
     */
    private ChannelShim getChannel(String channelId)
    {
        synchronized (channels)
        {
            return channels.get(channelId);
        }
    }

    /**
     * @return the number of channels in this content's list.
     */
    public int getChannelCount()
    {
        return channels.size();
    }

    /**
     * Returns a copy of this content's list of channels.
     */
    List<ChannelShim> getChannelShims()
    {
        synchronized (channels)
        {
            return new ArrayList<>(channels.values());
        }
    }

    /**
     * @return The SCTP connection from this content's list with the given ID,
     * or {@code null}.
     */
    private SctpConnectionShim getSctpConnection(String id)
    {
        synchronized (channels)
        {
            ChannelShim channel = channels.get(id);
            if (channel instanceof SctpConnectionShim)
            {
                return (SctpConnectionShim)channel;
            }
            return null;
        }
    }

    /**
     * Process an incoming {@link ColibriConferenceIQ.Channel} and returns the
     * {@link ChannelShim} associated with it. If {@code channelIq} requests
     * the creation of a new channel, a new channel will be created. Also
     * updates the expire value of the channel. Returns the channel
     * corresponding to {@code channelIq}, or {@code null} if the channel
     * is expired.
     * @param channelIq the received Colibri packet extension which describes
     *                  a channel.
     * @return the channel described by {@code channelIq}, or {@code null} if
     * the channel is expired.
     * @throws IqProcessingException if {@code channelIq}
     * contains invalid fields.
     */
    ChannelShim getOrCreateChannelShim(
            ColibriConferenceIQ.Channel channelIq)
            throws IqProcessingException
    {
        String channelId = channelIq.getID();
        int channelExpire = channelIq.getExpire();
        String channelBundleId = channelIq.getChannelBundleId();
        String endpointId = channelIq.getEndpoint();

        ChannelShim channelShim;
        if (channelId == null)
        {
            if (channelExpire == 0)
            {
                // An expire attribute in the channel element with value
                // equal to zero requests the immediate expiration of the
                // channel in question. Consequently, it does not make sense
                // to have it in a channel allocation request.
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "Channel expire request for empty ID");
            }
            if (endpointId == null)
            {
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "Channel creation requested without endpoint ID");
            }
            if (!endpointId.equals(channelBundleId))
            {
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "Endpoint ID does not match channel bundle ID");
            }
            channelShim = createRtpChannel(endpointId);
            if (channelShim == null)
            {
                throw new IqProcessingException(
                        XMPPError.Condition.internal_server_error,
                        "Error creating channel");
            }
        }
        else
        {
            channelShim = getChannel(channelId);
            if (channelShim == null)
            {
                if (channelExpire == 0)
                {
                    // Channel expired on its own before it was requested to be
                    // expired
                    return null;
                }
                throw new IqProcessingException(
                        XMPPError.Condition.internal_server_error,
                        "Error finding channel " + channelId);
            }
        }

        try
        {
            if (!setExpire(channelShim, channelExpire))
            {
                return null;
            }
        }
        catch (IllegalArgumentException iae)
        {
            throw new IqProcessingException(
                    XMPPError.Condition.bad_request,
                    iae.getMessage());
        }

        return channelShim;
    }

    /**
     * Process an incoming {@link ColibriConferenceIQ.SctpConnection} and
     * returns the {@link SctpConnectionShim} associated with it. If
     * {@code sctpConnectionIq} requests the creation of a new SCTP connection,
     * a new one will be created. Also updates the expire value. Returns
     * {@code null} if the SCTP connection is expired.
     *
     * @param sctpConnectionIq the received Colibri packet extension which
     * describes an SCTP connection.
     *
     * @return the {@link SctpConnectionShim} described by
     * {@code sctpConnectionIq}, or {@code null} if it is expired.
     * @throws IqProcessingException if {@code sctpConnectionIq}
     * contains invalid fields.
     */
    SctpConnectionShim getOrCreateSctpConnectionShim(
            ColibriConferenceIQ.SctpConnection sctpConnectionIq)
            throws IqProcessingException
    {
        String id = sctpConnectionIq.getID();
        String endpointId = sctpConnectionIq.getEndpoint();
        SctpConnectionShim sctpConnectionShim;
        int expire = sctpConnectionIq.getExpire();

        if (id == null)
        {
            if (expire == 0)
            {
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "SCTP connection expire request for empty ID");
            }

            if (endpointId == null)
            {
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "No endpoint ID specified for the new SCTP connection");
            }

            sctpConnectionShim
                    = createSctpConnection(endpointId);
        }
        else
        {
            sctpConnectionShim = getSctpConnection(id);
            if (sctpConnectionShim == null && expire == 0)
            {
                // Expire request, already expired.
                return null;
            }
            else if (sctpConnectionShim == null)
            {
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        "No SCTP connection found for ID: " + id);
            }
        }

        try
        {
            if (!setExpire(sctpConnectionShim, expire))
            {
                return null;
            }
        }
        catch (IllegalArgumentException iae)
        {
            throw new IqProcessingException(
                    XMPPError.Condition.bad_request,
                    iae.getMessage());
        }

        return sctpConnectionShim;
    }

    /**
     * Sets the {@code expire} value of an {@link ChannelShim} (or a
     * {@link SctpConnectionShim}). Returns {@code true} if the given channel is
     * still active (non-expired) after the call.
     * @param channelShim the {@code ChannelShim} on which to set expire.
     * @param expire the value to set.
     * @return {@code true} if after setting the expire value the channel is
     * still active, and {@code false} if the channel is expired (and should
     * therefore not be included in Colibri responses).
     */
    private static boolean setExpire(ChannelShim channelShim, int expire)
    {
        if (expire != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
        {
            if (expire < 0)
            {
                throw new IllegalArgumentException(
                        "Invalid 'expire' value: " + expire);
            }

            channelShim.setExpire(expire);

            // If the request indicates that it wants the channel
            // expired and the channel is indeed expired, then
            // the request is valid and has correctly been acted upon.
            if ((expire == 0) && channelShim.isExpired())
            {
                return false;
            }
        }
        else
        {
            channelShim.setExpire(
                (int)VideobridgeExpireThread.config.getInactivityTimeout().getSeconds());
        }

        return true;
    }

    /**
     * Removes a specific {@link ChannelShim} from this content.
     */
    void removeChannel(ChannelShim channelShim)
    {
        channels.remove(channelShim.getId());
    }
}
