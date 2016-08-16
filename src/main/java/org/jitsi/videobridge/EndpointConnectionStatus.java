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
package org.jitsi.videobridge;

import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;

import org.jitsi.service.configuration.*;
import org.json.simple.*;

import org.osgi.framework.*;

import java.util.*;

/**
 * This module monitors all endpoints across all conferences currently hosted
 * on the bridge for their connectivity status and sends notifications through
 * the data channel.
 *
 * An endpoint's connectivity status is considered connected as long as there
 * is any traffic activity seen on any of it's channels as defined in
 * {@link Channel#lastTransportActivityTime}. When there is no activity for
 * longer than {@link #maxInactivityLimit} it will be assumed that
 * the endpoint is having some connectivity issues. Those may be temporary or
 * permanent. When that happens there will be a Colibri message broadcasted
 * to all conference endpoints. The Colibri class name of the message is defined
 * in {@link #COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS} and it will contain
 * "active" attribute set to "false". If those problems turn out to be temporary
 * and the traffic is restored another message is sent with "active" set to
 * "true".
 *
 * The modules is started by OSGi as configured in
 * {@link org.jitsi.videobridge.osgi.JvbBundleConfig}
 *
 * @author Pawel Domas
 */
public class EndpointConnectionStatus
    extends EventHandlerActivator
{
    /**
     * The base for config property names constants.
     */
    private final static String CFG_PNAME_BASE
        = "org.jitsi.videobridge.EndpointConnectionStatus";

    /**
     * The name of the configuration property which configures
     * {@link #firstTransferTimeout}.
     */
    public final static String CFG_PNAME_FIRST_TRANSFER_TIMEOUT
        = CFG_PNAME_BASE + ".FIRST_TRANSFER_TIMEOUT";

    /**
     * The name of the configuration property which configures
     * {@link #maxInactivityLimit}.
     */
    public static final String CFG_PNAME_MAX_INACTIVITY_LIMIT
        = CFG_PNAME_BASE + ".MAX_INACTIVITY_LIMIT";

    /**
     * The default value for {@link #firstTransferTimeout}.
     */
    private final static long DEFAULT_FIRST_TRANSFER_TIMEOUT = 15000L;

    /**
     * The default value for {@link #maxInactivityLimit}.
     */
    private final static long DEFAULT_MAX_INACTIVITY_LIMIT = 3000L;

    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(EndpointConnectionStatus.class);

    /**
     * Constant value defines the name of "colibriClass" for connectivity status
     * notifications sent over the data channels.
     */
    private static final String COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS
        = "EndpointConnectivityStatusChangeEvent";

    /**
     * How long it can take an endpoint to send first data, before it will
     * be marked as inactive.
     */
    private long firstTransferTimeout;

    /**
     * How long an endpoint can be inactive before it wil be considered
     * disconnected.
     */
    private long maxInactivityLimit;

    /**
     * How often connectivity status is being probed. Value in milliseconds.
     */
    private final static long PROBE_INTERVAL = 500L;

    /**
     * OSGi BC for this module.
     */
    private BundleContext bundleContext;

    /**
     * The list of <tt>Endpoint</tt>s which have current their connection status
     * classified as inactive.
     */
    private List<Endpoint> inactiveEndpoints = new LinkedList<>();

    /**
     * The timer which runs the periodical connection status probing operation.
     */
    private Timer timer;

    /**
     * Creates new instance of <tt>EndpointConnectionStatus</tt>
     */
    public EndpointConnectionStatus()
    {
        super(new String[] { EventFactory.SCTP_CONN_READY_TOPIC });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        if (timer == null)
        {
            timer = new Timer("EndpointConnectionStatusMonitoring", true);
            timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    doMonitor();
                }
            }, PROBE_INTERVAL, PROBE_INTERVAL);
        }
        else
        {
            logger.error("Endpoint connection monitoring is already running");
        }

        ConfigurationService config = ServiceUtils.getService(
                bundleContext, ConfigurationService.class);

        firstTransferTimeout = config.getLong(
                CFG_PNAME_FIRST_TRANSFER_TIMEOUT,
                DEFAULT_FIRST_TRANSFER_TIMEOUT);

        maxInactivityLimit = config.getLong(
                CFG_PNAME_MAX_INACTIVITY_LIMIT,
                DEFAULT_MAX_INACTIVITY_LIMIT);

        if (firstTransferTimeout <= maxInactivityLimit)
        {
            throw new IllegalArgumentException(
                String.format("FIRST_TRANSFER_TIMEOUT(%s) must be greater"
                            + " than MAX_INACTIVITY_LIMIT(%s)",
                        firstTransferTimeout, maxInactivityLimit));
        }

        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        super.stop(bundleContext);

        if (timer != null)
        {
            timer.cancel();
            timer = null;
        }

        inactiveEndpoints.clear();

        this.bundleContext = null;
    }

    /**
     * Periodic task which is executed in {@link #PROBE_INTERVAL} intervals.
     * Monitors endpoints connectivity status.
     */
    private void doMonitor()
    {
        BundleContext bundleContext = this.bundleContext;
        if (bundleContext != null)
        {
            Collection<Videobridge> jvbs
                = Videobridge.getVideobridges(bundleContext);
            for (Videobridge videobridge : jvbs)
            {
                Conference[] conferences = videobridge.getConferences();
                for (Conference conference : conferences)
                {
                    List<Endpoint> endpoints = conference.getEndpoints();
                    for (Endpoint endpoint : endpoints)
                    {
                        monitorEndpointActivity(endpoint);
                    }
                }

                cleanupExpiredEndpointsStatus();
            }
        }
    }

    private void monitorEndpointActivity(Endpoint endpoint)
    {
        String endpointId = endpoint.getID();
        long lastActivity = 0;
        long mostRecentChannelCreated = 0;

        // Go over all RTP channels to get the latest timestamp
        List<RtpChannel> rtpChannels = endpoint.getChannels(null);
        for (RtpChannel channel : rtpChannels)
        {
            long channelLastActivity = channel.getLastTransportActivityTime();
            if (channelLastActivity > lastActivity)
            {
                lastActivity = channelLastActivity;
            }
            long creationTimestamp = channel.getCreationTimestamp();
            if (creationTimestamp > mostRecentChannelCreated)
            {
                mostRecentChannelCreated = creationTimestamp;
            }
        }
        // Also check SctpConnection
        SctpConnection sctpConnection = endpoint.getSctpConnection();
        if (sctpConnection != null)
        {
            long lastSctpActivity
                = sctpConnection.getLastTransportActivityTime();
            if (lastSctpActivity > lastActivity)
            {
                lastActivity = lastSctpActivity;
            }
            long creationTimestamp = sctpConnection.getCreationTimestamp();
            if (creationTimestamp > mostRecentChannelCreated)
            {
                mostRecentChannelCreated = creationTimestamp;
            }
        }

        // Transport not initialized yet
        if (lastActivity == 0)
        {
            // Here we check if it's taking too long for the endpoint to connect
            // We're doing that by checking how much time has elapsed since
            // the first endpoint's channel has been created.
            if (System.currentTimeMillis() - mostRecentChannelCreated
                    > firstTransferTimeout)
            {
                logger.debug(endpointId + " is having trouble establishing"
                        + " the connection and will be marked as inactive");
                // Let the logic below mark endpoint as inactive.
                // Beware that FIRST_TRANSFER_TIMEOUT constant MUST be greater
                // than MAX_INACTIVITY_LIMIT for this logic to work.
                lastActivity = mostRecentChannelCreated;
            }
            else
            {
                // Wait for the endpoint to connect...
                logger.debug(endpointId + " not ready for activity checks yet");
                return;
            }
        }

        long noActivityForMs = System.currentTimeMillis() - lastActivity;
        boolean inactive = noActivityForMs > maxInactivityLimit;
        if (inactive && !inactiveEndpoints.contains(endpoint))
        {
            logger.debug(endpointId + " is considered disconnected");

            inactiveEndpoints.add(endpoint);
            // Broadcast connection "inactive" message over data channels
            sendEndpointConnectionStatus(endpoint, false, null);
        }
        else if (!inactive && inactiveEndpoints.contains(endpoint))
        {
            logger.debug(endpointId + " has reconnected");

            inactiveEndpoints.remove(endpoint);
            // Broadcast connection "active" message over data channels
            sendEndpointConnectionStatus(endpoint, true, null);
        }

        if (inactive && logger.isDebugEnabled())
        {
            logger.debug(String.format(
                    "No activity on %s for %s",
                    endpointId, (( (double) noActivityForMs) / 1000d )));
        }
    }

    private void sendEndpointConnectionStatus(Endpoint    subjectEndpoint,
                                              boolean     isConnected,
                                              Endpoint    msgReceiver)
    {
        Conference conference = subjectEndpoint.getConference();
        if (conference != null)
        {
            String msg
                = createEndpointConnectivityStatusChangeEvent(
                        subjectEndpoint, isConnected);
            if (msgReceiver == null)
            {
                // We broadcast the message also to the endpoint itself for
                // debugging purposes
                conference.broadcastMessageOnDataChannels(msg);
            }
            else
            {
                // Send only to the receiver endpoint
                ArrayList<Endpoint> receivers = new ArrayList<>(1);
                receivers.add(msgReceiver);

                conference.sendMessageOnDataChannels(msg, receivers);
            }
        }
        else
        {
            logger.warn("Attempt to send connectivity status update for " +
                    "endpoint without parent conference instance(expired?)");
        }
    }

    private String createEndpointConnectivityStatusChangeEvent(
            Endpoint endpoint, boolean connected)
    {
        return
            "{\"colibriClass\":\""
                + COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS
                + "\",\"endpoint\":\"" + JSONValue.escape(endpoint.getID())
                +"\", \"active\":\"" + String.valueOf(connected)
                + "\"}";
    }

    private void cleanupExpiredEndpointsStatus()
    {
        Iterator<Endpoint> endpoints = inactiveEndpoints.iterator();
        while (endpoints.hasNext())
        {
            Endpoint endpoint = endpoints.next();
            if (endpoint.getConference().isExpired())
            {
                logger.debug("Removing endpoint from expired conference: "
                        + endpoint.getID());

                endpoints.remove();
            }
            else if (endpoint.isExpired() && logger.isDebugEnabled())
            {
                logger.debug(
                        "Endpoint has expired: " + endpoint.getID()
                            + ", but still on the list");
            }
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        // Verify the topic just in case
        // FIXME eventually add this verification to the base class
        String topic = event.getTopic();
        if (!EventFactory.SCTP_CONN_READY_TOPIC.equals(topic))
        {
            logger.warn("Received event for unexpected topic: " + topic);
            return;
        }

        Endpoint endpoint
            = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
        if (endpoint == null)
        {
            logger.error("Endpoint is null");
            return;
        }

        Conference conference = endpoint.getConference();
        if (conference == null || conference.isExpired())
        {
            // Received event for endpoint which is now expired - ignore
            return;
        }

        // Go over all endpoints in the conference and send notification about
        // those currently "inactive"
        //
        // Looping over all inactive endpoints of all conferences maybe is not
        // the most efficient, but it should not be extremely large number.
        for (Endpoint potentialSubject : inactiveEndpoints)
        {
            if (potentialSubject.getConference() == conference)
            {
                sendEndpointConnectionStatus(
                        potentialSubject, false, endpoint);
            }
        }
    }
}
