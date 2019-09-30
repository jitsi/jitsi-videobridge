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
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;

import java.io.*;
import java.util.*;

/**
 * Handles Colibri-related logic for a {@link Conference}, e.g.
 * creates/expires contents, describes the conference in XML.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ConferenceShim
{
    /**
     * The {@link Logger} used by the {@link ConferenceShim} class to print
     * debug information.
     */
    private final Logger logger;

    /**
     * The corresponding {@link Conference}.
     */
    public final Conference conference;

    /**
     * The list of contents in this conference.
     */
    private final Map<MediaType, ContentShim> contents = new HashMap<>();

    /**
     * Initializes a new {@link ConferenceShim} instance.
     *
     * @param conference the corresponding conference.
     */
    public ConferenceShim(Conference conference, Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(ConferenceShim.class.getName());
        this.conference = conference;
    }

    /**
     * Gets the content of type {@code type}, creating it if necessary.
     *
     * @param type the media type of the content to add.
     *
     * @return the content.
     */
    public ContentShim getOrCreateContent(MediaType type)
    {
        synchronized (contents)
        {
            return contents.computeIfAbsent(type,
                    key -> new ContentShim(getConference(), type, logger));
        }
    }

    /**
     * @return the corresponding conference.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Gets a copy of the list of contents.
     *
     * @return
     */
    public Collection<ContentShim> getContents()
    {
        synchronized (contents)
        {
            return new ArrayList<>(contents.values());
        }
    }

    /**
     * Describes the channel bundles of this conference in a Colibri IQ.
     * @param iq the IQ to describe in.
     * @param endpointIds the list of IDs to describe.
     */
    void describeChannelBundles(
            ColibriConferenceIQ iq,
            Set<String> endpointIds)
    {
        for (AbstractEndpoint endpoint : conference.getEndpoints())
        {
            String endpointId = endpoint.getID();
            if (endpointIds.contains(endpointId))
            {
                ColibriConferenceIQ.ChannelBundle responseBundleIQ
                    = new ColibriConferenceIQ.ChannelBundle(endpointId);
                endpoint.describe(responseBundleIQ);

                iq.addChannelBundle(responseBundleIQ);
            }
        }
    }

    /**
     * Adds the endpoint of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.Endpoint</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     */
    void describeEndpoints(ColibriConferenceIQ iq)
    {
        conference.getEndpoints().forEach(
                en -> iq.addEndpoint(
                        new ColibriConferenceIQ.Endpoint(
                                en.getID(), en.getStatsId(), en.getDisplayName())));
    }

    /**
     * Sets the attributes of this conference to an IQ.
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(conference.getID());
        iq.setName(conference.getName());
    }

    /**
     * Gets the ID of the conference.
     */
    public String getId()
    {
        return conference.getID();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

        for (ContentShim contentShim : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(contentShim.getMediaType().toString());

            for (ChannelShim channelShim : contentShim.getChannelShims())
            {
                if (channelShim instanceof SctpConnectionShim)
                {
                    ColibriConferenceIQ.SctpConnection sctpConnectionIQ
                        = new ColibriConferenceIQ.SctpConnection();
                    channelShim.describe(sctpConnectionIQ);
                    contentIQ.addSctpConnection(sctpConnectionIQ);
                }
                else
                {
                    ColibriConferenceIQ.Channel channelIQ
                        = new ColibriConferenceIQ.Channel();

                    channelShim.describe(channelIQ);
                    contentIQ.addChannel(channelIQ);
                }
            }
        }
        // Do we also want endpoint-s anc channel-bundle-id-s?
    }

    /**
     * Processes the Octo channels from a Colibri request.
     */
    public void processOctoChannels(
                @NotNull ColibriConferenceIQ.Channel audioChannel,
                @NotNull ColibriConferenceIQ.Channel videoChannel)
    {
        OctoTentacle tentacle = conference.getTentacle();

        int expire
                = Math.min(audioChannel.getExpire(), videoChannel.getExpire());
        if (expire == 0)
        {
            tentacle.expire();
        }
        else if (audioChannel instanceof ColibriConferenceIQ.OctoChannel
            && videoChannel instanceof ColibriConferenceIQ.OctoChannel)
        {
            ColibriConferenceIQ.OctoChannel audioOctoChannel
                    = (ColibriConferenceIQ.OctoChannel) audioChannel;
            ColibriConferenceIQ.OctoChannel videoOctoChannel
                    = (ColibriConferenceIQ.OctoChannel) videoChannel;

            Set<String> relays = new HashSet<>(audioOctoChannel.getRelays());
            relays.addAll(videoOctoChannel.getRelays());
            tentacle.setRelays(relays);
        }

        Set<RTPHdrExtPacketExtension> headerExtensions
                = new HashSet<>(audioChannel.getRtpHeaderExtensions());
        headerExtensions.addAll(videoChannel.getRtpHeaderExtensions());

        // Like for payload types, we never clear the transceiver's list of RTP
        // header extensions. See the note in #addPayloadTypes.
        headerExtensions.forEach(ext -> {
                RtpExtension rtpExtension = ChannelShim.createRtpExtension(ext);
                if (rtpExtension != null)
                {
                    tentacle.addRtpExtension(rtpExtension);
                }
        });

        Map<PayloadTypePacketExtension, MediaType> payloadTypes
                = new HashMap<>();
        audioChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.AUDIO));
        videoChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.VIDEO));

        payloadTypes.forEach((ext, mediaType) -> {
            PayloadType pt = PayloadTypeUtil.create(ext, mediaType);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                tentacle.addPayloadType(pt);
            }
        });

        tentacle.setSources(
                audioChannel.getSources(),
                videoChannel.getSources(),
                videoChannel.getSourceGroups());
    }

    /**
     * Process whole {@link ColibriConferenceIQ} and initialize all signaled
     * endpoints which has not been initialized before.
     * @param conferenceIQ conference IQ having endpoints
     */
    void initializeSignaledEndpoints(ColibriConferenceIQ conferenceIQ)
        throws VideobridgeShim.IqProcessingException
    {
        for (ColibriConferenceIQ.Content content : conferenceIQ.getContents())
        {
            for (ColibriConferenceIQ.Channel channel : content.getChannels())
            {
                final String endpoint = channel.getEndpoint();
                if (endpoint != null)
                {
                    ensureEndpointCreated(
                        channel.getEndpoint(),
                        Boolean.TRUE.equals(channel.isInitiator()));
                }
            }

            for (ColibriConferenceIQ.SctpConnection channel
                : content.getSctpConnections())
            {
                final String endpoint = channel.getEndpoint();
                if (endpoint != null)
                {
                    ensureEndpointCreated(
                        channel.getEndpoint(),
                        Boolean.TRUE.equals(channel.isInitiator()));
                }
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundle
            : conferenceIQ.getChannelBundles())
        {
            ensureEndpointCreated(channelBundle.getId(), false);
        }

        for (ColibriConferenceIQ.Endpoint endpoint
            : conferenceIQ.getEndpoints())
        {
            ensureEndpointCreated(endpoint.getId(), false);
        }
    }

    /**
     * Checks if endpoint with specified ID is initialized, if endpoint does not
     * exist in a conference, it will be created and initialized.
     * @param endpointId identifier of endpoint to check and initialize
     * @param iceControlling ICE control role of transport of newly created
     * endpoint
     * @throws VideobridgeShim.IqProcessingException
     */
    private void ensureEndpointCreated(String endpointId, boolean iceControlling)
        throws VideobridgeShim.IqProcessingException
    {
        if (conference.getLocalEndpoint(endpointId) != null)
        {
            return;
        }
        try
        {
            conference.createLocalEndpoint(endpointId, iceControlling);
        }
        catch (IOException ioe)
        {
            throw new VideobridgeShim.IqProcessingException(
                XMPPError.Condition.internal_server_error,
                "Error initializing endpoint " +
                    endpointId);
        }
    }

    /**
     * Updates an <tt>Endpoint</tt> of this <tt>Conference</tt> with the
     * information contained in <tt>colibriEndpoint</tt>. The ID of
     * <tt>colibriEndpoint</tt> is used to select the <tt>Endpoint</tt> to
     * update.
     *
     * @param colibriEndpoint a <tt>ColibriConferenceIQ.Endpoint</tt> instance
     * that contains information to be set on an <tt>Endpoint</tt> instance of
     * this <tt>Conference</tt>.
     */
    void updateEndpoint(ColibriConferenceIQ.Endpoint colibriEndpoint)
    {
        String id = colibriEndpoint.getId();

        if (id != null)
        {
            AbstractEndpoint endpoint = conference.getEndpoint(id);

            if (endpoint != null)
            {
                endpoint.setDisplayName(colibriEndpoint.getDisplayName());
                endpoint.setStatsId(colibriEndpoint.getStatsId());
            }
        }
    }
}
