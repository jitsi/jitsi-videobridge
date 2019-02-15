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
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.io.*;
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
     * This method collects all of the channel bundle IDs referenced in the
     * given IQ.
     * @param conferenceIq
     * @return
     */
    private static Set<String> getAllSignaledChannelBundleIds(
            ColibriConferenceIQ conferenceIq)
    {
        Set<String> channelBundleIds = new HashSet<>();
        for (ColibriConferenceIQ.Content contentIq : conferenceIq.getContents())
        {
            for (ColibriConferenceIQ.Channel channelIq : contentIq.getChannels())
            {
                channelBundleIds.add(channelIq.getChannelBundleId());
            }
            for (ColibriConferenceIQ.SctpConnection sctpConnIq
                    : contentIq.getSctpConnections())
            {
                channelBundleIds.add(sctpConnIq.getChannelBundleId());
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq
                : conferenceIq.getChannelBundles())
        {
            channelBundleIds.add(channelBundleIq.getId());
        }
        return channelBundleIds;
    }

    /**
     * Processes a list of {@code Channel} elements in a specific
     * {@link ContentShim}.
     *
     * @param channelIqs the list of channel elements.
     * @param contentShim the associated {@code ContentShim}
     * @return the list of channel elements that have been created and updated
     * (but haven't been expired).
     * @throws IqProcessingException
     */
    private static List<ColibriConferenceIQ.Channel> processChannels(
            List<ColibriConferenceIQ.Channel> channelIqs,
            ContentShim contentShim)
            throws IqProcessingException
    {
        List<ColibriConferenceIQ.Channel> createdOrUpdatedChannels
                = new ArrayList<>();

        for (ColibriConferenceIQ.Channel channelIq : channelIqs)
        {
            if (channelIq instanceof ColibriConferenceIQ.OctoChannel)
            {
                logger.warn("Ignoring an octo channel request. Not implemented.");
                continue;
            }

            ChannelShim channelShim
                    = contentShim.getOrCreateChannelShim(channelIq);
            if (channelShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }


            channelShim.setDirection(channelIq.getDirection());
            channelShim.addPayloadTypes(channelIq.getPayloadTypes());
            channelShim.addRtpHeaderExtensions(
                    channelIq.getRtpHeaderExtensions());

            List<SourcePacketExtension> channelSources = channelIq.getSources();
            channelShim.setSources(channelSources);
            channelShim.setSourceGroups(channelIq.getSourceGroups());

            // We only create tracks for video right now, because we don't have
            // audio tracks. So only trigger re-creation of the tracks when a
            // video channel is signaled.
            if (MediaType.VIDEO.equals(contentShim.getMediaType())
                    && !channelSources.isEmpty())
            {
                channelShim.getEndpoint().recreateMediaStreamTracks();
            }

            Integer channelLastN = channelIq.getLastN();
            if (channelLastN != null)
            {
                channelShim.setLastN(channelLastN);
            }
            ColibriConferenceIQ.Channel responseChannelIQ
                    = new ColibriConferenceIQ.Channel();
            channelShim.describe(responseChannelIQ);
            createdOrUpdatedChannels.add(responseChannelIQ);

            // TODO: do we still want to support the legacy mode of putting
            // <transport> inside <channel>?
        }

        return createdOrUpdatedChannels;
    }

    /**
     * Processes the list of {@link ColibriConferenceIQ.SctpConnection}s
     * present in a received {@link ColibriConferenceIQ}.  Returns a list of
     * {@link ColibriConferenceIQ.SctpConnection} elements that contain
     * descriptions of the created and/or updated SCTP connection instances.
     * @param sctpConnections
     * @param contentShim
     * @return
     * @throws IqProcessingException if there are any errors during the
     * processing of the incoming connections.
     */
    private static List<ColibriConferenceIQ.SctpConnection> processSctpConnections(
            List<ColibriConferenceIQ.SctpConnection> sctpConnections,
            ContentShim contentShim) throws IqProcessingException
    {
        List<ColibriConferenceIQ.SctpConnection> createdOrUpdatedSctpConnections
                = new ArrayList<>();
        for (ColibriConferenceIQ.SctpConnection sctpConnIq : sctpConnections)
        {
            SctpConnectionShim sctpConnectionShim
                    = contentShim.getOrCreateSctpConnectionShim(sctpConnIq);
            if (sctpConnectionShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }

            ColibriConferenceIQ.SctpConnection responseSctpIq
                    = new ColibriConferenceIQ.SctpConnection();

            sctpConnectionShim.describe(responseSctpIq);

            createdOrUpdatedSctpConnections.add(responseSctpIq);
        }

        return createdOrUpdatedSctpConnections;
    }

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
        conference.setLastKnownFocus(focus);

        ConferenceShim conferenceShim = conference.getShim();
        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        responseConferenceIQ.setGracefulShutdown(
                videobridge.isShutdownInProgress());

        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents())
        {
             // The content element springs into existence whenever it gets
             // mentioned, it does not need explicit creation (in contrast to
             // the conference and channel elements).
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
                processChannels(contentIQ.getChannels(), contentShim)
                        .forEach(responseContentIQ::addChannel);
            }
            catch (IqProcessingException e)
            {
                logger.error("Error processing channels: " + e.toString());
                return IQUtils.createError(
                        conferenceIQ, e.condition, e.errorMessage);
            }

            try
            {
                processSctpConnections(contentIQ.getSctpConnections(), contentShim)
                        .forEach(responseContentIQ::addSctpConnection);
            }
            catch (IqProcessingException e)
            {
                logger.error(
                    "Error processing sctp connections in IQ: " + e.toString());
                return IQUtils.createError(
                        conferenceIQ, e.condition, e.errorMessage);
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq
                : conferenceIQ.getChannelBundles())
        {
            IceUdpTransportPacketExtension transportIq
                    = channelBundleIq.getTransport();
            if (transportIq == null)
            {
                continue;
            }

            AbstractEndpoint endpoint
                    = conference.getEndpoint(channelBundleIq.getId(), true);
            if (endpoint instanceof Endpoint)
            {
                try
                {
                    ((Endpoint)endpoint).setTransportInfo(transportIq);
                }
                catch (IOException ioe)
                {
                    logger.error(
                        "Error processing channel-bundle: " + ioe.toString());
                    return IQUtils.createError(
                            conferenceIQ,
                            XMPPError.Condition.internal_server_error,
                            "Failed to set transport: " + ioe.getMessage());
                }
            }
        }

        // TODO read "endpoint" elements?

        Set<String> channelBundleIdsToDescribe
                = getAllSignaledChannelBundleIds(conferenceIQ);
        conferenceShim.describeChannelBundles(
                responseConferenceIQ,
                channelBundleIdsToDescribe);
        conferenceShim.describeEndpoints(responseConferenceIQ);

        responseConferenceIQ.setType(IQ.Type.result);

        return responseConferenceIQ;
    }

    static class IqProcessingException extends Exception
    {
        private final XMPPError.Condition condition;
        private final String errorMessage;

        public IqProcessingException(
                XMPPError.Condition condition, String errorMessage)
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
