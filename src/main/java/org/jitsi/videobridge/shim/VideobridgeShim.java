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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.util.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.net.*;
import java.util.*;

/**
 * Handles Colibri-related logic for a {@link Videobridge}, e.g. handles
 * incoming Colibri requests.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class VideobridgeShim
{
    /**
     * The {@link Logger} used by the {@link VideobridgeShim} class and its
     * instances to print debug information.
     */
    private static final Logger logger =
            Logger.getLogger(VideobridgeShim.class);

    /**
     * The associated {@link Videobridge}.
     */
    private final Videobridge videobridge;

    /**
     * Initializes a new {@link VideobridgeShim} instance.
     * @param videobridge
     */
    public VideobridgeShim(Videobridge videobridge)
    {
        this.videobridge = videobridge;
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @param options
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     */
    public IQ handleColibriConferenceIQ(
            ColibriConferenceIQ conferenceIQ, int options)
    {
        Jid focus = conferenceIQ.getFrom();

        if (!videobridge.accept(focus, options))
        {
            return IQUtils.createError(
                    conferenceIQ, XMPPError.Condition.not_authorized);
        }

        Conference conference;

        String conferenceId = conferenceIQ.getID();
        if (conferenceId == null)
        {
            if (videobridge.isShutdownInProgress())
            {
                return ColibriConferenceIQ
                        .createGracefulShutdownErrorResponse(conferenceIQ);
            }
            else
            {
                conference
                        = videobridge.createConference(
                                focus,
                                conferenceIQ.getName(),
                                conferenceIQ.getGID());
                if (conference == null)
                {
                    return IQUtils.createError(
                            conferenceIQ,
                            XMPPError.Condition.internal_server_error,
                            "Failed to create new conference");
                }
            }
        }
        else
        {
            conference = videobridge.getConference(conferenceId, focus);
            if (conference == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.bad_request,
                        "Conference not found for ID: " + conferenceId);
            }
        }

        // XXX_Boris: Do we need to bring this back, or just remove the
        // lastKnownFocus concept (is this from pre-jicofo times?):
        // conference.setLastKnownFocus(conferenceIQ.getFrom());

        ConferenceShim conferenceShim = conference.getShim();
        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        responseConferenceIQ.setGracefulShutdown(
                videobridge.isShutdownInProgress());

        Map<String, List<PayloadTypePacketExtension>> endpointPayloadTypes
                = new HashMap<>();
        Map<String, List<RTPHdrExtPacketExtension>> endpointHeaderExts
                = new HashMap<>();
        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents())
        {
            /*
             * The content element springs into existence whenever it gets
             * mentioned, it does not need explicit creation (in contrast to
             * the conference and channel elements).
             */
            MediaType contentType = MediaType.parseString(contentIQ.getName());
            ContentShim contentShim =
                    conferenceShim.getOrCreateContent(contentType);
            if (contentShim == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.internal_server_error,
                        "Failed to create new content for type: "
                                + contentType);
            }

            ColibriConferenceIQ.Content responseContentIQ
                    = new ColibriConferenceIQ.Content(contentType.toString());

            responseConferenceIQ.addContent(responseContentIQ);

            try
            {
                List<ColibriConferenceIQ.Channel> describedChannels
                    = processChannels(
                        contentIQ.getChannels(),
                        endpointPayloadTypes,
                        endpointHeaderExts,
                        conferenceShim,
                        contentShim);
                describedChannels.forEach(responseContentIQ::addChannel);
            }
            catch (IqProcessingException e)
            {
                logger.error("Error processing channels in IQ: " + e.toString());
                return IQUtils.createError(conferenceIQ, e.condition, e.errorMessage);
            }

            notifyEndpointsOfPayloadTypes(endpointPayloadTypes, conference);
            notifyEndpointsOfRtpHeaderExtensions(endpointHeaderExts, conference);

            try
            {
                List<ColibriConferenceIQ.SctpConnection> describedSctpConnections
                        = processSctpConnections(
                                contentIQ.getSctpConnections(),
                                conferenceShim,
                                contentShim);
                describedSctpConnections.forEach(responseContentIQ::addSctpConnection);
            }
            catch (IqProcessingException e)
            {
                logger.error("Error processing sctp connections in IQ: " + e.toString());
                return IQUtils.createError(conferenceIQ, e.condition, e.errorMessage);
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIQ.getChannelBundles())
        {
            ChannelBundleShim channelBundleShim =
                    conferenceShim.getOrCreateChannelBundle(channelBundleIq.getId());
            IceUdpTransportPacketExtension transportIq = channelBundleIq.getTransport();

            if (channelBundleShim != null && transportIq != null)
            {
                channelBundleShim.startConnectivityEstablishment(transportIq);
            }
        }

        //TODO update endpoints(?)

        Set<String> channelBundleIdsToDescribe
                = getAllSignaledChannelBundleIds(conferenceIQ);
        conferenceShim.describeChannelBundles(
                responseConferenceIQ,
                channelBundleIdsToDescribe);
        conferenceShim.describeEndpoints(responseConferenceIQ);

        responseConferenceIQ.setType(org.jivesoftware.smack.packet.IQ.Type.result);

        System.out.println("Sending colibri conference iq response:\n" + responseConferenceIQ.toXML());
        return responseConferenceIQ;
    }

    /**
     * This method collects all of the channel bundle IDs referenced in the given IQ.
     * @param conferenceIq
     * @return
     */
    private Set<String> getAllSignaledChannelBundleIds(ColibriConferenceIQ conferenceIq)
    {
        Set<String> channelBundleIds = new HashSet<>();
        for (ColibriConferenceIQ.Content contentIq : conferenceIq.getContents()) {
            for (ColibriConferenceIQ.Channel channelIq : contentIq.getChannels()) {
                channelBundleIds.add(channelIq.getChannelBundleId());
            }
            for (ColibriConferenceIQ.SctpConnection sctpConnIq : contentIq.getSctpConnections())
            {
                channelBundleIds.add(sctpConnIq.getChannelBundleId());
            }
        }
        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIq.getChannelBundles())
        {
            channelBundleIds.add(channelBundleIq.getId());
        }
        return channelBundleIds;
    }

    private List<ColibriConferenceIQ.Channel> processChannels(
            List<ColibriConferenceIQ.Channel> channels,
            Map<String, List<PayloadTypePacketExtension>> endpointPayloadTypes,
            Map<String, List<RTPHdrExtPacketExtension>> endpointHeaderExts,
            ConferenceShim conferenceShim,
            ContentShim content) throws IqProcessingException
    {
        List<ColibriConferenceIQ.Channel> createdOrUpdatedChannels = new ArrayList<>();
        Map<String, List<SourceGroupPacketExtension>> endpointSourceGroups = new HashMap<>();
        Map<String, List<SourcePacketExtension>> endpointSources = new HashMap<>();

        for (ColibriConferenceIQ.Channel channelIq : channels)
        {
            String channelId = channelIq.getID();
            int channelExpire = channelIq.getExpire();
            String channelBundleId = channelIq.getChannelBundleId();
            String endpointId = channelIq.getEndpoint();

            boolean isOcto
                    = channelIq instanceof ColibriConferenceIQ.OctoChannel;

            ChannelShim channelShim;
            if (channelId == null)
            {
                if (channelExpire == 0)
                {
                    // An expire attribute in the channel element with
                    // value equal to zero requests the immediate
                    // expiration of the channel in question.
                    // Consequently, it does not make sense to have it in a
                    // channel allocation request.
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Channel expire request for empty ID");
                }
                if (endpointId == null)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Channel creation requested without endpoint ID");
                }
                if (!endpointId.equals(channelBundleId))
                {
                    //TODO: can we enforce this?
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Endpoint ID does not match channel bundle ID");
                }
                channelShim = content.createRtpChannel(conferenceShim, endpointId, isOcto);
                if (channelShim == null)
                {
                    throw new IqProcessingException(XMPPError.Condition.internal_server_error, "Error creating channel");
                }
            }
            else
            {
                channelShim = content.getChannel(channelId);
                if (channelShim == null)
                {
                    if (channelExpire == 0)
                    {
                        // Channel expired on its own before it was requested to be expired
                        continue;
                    }
                    throw new IqProcessingException(
                            XMPPError.Condition.internal_server_error, "Error finding channel " + channelId);
                }
                // If this was an existing endpoint, it won't have set an endpoint ID in the IQ, so we look it up
                // from the shim
                endpointId = channelShim.endpoint.getID();
            }
            MediaDirection channelDirection = channelIq.getDirection();
            Collection<PayloadTypePacketExtension> channelPayloadTypes = channelIq.getPayloadTypes();
            Collection<RTPHdrExtPacketExtension> channelRtpHeaderExtensions = channelIq.getRtpHeaderExtensions();
            Collection<SourcePacketExtension> channelSources = channelIq.getSources();
            Collection<SourceGroupPacketExtension> channelSourceGroups = channelIq.getSourceGroups();
            Integer channelLastN = channelIq.getLastN();

            if (channelExpire != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
            {
                if (channelExpire < 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Invalid 'expire' value: " + channelExpire);
                }
                channelShim.setExpire(channelExpire);
                /*
                 * If the request indicates that it wants the channel
                 * expired and the channel is indeed expired, then
                 * the request is valid and has correctly been acted upon.
                 */
                if ((channelExpire == 0) && channelShim.isExpired())
                {
                    continue;
                }
            }
            else
            {
                channelShim.setExpire(VideobridgeExpireThread.DEFAULT_EXPIRE);
            }
            channelShim.direction = channelDirection;

            List<PayloadTypePacketExtension> epPayloadTypes =
                    endpointPayloadTypes.computeIfAbsent(endpointId, key -> new ArrayList<>());
            epPayloadTypes.addAll(channelPayloadTypes);
            channelShim.setPayloadTypes(channelPayloadTypes);

            List<RTPHdrExtPacketExtension> epHeaderExts =
                    endpointHeaderExts.computeIfAbsent(endpointId, key -> new ArrayList<>());
            epHeaderExts.addAll(channelRtpHeaderExtensions);
            channelShim.rtpHeaderExtensions = channelRtpHeaderExtensions;

            if (channelSources != null)
            {
                // Note that we only add sources and never remove. They accumulate with successive
                // colibri requests.
                List<SourcePacketExtension> epSources =
                        endpointSources.computeIfAbsent(endpointId, key -> new ArrayList<>());
                epSources.addAll(channelSources);
            }
            channelShim.sources = channelSources;


            if (channelSourceGroups != null)
            {
                // Note that we only add source ground and never remove. They accumulate with successive
                // colibri requests.
                List<SourceGroupPacketExtension> epSourceGroups =
                        endpointSourceGroups.computeIfAbsent(endpointId, key -> new ArrayList<>());
                epSourceGroups.addAll(channelSourceGroups);
            }
            channelShim.sourceGroups = channelSourceGroups;

            //TODO(brian): we only create tracks for video right now, which is a bit weird (even though that's the
            // only place we use them).  We should either create them for both audio and video and index them by
            // media type or name these methods to better reflect they are for video
            if (MediaType.VIDEO.equals(content.getType()) && !(channelShim.sources == null || channelShim.sources.isEmpty()))
            {
                MediaStreamTrackDesc[] tracks =
                        MediaStreamTrackFactory.createMediaStreamTracks(channelShim.sources, channelShim.sourceGroups);
                //TEMP
                System.out.println("TEMP: created media stream tracks:");
                for (MediaStreamTrackDesc track : tracks)
                {
                    System.out.println(track.toString());
                }
                //END TEMP
                channelShim.endpoint.setMediaStreamTracks(tracks);
            }

            if (channelLastN != null)
            {
                channelShim.setLastN(channelLastN);
            }
            ColibriConferenceIQ.Channel responseChannelIQ = new ColibriConferenceIQ.Channel();
            channelShim.describe(responseChannelIQ);
            createdOrUpdatedChannels.add(responseChannelIQ);
        }

        addSources(endpointSources, conferenceShim.getConference());
        addSourceGroups(endpointSourceGroups, conferenceShim.getConference());
        return createdOrUpdatedChannels;
    }

    private void addSources(Map<String, List<SourcePacketExtension>> epSources, Conference conference)
    {
        epSources.forEach((epId, currEpSources) -> {
            currEpSources.forEach(epSource -> {
                AbstractEndpoint ep = conference.getEndpoint(epId);
                if (ep != null)
                {
                    ep.addReceiveSsrc(epSource.getSSRC());
                }
                else
                {
                    logger.error("Unable to find endpoint " + epId +
                            " to add incoming SSRC " + epSource.getSSRC());
                }
            });
        });
    }

    private void addSourceGroups(Map<String, List<SourceGroupPacketExtension>> epSourceGroups, Conference conference)
    {
        epSourceGroups.forEach((epId, currEpSourceGroups) -> {
            currEpSourceGroups.forEach(srcGroup -> {
                long primarySsrc = srcGroup.getSources().get(0).getSSRC();
                long secondarySsrc = srcGroup.getSources().get(1).getSSRC();

                //TODO(brian): we should move the fact that we don't care about SIM groups to a lower level
                // (endpoint or transceiver)
                SsrcAssociationType ssrcAssociationType = groupSemanticsToSsrcAssociationType(srcGroup.getSemantics());
                if (ssrcAssociationType != null && ssrcAssociationType != SsrcAssociationType.SIM)
                {
                    conference.encodingsManager.addSsrcAssociation(epId, primarySsrc, secondarySsrc, ssrcAssociationType);
                }
            });
        });
    }
    private static SsrcAssociationType groupSemanticsToSsrcAssociationType(String sourceGroupSemantics)
    {
        if (sourceGroupSemantics.equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_FID))
        {
            return SsrcAssociationType.RTX;
        }
        else if (sourceGroupSemantics.equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
        {
            return SsrcAssociationType.SIM;
        }
        else if (sourceGroupSemantics.equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_FEC))
        {
            return SsrcAssociationType.FEC;
        }
        return null;
    }



    private void notifyEndpointsOfRtpHeaderExtensions(
            Map<String, List<RTPHdrExtPacketExtension>> epHeaderExtensions,
            Conference conference)
    {
        //TODO: like payload types, we may have a buf here if the extensions get updated for a single channel.  if that
        // happens we will clear all of them, but only re-add the ones from the updated channel
        epHeaderExtensions.forEach((epId, headerExtensions) -> {
            logger.info("Notifying ep " + epId + " about " + headerExtensions.size() + " header extensions");
            AbstractEndpoint ep = conference.getEndpoint(epId);
            if (ep != null)
            {
                ep.transceiver.clearRtpExtensions();
                headerExtensions.forEach(hdrExt -> {
                    URI uri = hdrExt.getURI();
                    Byte id = Byte.valueOf(hdrExt.getID());

                    ep.transceiver.addRtpExtension(id, new RTPExtension(uri));
                });
            }
        });

    }

    private void notifyEndpointsOfPayloadTypes(
            Map<String, List<PayloadTypePacketExtension>> epPayloadTypes,
            Conference conference)
    {
        // TODO(brian): The code below is an transitional step in moving logic
        //  out of the channel. Instead of relying on the channel to update the
        //  transceiver with the payload types, we do it here (after gathering
        //  them for the entire endpoint, rather than one channel at a time).
        //  This should go elsewhere (in one of the Shims?), but at least here
        //  we've gotten that code out of the channel.
        // TODO: There's a bug here, where I think only the video channel is
        //  being updated so we clear the payload types and then only re-set the
        //  video ones. Not sure exactly what changed from the logic being
        //  moved, but we need to come up with a new way to do this anyway.
        //  (???) Is this still valid?
        epPayloadTypes.forEach((epId, payloadTypeExtensions) -> {
            logger.debug("Notifying ep " + epId + " about "
                    + payloadTypeExtensions.size() + " payload type mappings");
            AbstractEndpoint ep = conference.getEndpoint(epId);
            if (ep != null) {
                ep.transceiver.clearPayloadTypes();
                payloadTypeExtensions.forEach(ext -> {
                    System.out.println("TEMP: looking at payload type extension: " + ext.toXML());
                    PayloadType pt = PayloadTypeUtil.create(ext);
                    if (pt == null)
                    {
                        logger.warn("Unrecognized payload type " + ext.toXML());
                    }
                    else
                    {
                        logger.debug("Notifying ep " + epId
                                + " about payload type mapping: " + pt);
                        ep.addPayloadType(pt);
                    }
                });
            }
        });
    }

    /**
     * Processes the list of {@link ColibriConferenceIQ.SctpConnection}s present in a receive
     * {@link ColibriConferenceIQ}.  Returns a list of {@link ColibriConferenceIQ.SctpConnection} elements that contain
     * descriptions of the created and/or updated SCTP connection instances.
     * @param sctpConnections
     * @param conference
     * @param contentShim
     * @return
     * @throws IqProcessingException if there are any errors during the processing of the incoming connections
     */
    private List<ColibriConferenceIQ.SctpConnection> processSctpConnections(
            List<ColibriConferenceIQ.SctpConnection> sctpConnections,
            ConferenceShim conference,
            ContentShim contentShim) throws IqProcessingException {
        List<ColibriConferenceIQ.SctpConnection> createdOrUpdatedSctpConnections = new ArrayList<>();
        for (ColibriConferenceIQ.SctpConnection sctpConnIq : sctpConnections)
        {
            String sctpConnId = sctpConnIq.getID();
            String endpointID = sctpConnIq.getEndpoint();
            SctpConnectionShim sctpConnectionShim;
            int sctpConnExpire = sctpConnIq.getExpire();

            if (sctpConnId == null)
            {
                if (sctpConnExpire == 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "SCTP connection expire request for empty ID");
                }

                if (endpointID == null)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "No endpoint ID specified for the new SCTP connection");
                }

                sctpConnectionShim = contentShim.createSctpConnection(conference.getId(), endpointID);
            }
            else
            {
                sctpConnectionShim = contentShim.getSctpConnection(sctpConnId);
                if (sctpConnectionShim == null && sctpConnExpire == 0)
                {
                    continue;
                }
                else if (sctpConnectionShim == null)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "No SCTP connection found for ID: " + sctpConnId);
                }
            }
            if (sctpConnExpire != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
            {
                if (sctpConnExpire < 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Invalid 'expire' value: " + sctpConnExpire);
                }
                sctpConnectionShim.setExpire(sctpConnExpire);
                if (sctpConnExpire == 0 && sctpConnectionShim.isExpired())
                {
                    continue;
                }
            }
            else
            {
                sctpConnectionShim.setExpire(sctpConnExpire);
            }
            ColibriConferenceIQ.SctpConnection responseSctpIq = new ColibriConferenceIQ.SctpConnection();

            sctpConnectionShim.describe(responseSctpIq);

            createdOrUpdatedSctpConnections.add(responseSctpIq);
        }
        return createdOrUpdatedSctpConnections;
    }

    private class IqProcessingException extends Exception
    {
        public final XMPPError.Condition condition;
        public final String errorMessage;

        public IqProcessingException(XMPPError.Condition condition, String errorMessage)
        {
            this.condition = condition;
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString()
        {
            return condition.toString() + " " + errorMessage;
        }
    }

}
