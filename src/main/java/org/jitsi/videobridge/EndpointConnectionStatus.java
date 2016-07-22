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

import net.java.sip.communicator.util.Logger;

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
 * longer than {@link #MAX_INACTIVITY_LIMIT} it will be assumed that
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
    implements BundleActivator
{
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
     * How long an endpoint can be inactive before it wil be considered
     * disconnected.
     */
    private final static long MAX_INACTIVITY_LIMIT = 3000L;

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
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
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
        long lastActivity = 0;

        // Go over all RTP channels to get the latest timestamp
        List<RtpChannel> rtpChannels = endpoint.getChannels(null);
        for (RtpChannel channel : rtpChannels)
        {
            long channelLastActivity = channel.getLastTransportActivityTime();
            if (channelLastActivity > lastActivity)
            {
                lastActivity = channelLastActivity;
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
        }

        // Transport not initialized yet
        if (lastActivity == 0)
        {
            logger.debug(endpoint + " not ready for activity checks yet");
            return;
        }

        String endpointId = endpoint.getID();
        long noActivityForMs = System.currentTimeMillis() - lastActivity;
        boolean inactive = noActivityForMs > MAX_INACTIVITY_LIMIT;
        if (inactive && !inactiveEndpoints.contains(endpoint))
        {
            logger.debug(endpointId + " is considered disconnected");

            inactiveEndpoints.add(endpoint);
            // Broadcast connection "inactive" message over data channels
            sendEndpointConnectionStatus(endpoint, false);
        }
        else if (!inactive && inactiveEndpoints.contains(endpoint))
        {
            logger.debug(endpointId + " has reconnected");

            inactiveEndpoints.remove(endpoint);
            // Broadcast connection "active" message over data channels
            sendEndpointConnectionStatus(endpoint, true);
        }

        if (inactive && logger.isDebugEnabled())
        {
            logger.debug(String.format(
                    "No activity on %s for %s",
                    endpointId, (( (double) noActivityForMs) / 1000d )));
        }
    }

    private void sendEndpointConnectionStatus(Endpoint    endpoint,
                                              boolean     isConnected)
    {
        Conference conference = endpoint.getConference();
        if (conference != null)
        {
            // We broadcast the message also to the endpoint itself for
            // debugging purposes
            conference.broadcastMessageOnDataChannels(
                    createEndpointConnectivityStatusChangeEvent(
                            endpoint, isConnected));
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
        }
    }
}
