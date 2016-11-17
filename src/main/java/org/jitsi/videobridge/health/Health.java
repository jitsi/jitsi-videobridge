/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.health;

import java.io.*;
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

/**
 * Checks the health of {@link Videobridge}.
 *
 * @author Lyubomir Marinov
 */
public class Health
{
    /**
     * The {@link MediaType}s of {@link RtpChannel}s supported by
     * {@link Videobridge}. For example, {@link MediaType#DATA} is not supported
     * by {@link
     * Content#createRtpChannel(String, String, Boolean, RTPLevelRelayType)}.
     */
    private static final MediaType[] MEDIA_TYPES
        = { MediaType.AUDIO, MediaType.VIDEO };

    /**
     * The pseudo-random generator used to generate random input for
     * {@link Videobridge} such as {@link Endpoint} IDs.
     */
    private static Random RANDOM = Videobridge.RANDOM;

    /**
     * Checks the health (status) of the {@link Videobridge} associated with a
     * specific {@link Conference}. The specified {@code conference} will be
     * used to perform the check i.e. for testing purposes.
     *
     * @param conference the {@code Conference} associated with the
     * {@code Videobridge} to check the health (status) of
     * @throws Exception if an error occurs while checking the health (status)
     * of the {@code videobridge} associated with {@code conference} or the
     * check determines that the {@code Videobridge} is not healthy 
     */
    private static void check(Conference conference)
        throws Exception
    {
        // Initialize the Endpoints, Contents, RtpChannels, SctpConnections.

        // Endpoint
        Endpoint[] endpoints = new Endpoint[2];

        for (int i = 0; i < endpoints.length; ++i)
        {
            Endpoint endpoint
                = conference.getOrCreateEndpoint(generateEndpointID());

            // Fail as quickly as possible.
            if (endpoint == null)
                throw new NullPointerException("Failed to create an endpoint.");

            endpoints[i] = endpoint;

            String channelBundleId = null;
            // Since Endpoints will connect between themselves, they should be
            // opposite in initiator terms.
            Boolean initiator = Boolean.valueOf(i % 2 == 0);

            for (MediaType mediaType : MEDIA_TYPES)
            {
                // Content
                Content content
                    = conference.getOrCreateContent(mediaType.toString());
                // RtpChannel
                RtpChannel rtpChannel
                    = content.createRtpChannel(
                            channelBundleId,
                            /* transportNamespace */ null,
                            initiator,
                            null);

                // Fail as quickly as possible.
                if (rtpChannel == null)
                    throw new NullPointerException(
                            "Failed to create a channel.");
            }

            // SctpConnection
            Content dataContent = conference.getOrCreateContent("data");
            SctpConnection sctpConnection
                = dataContent.createSctpConnection(
                        endpoint,
                        /* sctpPort */ RANDOM.nextInt(),
                        channelBundleId,
                        initiator);

            // Fail as quickly as possible.
            if (sctpConnection == null)
            {
                throw new NullPointerException(
                    "Failed to create SCTP connection.");
            }
        }

        // Connect the Endpoints (i.e. RtpChannels and SctpConnections) between
        // themselves.
        interconnect(endpoints);
    }

    /**
     * Checks the health (status) of a specific {@link Videobridge}.
     *
     * @param videobridge the {@code Videobridge} to check the health (status)
     * of
     * @throws Exception if an error occurs while checking the health (status)
     * of {@code videobridge} or the check determines that {@code videobridge}
     * is not healthy 
     */
    public static void check(Videobridge videobridge)
        throws Exception
    {
        if (MappingCandidateHarvesters.stunDiscoveryFailed)
        {
            throw new Exception("Address discovery through STUN failed");
        }

        // Conference
        Conference conference
            = videobridge.createConference(
                    /* focus */ null,
                    /* name */ null,
                    /* enableLogging */ false);

        // Fail as quickly as possible.
        if (conference == null)
        {
            throw new NullPointerException("Failed to create a conference");
        }
        else
        {
            try
            {
                check(conference);
            }
            finally
            {
                conference.expire();
            }
        }
    }

    /**
     * Checks if given {@link Videobridge} has valid connection to XMPP server.
     *
     * @param videobridge the {@code Videobridge} to check the XMPP connection
     *                    status of
     * @return <tt>true</tt> if given videobridge has valid XMPP connection,
     *         also if it's not using XMPP api at all(does not have
     *         ComponentImpl). Otherwise <tt>false</tt> will be returned.
     */
    private static boolean checkXmppConnection(Videobridge videobridge)
    {
        // If XMPP API was requested, but there isn't any XMPP component
        // registered we shall return false(no valid XMPP connection)
        Collection<ComponentImpl> components = videobridge.getComponents();
        if (videobridge.isXmppApiEnabled() && components.size() == 0)
        {
            return false;
        }

        for(ComponentImpl component : components)
        {
            if(!component.isConnectionAlive())
                return false;
        }
        return true;
    }

    /**
     * Connects a pair of {@link Endpoint}s between themselves.
     *
     * @param a the {@code Endpoint} to connect to {@code b}
     * @param b the {@code Endpoint} to connect to {@code a}
     * @throws Exception
     */
    private static void connect(Endpoint a, Endpoint b)
        throws Exception
    {
        // RtpChannel
        for (MediaType mediaType : MEDIA_TYPES)
        {
            List<RtpChannel> aRtpChannels = a.getChannels(mediaType);
            int count = aRtpChannels.size();
            List<RtpChannel> bRtpChannels = b.getChannels(mediaType);

            // Fail as quickly as possible
            if (count != bRtpChannels.size())
            {
                throw new IllegalStateException(
                        "Endpoint#getChannels(MediaType)");
            }
            else
            {
                for (int i = 0; i < count; ++i)
                    connect(aRtpChannels.get(i), bRtpChannels.get(i));
            }
        }

        // SctpConnection
        SctpConnection aSctpConnection = a.getSctpConnection();

        // Fail as quickly as possible.
        if (aSctpConnection == null)
            throw new NullPointerException("aSctpConnection is null");

        SctpConnection bSctpConnection = b.getSctpConnection();

        // Fail as quickly as possible.
        if (bSctpConnection == null)
            throw new NullPointerException("bSctpConnection is null");

        connect(aSctpConnection, bSctpConnection);
    }

    /**
     * Connects a pair of {@link Channel}s between themselves.
     *
     * @param a the {@code Channel} to connect to {@code b}
     * @param b the {@code Channel} to connect to {@code a}
     * @throws Exception
     */
    private static void connect(Channel a, Channel b)
        throws Exception
    {
        IceUdpTransportPacketExtension aTransport = describeTransportManager(a);

        // Fail as quickly as possible.
        if (aTransport == null)
            throw new NullPointerException("Failed to describe transport.");

        IceUdpTransportPacketExtension bTransport = describeTransportManager(b);

        // Fail as quickly as possible.
        if (bTransport == null)
            throw new NullPointerException("Failed to describe transport.");

        b.setTransport(aTransport);
        a.setTransport(bTransport);
    }

    /**
     * Builds a {@link IceUdpTransportPacketExtension} representation of the
     * {@link TransportManager} of a specific {@link Channel}.
     *
     * @param channel the {@code Channel} whose {@code TransportManager} is to
     * be represented as a {@code IceUdpTransportPacketExtension}
     * @return a {@code IceUdpTransportPacketExtension} representation of the
     * {@code TransportManager} of {@code channel}
     */
    private static IceUdpTransportPacketExtension describeTransportManager(
            Channel channel)
    {
        ColibriConferenceIQ.ChannelCommon iq
            = (channel instanceof SctpConnection)
                ? new ColibriConferenceIQ.SctpConnection()
                : new ColibriConferenceIQ.Channel();

        channel.getTransportManager().describe(iq);
        return iq.getTransport();
    }

    /**
     * Generates a pseudo-random {@code Endpoint} ID which is not guaranteed to
     * be unique.
     *
     * @return a pseudo-random {@code Endpoint} ID which is not guaranteed to be
     * unique
     */
    private static String generateEndpointID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Gets a JSON representation of the health (status) of a specific
     * {@link Videobridge}.
     *
     * @param videobridge the {@code Videobridge} to get the health (status) of
     * in the form of a JSON representation
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    public static void getJSON(
        Videobridge videobridge,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException,
               ServletException
    {
        int status;
        String reason = null;

        try
        {
            // Check XMPP connection status first
            if (checkXmppConnection(videobridge))
            {
                // Check if the videobridge is functional
                check(videobridge);
                status = HttpServletResponse.SC_OK;
            }
            else
            {
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                reason = "XMPP component connection failure.";
            }
        }
        catch (Exception ex)
        {
            if (ex instanceof IOException)
                throw (IOException) ex;
            else if (ex instanceof ServletException)
                throw (ServletException) ex;
            else
            {
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                reason = ex.getMessage();
            }
        }

        if (reason != null)
        {
            response.getOutputStream().println(reason);
        }
        response.setStatus(status);
    }

    /**
     * Connects a specific list of {@link Endpoint}s between themselves (in
     * consecutive pairs).
     *
     * @param endpoints the list of {@code Endpoint}s to connect between
     * themselves (in consecutive pairs)
     * @throws Exception
     */
    private static void interconnect(Endpoint[] endpoints)
        throws Exception
    {
        for (int i = 0; i < endpoints.length;)
            connect(endpoints[i++], endpoints[i++]);
    }
}
