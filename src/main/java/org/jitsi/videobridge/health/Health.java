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
import java.util.concurrent.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;
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
     * The {@link Logger} used by the {@link Health} class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Health.class);

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
     * The executor used to perform periodic health checks.
     */
    private static final RecurringRunnableExecutor executor
        = new RecurringRunnableExecutor(Health.class.getName());

    /**
     * The interval between health checks.
     */
    private static final long HEALTH_CHECK_INTERVAL = 10000;

    /**
     * The {@link #check(Videobridge)} API will return failure unless a there
     * was a health check performed in the last that many milliseconds.
     */
    private static final long HEALTH_CHECK_TIMEOUT = 30000;

    /**
     * Maps a {@link Videobridge} it the single
     * {@link VideobridgePeriodicChecker} instance responsible for checking its
     * health.
     */
    private static final Map<Videobridge,VideobridgePeriodicChecker> checkers
        = new ConcurrentHashMap<>();

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
                = (Endpoint) conference.getOrCreateEndpoint(generateEndpointID());

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

                // FIXME: Without the call to setEndpoint() the channel is not
                // added to the endpoint and as a result the channels of the two
                // endpoints will not be connected as part of the health check.
                // We are now intentionally not doing the call because:
                // 1. The code has been running like this for a long time
                //     without any known failures to detect issues.
                // 2. Connecting a pair of audio channels and a pair of video
                //     channels with the current code will result in 4
                //     additional ICE Agents being instantiated, which is a
                //     significant use of resources.
                // 3. We have a longer-term solution of refactoring the code to
                //     use channel bundles which will also solve this problem.

                // rtpChannel.setEndpoint(endpoint);

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
     * Checks the health (status) of a specific {@link Videobridge}. This method
     * only returns the cache results, it does not do the actual health check
     * (i.e. creating a test conference).
     *
     * @param videobridge the {@code Videobridge} to check the health (status)
     * of.
     * @throws Exception if an error occurs while checking the health (status)
     * of {@code videobridge} or the check determines that {@code videobridge}
     * is not healthy.
     */
    public static void check(Videobridge videobridge)
        throws Exception
    {
        VideobridgePeriodicChecker checker = checkers.get(videobridge);
        if (checker == null || checker.lastResultMs < 0)
        {
            throw new Exception(
                "No health checks running for this videobridge.");
        }

        Exception lastResult = checker.lastResult;
        long lastResultMs = checker.lastResultMs;
        long timeSinceLastResult = System.currentTimeMillis() - lastResultMs;

        if (timeSinceLastResult > HEALTH_CHECK_TIMEOUT)
        {
            throw new Exception(
                "No health checks performed recently, the last result was "
                    + timeSinceLastResult + "ms ago.");
        }

        if (lastResult != null)
        {
            throw new Exception(lastResult);
        }

        // We've had a recent result, and it is successful (no exception).
    }

    /**
     * Performs a health check on a specific {@link Videobridge}.
     *
     * @param videobridge the {@code Videobridge} to check the health (status)
     * of
     * @throws Exception if an error occurs while checking the health (status)
     * of {@code videobridge} or the check determines that {@code videobridge}
     * is not healthy 
     */
    private static void doCheck(Videobridge videobridge)
        throws Exception
    {
        if (MappingCandidateHarvesters.stunDiscoveryFailed)
        {
            throw new Exception("Address discovery through STUN failed");
        }

        if (!IceUdpTransportManager.healthy)
        {
            throw new Exception("Failed to bind single-port");
        }

        // Conference
        Conference conference
            = videobridge.createConference(
                    /* focus */ null,
                    /* name */ null,
                    /* enableLogging */ false,
                    /* gid */ null);

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
                // Note that the channel count is 0 because we don't add the
                // channels we create to the endpoint (see the FIXME in
                // check(Conference conference))
                for (int i = 0; i < count; ++i)
                {
                    connect(aRtpChannels.get(i), bRtpChannels.get(i));
                }
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

    /**
     * Starts a runnable which checks the health of {@code videobridge}
     * periodically (at an interval of ({@link #HEALTH_CHECK_INTERVAL}).
     * @param videobridge the {@link Videobridge} to run checks on.
     */
    public static void start(Videobridge videobridge)
    {
        if (checkers.get(videobridge) != null)
        {
            logger.error("Already running checks for " + videobridge);
            return;
        }

        checkers.put(videobridge, new VideobridgePeriodicChecker(videobridge));
    }

    /**
     * Stops running health checks for a specific {@link Videobridge}.
     * @param videobridge the {@link Videobridge}.
     */
    public static void stop(Videobridge videobridge)
    {
        VideobridgePeriodicChecker checker = checkers.remove(videobridge);
        if (checker != null)
        {
            checker.stop();
        }
    }

    /**
     * Periodically checks the health for a specific {@link Videobridge}
     * instance and stores the results.
     */
    private static class VideobridgePeriodicChecker
        extends PeriodicRunnableWithObject<Videobridge>
    {
        /**
         * The exception resulting from the last health check performed on this
         * videobridge. When the health check is successful, this is
         * {@code null}.
         */
        private Exception lastResult = null;

        /**
         * The time the last health check finished being performed. A value of
         * {@code -1} indicates that no health check has been performed yet.
         */
        private long lastResultMs = -1;

        /**
         * Initializes a new {@link VideobridgePeriodicChecker} instance for
         * a specific {@link Videobridge} and registers this
         * {@link PeriodicRunnable} with the executor.
         *
         * @param videobridge the {@link Videobridge}.
         */
        VideobridgePeriodicChecker(Videobridge videobridge)
        {
            super(videobridge, HEALTH_CHECK_INTERVAL, true);

            executor.registerRecurringRunnable(this);
        }

        /**
         * Performs a health check and updates this instance's state.
         */
        @Override
        protected void doRun()
        {
            long start = System.currentTimeMillis();
            Exception exception = null;

            try
            {
                Health.doCheck(this.o);
            }
            catch (Exception e)
            {
                exception = e;
            }

            long duration = System.currentTimeMillis() - start;
            lastResult = exception;
            lastResultMs = start + duration;

            if (exception == null)
            {
                logger.info(
                    "Performed a successful health check in " + duration + "ms.");
            }
            else
            {
                logger.error(
                    "Health check failed in " + duration + "ms:", exception);
            }
        }

        /**
         * Stops performing health checks for this {@link Videobridge}.
         */
        private void stop()
        {
            executor.deRegisterRecurringRunnable(this);
        }
    }
}
