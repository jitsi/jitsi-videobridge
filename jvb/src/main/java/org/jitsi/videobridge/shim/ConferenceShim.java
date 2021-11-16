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
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.Endpoint;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.stream.*;

import static org.jitsi.videobridge.Conference.GID_NOT_SET;

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

    private final PacketQueue<XmppConnection.ColibriRequest> colibriQueue;

    /**
     * Initializes a new {@link ConferenceShim} instance.
     *
     * @param conference the corresponding conference.
     */
    public ConferenceShim(Conference conference, Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(ConferenceShim.class.getName());
        this.conference = conference;
        colibriQueue = new PacketQueue<>(
                Integer.MAX_VALUE,
                true,
                "colibri-queue",
                request ->
                {
                    try
                    {
                        long start = System.currentTimeMillis();
                        IQ requestIQ = request.getRequest();
                        IQ response;
                        if (requestIQ instanceof ColibriConferenceIQ)
                        {
                            response = handleColibriConferenceIQ((ColibriConferenceIQ)requestIQ);
                        }
                        else if (requestIQ instanceof ConferenceModifyIQ)
                        {
                            response = handleConferenceModifyIQ((ConferenceModifyIQ)requestIQ);
                        }
                        else
                        {
                            throw new IllegalStateException("Bad IQ " + request.getClass() + " passed to colibriIQ");
                        }
                        long end = System.currentTimeMillis();
                        long processingDelay = end - start;
                        long totalDelay = end - request.getReceiveTime();
                        request.getProcessingDelayStats().addDelay(processingDelay);
                        request.getTotalDelayStats().addDelay(totalDelay);
                        if (processingDelay > 100)
                        {
                            logger.warn("Took " + processingDelay + " ms to process an IQ (total delay "
                                    + totalDelay + " ms): " + request.getRequest().toXML());
                        }
                        request.getCallback().invoke(response);
                    }
                    catch (Throwable e)
                    {
                        logger.warn("Failed to handle colibri request: ", e);
                        request.getCallback().invoke(
                                IQUtils.createError(
                                        request.getRequest(),
                                        StanzaError.Condition.internal_server_error,
                                        e.getMessage()));
                    }
                    return true;
                },
                TaskPools.IO_POOL
        );
    }

    public void enqueueColibriRequest(XmppConnection.ColibriRequest request)
    {
        colibriQueue.add(request);
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
            String endpointId = endpoint.getId();
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
     * Sets the attributes of this conference to an IQ.
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        conference.describeShallow(iq);
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
        ConfOctoTransport tentacle = conference.getTentacle();

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
     * endpoints that have not been initialized before.
     * @param conferenceIQ conference IQ having endpoints
     */
    void initializeSignaledEndpoints(ColibriConferenceIQ conferenceIQ)
    {
        List<ColibriConferenceIQ.ChannelCommon> nonExpiredChannels
            = conferenceIQ.getContents().stream()
                .flatMap(content ->
                        Stream.concat(
                                content.getChannels().stream(),
                                content.getSctpConnections().stream()))
                .filter(c -> c.getEndpoint() != null && c.getExpire() != 0)
                .collect(Collectors.toList());

        for (ColibriConferenceIQ.ChannelCommon c : nonExpiredChannels)
        {
            ensureEndpointCreated(
                c.getEndpoint(),
                Boolean.TRUE.equals(c.isInitiator()));
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
     */
    private @NotNull Endpoint ensureEndpointCreated(String endpointId, boolean iceControlling)
    {
        Endpoint ep = conference.getLocalEndpoint(endpointId);
        if (ep != null)
        {
            return ep;
        }

        return conference.createLocalEndpoint(endpointId, iceControlling);
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
                endpoint.setStatsId(colibriEndpoint.getStatsId());
            }
        }
    }

    public void close()
    {
        colibriQueue.close();
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     */
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ)
    {
        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        responseConferenceIQ.setGracefulShutdown(conference.getVideobridge().isShutdownInProgress());

        initializeSignaledEndpoints(conferenceIQ);

        ColibriConferenceIQ.Channel octoAudioChannel = null;
        ColibriConferenceIQ.Channel octoVideoChannel = null;

        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents())
        {
            // The content element springs into existence whenever it gets
            // mentioned, it does not need explicit creation (in contrast to
            // the conference and channel elements).
            MediaType contentType = MediaType.parseString(contentIQ.getName());
            ContentShim contentShim = getOrCreateContent(contentType);
            if (contentShim == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        StanzaError.Condition.internal_server_error,
                        "Failed to create new content for type: " + contentType);
            }

            ColibriConferenceIQ.Content responseContentIQ = new ColibriConferenceIQ.Content(contentType.toString());

            responseConferenceIQ.addContent(responseContentIQ);

            try
            {
                ColibriUtil.processChannels(contentIQ.getChannels(), contentShim)
                        .forEach(responseContentIQ::addChannel);
            }
            catch (IqProcessingException e)
            {
                // Item not found conditions are assumed to be less critical, as they often happen in case a request
                // arrives late for an expired endpoint.
                if (StanzaError.Condition.item_not_found.equals(e.getCondition()))
                {
                    logger.warn("Error processing channels: " + e);
                }
                else
                {
                    logger.error("Error processing channels: " + e);
                }
                return IQUtils.createError(conferenceIQ, e.getCondition(), e.getMessage());
            }

            // We want to handle the two Octo channels together.
            ColibriConferenceIQ.Channel octoChannel = ColibriUtil.findOctoChannel(contentIQ);
            if (octoChannel != null)
            {
                if (MediaType.VIDEO.equals(contentType))
                {
                    octoVideoChannel = octoChannel;
                }
                else
                {
                    octoAudioChannel = octoChannel;
                }

                ColibriConferenceIQ.OctoChannel octoChannelResponse = new ColibriConferenceIQ.OctoChannel();
                octoChannelResponse.setID(ColibriUtil.getOctoChannelId(contentType));
                responseContentIQ.addChannel(octoChannelResponse);
            }

            try
            {
                ColibriUtil.processSctpConnections(contentIQ.getSctpConnections(), contentShim)
                        .forEach(responseContentIQ::addSctpConnection);
            }
            catch (IqProcessingException e)
            {
                logger.error("Error processing sctp connections in IQ: " + e.toString());
                return IQUtils.createError(conferenceIQ, e.getCondition(), e.getMessage());
            }
        }

        if (octoAudioChannel != null && octoVideoChannel != null)
        {
            if (conference.getGid() == GID_NOT_SET)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        StanzaError.Condition.bad_request,
                        "Can not enable octo without a valid GID.");
            }

            processOctoChannels(octoAudioChannel, octoVideoChannel);

        }
        else if (octoAudioChannel != null || octoVideoChannel != null)
        {
            logger.error("Octo must be enabled for audio and video together");
            return IQUtils.createError(
                    conferenceIQ,
                    StanzaError.Condition.bad_request,
                    "Octo only enabled for one media type");
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIQ.getChannelBundles())
        {
            IceUdpTransportPacketExtension transportIq = channelBundleIq.getTransport();
            if (transportIq == null)
            {
                continue;
            }

            final String endpointId = channelBundleIq.getId();

            final Endpoint endpoint = conference.getLocalEndpoint(endpointId);
            if (endpoint == null)
            {
                // Endpoint is expired and removed as part of handling IQ
                continue;
            }

            endpoint.setTransportInfo(transportIq);
        }

        describeChannelBundles(responseConferenceIQ, ColibriUtil.getAllSignaledChannelBundleIds(conferenceIQ));

        // Update the endpoint information of Videobridge with the endpoint
        // information of the IQ.
        for (ColibriConferenceIQ.Endpoint colibriEndpoint : conferenceIQ.getEndpoints())
        {
            updateEndpoint(colibriEndpoint);
        }

        responseConferenceIQ.setType(IQ.Type.result);

        return responseConferenceIQ;
    }

    public IQ handleConferenceModifyIQ(ConferenceModifyIQ conferenceModifyIQ)
    {
        try
        {
            ConferenceModifiedIQ.Builder responseBuilder =
                ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(conferenceModifyIQ));

            conference.describeShallow(responseBuilder);

            /* TODO: rename colibri2.endpoint so as not to conflict here? */
            /* TODO: is there any reason we might need to handle Endpoints and Relays in in-message order? */
            for (org.jitsi.xmpp.extensions.colibri2.Endpoint e : conferenceModifyIQ.getEndpoints())
            {
                responseBuilder.addEndpoint(handleColibri2Endpoint(e));
            }

            for (Relay r : conferenceModifyIQ.getRelays())
            {
                /* TODO */
            }

            return responseBuilder.build();
        }
        catch (IqProcessingException e)
        {
            // Item not found conditions are assumed to be less critical, as they often happen in case a request
            // arrives late for an expired endpoint.
            if (StanzaError.Condition.item_not_found.equals(e.getCondition()))
            {
                logger.warn("Error processing conference-modify IQ: " + e);
            }
            else
            {
                logger.error("Error processing conference-modify IQ: " + e);
            }
            return IQUtils.createError(conferenceModifyIQ, e.getCondition(), e.getMessage());
        }
    }

    /**
     * Process a colibri2 Endpoint in a conference-modify, return the response to be put in
     * the conference-modified.
     */
    private org.jitsi.xmpp.extensions.colibri2.Endpoint
    handleColibri2Endpoint(org.jitsi.xmpp.extensions.colibri2.Endpoint eDesc)
    throws IqProcessingException
    {
        String id = eDesc.getId();
        Transport t = eDesc.getTransport();
        org.jitsi.xmpp.extensions.colibri2.Endpoint.Builder respBuilder =
            org.jitsi.xmpp.extensions.colibri2.Endpoint.getBuilder();

        respBuilder.setId(eDesc.getId());

        boolean iceControlling;
        if (t != null)
        {
            iceControlling = Boolean.TRUE.equals(t.getInitiator());
        }
        else
        {
            iceControlling = false;
        }

        /* TODO: does iceControlling really need to be set here? */
        Endpoint ep = ensureEndpointCreated(id, iceControlling);

        if (eDesc.getExpire())
        {
            ep.expire();
            respBuilder.setExpire(true);
            return respBuilder.build();
        }

        for (Media m: eDesc.getMedia())
        {
            MediaType type = m.getType();

            /* TODO: organize these data structures more sensibly for Colibri2 */
            ContentShim contentShim = getOrCreateContent(type);
            ChannelShim channelShim = ep.getChannel(type);
            if (channelShim == null) {
                channelShim = contentShim.createRtpChannel(id);
            }
            channelShim.addPayloadTypes(m.getPayloadTypes());
            channelShim.addRtpHeaderExtensions(m.getRtpHdrExts());

            /* No need to put media in conference-modified. */
        }

        if (t != null)
        {
            IceUdpTransportPacketExtension udpTransportPacketExtension = t.getIceUdpTransport();
            if (udpTransportPacketExtension != null)
            {
                ep.setTransportInfo(udpTransportPacketExtension);
            }

            Transport.Builder transBuilder = Transport.getBuilder();
            transBuilder.setIceUdpExtension(ep.describeTransport());
            respBuilder.setTransport(transBuilder.build());
        }

        Sources sources = eDesc.getSources();
        if (sources != null)
        {
            /* A bit clunky, to load the new signaling into the old shims. */
            Map<MediaType, List<SourcePacketExtension>> sourcesByType = new HashMap<>();
            Map<MediaType, List<SourceGroupPacketExtension>> sourceGroupsByType = new HashMap<>();
            for (MediaSource s: sources.getMediaSources())
            {
                MediaType type = s.getType();
                sourcesByType.computeIfAbsent(type, (v) -> new ArrayList<>()).addAll(s.getSources());
                sourceGroupsByType.computeIfAbsent(type, (v) -> new ArrayList<>()).addAll(s.getSsrcGroups());
            }

            for (MediaType type: sourcesByType.keySet())
            {
                ChannelShim channelShim = ep.getChannel(type);
                if (channelShim == null)
                {
                    logger.error("Endpoint " + id + " has source of type " + type + " without media");
                    continue;
                }
                channelShim.setSources(sourcesByType.get(type));
                channelShim.setSourceGroups(sourceGroupsByType.get(type));
            }
        }

        return respBuilder.build();
    }
}
