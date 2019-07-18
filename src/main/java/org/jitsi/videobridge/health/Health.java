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
import org.jitsi.service.configuration.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.transport.*;
import org.jitsi.videobridge.xmpp.*;

import java.io.*;
import java.util.*;

/**
 * Checks the health of {@link Videobridge}.
 *
 * @author Lyubomir Marinov
 */
public class Health
    extends PeriodicRunnableWithObject<Videobridge>
{
    /**
     * The {@link Logger} used by the {@link Health} class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Health.class);

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
     * The default interval between health checks.
     */
    private static final int PERIOD_DEFAULT = 10000;

    /**
     * The name of the property which configures the interval between health
     * checks.
     */
    public static final String PERIOD_PNAME
        = "org.jitsi.videobridge.health.INTERVAL";

    /**
     * The default timeout for health checks.
     */
    private static final int TIMEOUT_DEFAULT = 30000;

    /**
     * The name of the property which configures the timeout for health checks.
     * The {@link #check()} API will return failure unless a there was a health
     * check performed in the last that many milliseconds.
     */
    public static final String TIMEOUT_PNAME
        = "org.jitsi.videobridge.health.TIMEOUT";

    /**
     * The name of the property which makes any failures sticky (i.e. once the
     * bridge becomes unhealthy it will never go back to a healthy state).
     */
    public static final String STICKY_FAILURES_PNAME
        = "org.jitsi.videobridge.health.STICKY_FAILURES";

    /**
     * The default value for the {@code STICKY_FAILURES} property.
     */
    private static final boolean STICKY_FAILURES_DEFAULT = false;

    /**
     * Failures in the first 5 minutes are never sticky.
     */
    private static final long STICKY_FAILURES_GRACE_PERIOD = 300_000;

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
        ArrayList<Endpoint> endpoints = new ArrayList<>(numEndpoints);

        for (int i = 0; i < numEndpoints; ++i)
        {
            Endpoint endpoint
                = conference.getOrCreateLocalEndpoint(generateEndpointID());

            // Fail as quickly as possible.
            if (endpoint == null)
            {
                throw new NullPointerException("Failed to create an endpoint.");
            }

            // Trigger the creation of the transport manager.
            try
            {
                endpoint.getTransportManager();
            }
            catch (IOException ioe)
            {
                throw new RuntimeException(ioe);
            }

            endpoints.add(endpoint);

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

        if (!Harvesters.healthy)
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
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

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
     * The timeout in milliseconds after which this videobridge will be
     * considered unhealthy; i.e. if no health check has been completed in the
     * last {@code timeout} milliseconds the bridge is unhealthy.
     */
    private final int timeout;

    /**
     * Whether failures are sticky, i.e. once the bridge becomes unhealthy it
     * will never go back to a healthy state.
     */
    private final boolean stickyFailures;

    /**
     * The time when this instance was started.
     */
    private final long startMs;

    /**
     * Whether we've seen a health check failure.
     */
    private boolean hasFailed = false;

    /**
     * Iniatializes a new {@link Health} instance for a specific
     * {@link Videobridge}.
     */
    public Health(Videobridge videobridge, ConfigurationService cfg)
    {
        super(videobridge, PERIOD_DEFAULT, true);

        if (cfg == null)
        {
            logger.warn("Configuration service is null, using only defaults.");
        }

        int period =
            cfg == null ? PERIOD_DEFAULT
                : cfg.getInt(PERIOD_PNAME, PERIOD_DEFAULT);
        setPeriod(period);

        timeout =
            cfg == null ? TIMEOUT_DEFAULT
                : cfg.getInt(TIMEOUT_PNAME, TIMEOUT_DEFAULT);

        stickyFailures
            = cfg == null ? STICKY_FAILURES_DEFAULT
                : cfg.getBoolean(
                    STICKY_FAILURES_PNAME, STICKY_FAILURES_DEFAULT);

        startMs = System.currentTimeMillis();

        executor.registerRecurringRunnable(this);
    }

    /**
     * Stops running health checks for this {@link Videobridge}.
     */
    public void stop()
    {
        executor.deRegisterRecurringRunnable(this);
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
            if (System.currentTimeMillis() - this.startMs
                > STICKY_FAILURES_GRACE_PERIOD)
            {
                hasFailed = true;
            }
        }

        long duration = System.currentTimeMillis() - start;
        lastResultMs = start + duration;

        if (stickyFailures && hasFailed && exception == null)
        {
            // We didn't fail this last test, but we've failed before and
            // sticky failures are enabled.
            lastResult = new Exception("Sticky failure.");
        }
        else
        {
            lastResult = exception;
        }

        if (exception == null)
        {
            logger.info(
                "Performed a successful health check in " + duration
                    + "ms. Sticky failure: " + (stickyFailures && hasFailed));
        }
        else
        {
            logger.error(
                "Health check failed in " + duration + "ms:", exception);
        }
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
    public void check()
        throws Exception
    {
        Exception lastResult = this.lastResult;
        long lastResultMs = this.lastResultMs;
        long timeSinceLastResult = System.currentTimeMillis() - lastResultMs;

        if (timeSinceLastResult > timeout)
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

}
