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
package org.jitsi.videobridge.health;

import org.ice4j.ice.harvest.*;
import org.jitsi.health.*;
import org.jitsi.osgi.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.ice.*;
import org.jitsi.videobridge.xmpp.*;
import org.osgi.framework.*;

import java.io.*;
import java.util.*;

import static org.jitsi.videobridge.health.config.HealthConfig.*;

/**
 * Checks the health of {@link Videobridge}.
 *
 * @author Lyubomir Marinov
 */
public class Health
    extends AbstractHealthCheckService
{
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
    {
        final int numEndpoints = 2;
        //ArrayList<Endpoint> endpoints = new ArrayList<>(numEndpoints);

        for (int i = 0; i < numEndpoints; ++i)
        {
            final Endpoint endpoint;
            try
            {
                final boolean iceControlling = i % 2 == 0;
                endpoint = conference.createLocalEndpoint(
                    generateEndpointID(), iceControlling);
            }
            catch (IOException ioe)
            {
                throw new RuntimeException(ioe);
            }

            //endpoints.add(endpoint);

            endpoint.createSctpConnection();
        }


        // NOTE(brian): The below connection won't work with single port mode.  I think this is because both agent's
        // bind to the single port and we can't demux the ice packets correctly.  Forcing non-single port mode (via
        // hardcoding rtcpmux to false elsewhere) works, but causes other problems since we don't properly support
        // non-rtcpmux.

//        Endpoint ep0 = endpoints.get(0);
//        TransportManager ep0TransportManager =
//                conferenceShim.conference.getTransportManager(ep0.getID(), false, false);
//
//        Endpoint ep1 = endpoints.get(1);
//        TransportManager ep1TransportManager =
//                conferenceShim.conference.getTransportManager(ep1.getID(), false, false);
//
//        // Connect endpoint 0 to endpoint 1
//        ColibriConferenceIQ.ChannelBundle channelBundle0Iq = new ColibriConferenceIQ.ChannelBundle(ep0.getID());
//        ColibriShim.ChannelBundleShim channelBundle0Shim = conferenceShim.getChannelBundle(ep0.getID());
//        channelBundle0Shim.describe(channelBundle0Iq);
//        IceUdpTransportPacketExtension tpe = channelBundle0Iq.getTransport();
//        ep1TransportManager.start(channelBundle0Iq.getTransport());
//
//        // Connect endpoint 1 to endpoint 0
//        ColibriConferenceIQ.ChannelBundle channelBundle1Iq = new ColibriConferenceIQ.ChannelBundle(ep1.getID());
//        ColibriShim.ChannelBundleShim channelBundle1Shim = conferenceShim.getChannelBundle(ep1.getID());
//        channelBundle1Shim.describe(channelBundle1Iq);
//        ep0TransportManager.start(channelBundle1Iq.getTransport());
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

        if (!Harvesters.isHealthy())
        {
            throw new Exception("Failed to bind single-port");
        }

        checkXmppConnection(videobridge);

        // Conference
        Conference conference =
                videobridge.createConference(null, null, false, null);

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
                videobridge.expireConference(conference);
            }
        }
    }

    /**
     * Checks if this {@link Videobridge} has an XMPP component and its
     * connection is alive. Throws an exception if this isn't the case.
     * an XMPP component,
     *
     * @param videobridge the {@code Videobridge} to check the XMPP connection
     *                    status of
     */
    private static void checkXmppConnection(Videobridge videobridge)
        throws Exception
    {
        // If XMPP API was requested, but there isn't any XMPP component
        // registered we shall return false (no valid XMPP connection)
        Collection<ComponentImpl> components = videobridge.getComponents();
        if (videobridge.isXmppApiEnabled() && components.size() == 0)
        {
            throw new Exception("No XMPP components");
        }

        for (ComponentImpl component : components)
        {
            if (!component.isConnectionAlive())
            {
                throw new Exception(
                    "XMPP component not connected: " + component);
            }
        }
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
        return String.format("%08x", RANDOM.nextInt());
    }

    private Videobridge videobridge;

    /**
     * Iniatializes a new {@link Health} instance for a specific
     * {@link Videobridge}.
     */
    public Health()
    {
        super(Config.getInterval(), Config.getTimeout(), Config.getMaxCheckDuration(), Config.stickyFailures());
    }

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        videobridge = ServiceUtils2.getService(bundleContext, Videobridge.class);

        super.start(bundleContext);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        videobridge = null;

        super.stop(bundleContext);
    }

    /**
     * Checks the health of this {@link Videobridge}. This method only returns
     * the cache results, it does not do the actual health check (i.e. creating
     * a test conference).
     *
     * @throws Exception if an error occurs while checking the health (status)
     * of {@code videobridge} or the check determines that {@code videobridge}
     * is not healthy.
     */
    @Override
    protected void performCheck()
        throws Exception
    {
        doCheck(videobridge);
    }

}
