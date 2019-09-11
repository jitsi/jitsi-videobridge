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
package org.jitsi.videobridge;

import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.shim.*;
import org.osgi.framework.*;

import java.util.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * This module monitors all endpoints across all conferences currently hosted
 * on the bridge for their connectivity status and sends notifications through
 * the data channel.
 *
 * An endpoint's connectivity status is considered connected as long as there
 * is any traffic activity seen on any of its endpoints. When there is no
 * activity for longer than {@link #maxInactivityLimit} it will be assumed that
 * the endpoint is having some connectivity issues. Those may be temporary or
 * permanent. When that happens there will be a Colibri message broadcast
 * to all conference endpoints. The Colibri class name of the message is defined
 * in {@link EndpointMessageBuilder#COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS}
 * and it will contain "active" attribute set to "false". If those problems turn
 * out to be temporary and the traffic is restored another message is sent with
 * "active" set to "true".
 *
 * @author Pawel Domas
 */
@SuppressWarnings("unused") // started by OSGi
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
        = new LoggerImpl(EndpointConnectionStatus.class.getName());

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
        super(new String[] { EventFactory.MSG_TRANSPORT_READY_TOPIC});
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

        ConfigurationService config = ServiceUtils2.getService(
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
            Videobridge videobridge
                = ServiceUtils2.getService(bundleContext, Videobridge.class);
            cleanupExpiredEndpointsStatus();

            Conference[] conferences = videobridge.getConferences();
            Arrays.stream(conferences)
                .forEachOrdered(
                    conference ->
                        conference.getEndpoints()
                            .forEach(this::monitorEndpointActivity));
        }
    }

    /**
     * Checks the activity for a specific {@link Endpoint}.
     * @param abstractEndpoint the endpoint.
     */
    private void monitorEndpointActivity(AbstractEndpoint abstractEndpoint)
    {
        if (!(abstractEndpoint instanceof Endpoint))
        {
            // We only care about endpoints/participants connected to this
            // bridge, which are of type Endpoint.
            return;
        }

        Endpoint endpoint = (Endpoint) abstractEndpoint;
        String endpointId = endpoint.getID();

        long mostRecentChannelCreated = endpoint.channelShims.stream()
                .mapToLong(ChannelShim::getCreationTimestampMs)
                .max()
                .orElse(0);

        long lastActivity = endpoint.getLastActivity();

        // Transport not initialized yet
        if (lastActivity == 0)
        {
            // Here we check if it's taking too long for the endpoint to connect
            // We're doing that by checking how much time has elapsed since
            // the first endpoint's channel has been created.
            if (System.currentTimeMillis() - mostRecentChannelCreated
                    > firstTransferTimeout)
            {
                if (logger.isDebugEnabled())
                    logger.debug(
                            endpointId + " is having trouble establishing"
                            + " the connection and will be marked as inactive");
                // Let the logic below mark endpoint as inactive.
                // Beware that FIRST_TRANSFER_TIMEOUT constant MUST be greater
                // than MAX_INACTIVITY_LIMIT for this logic to work.
                lastActivity = mostRecentChannelCreated;
            }
            else
            {
                // Wait for the endpoint to connect...
                if (logger.isDebugEnabled())
                    logger.debug(
                            endpointId + " not ready for activity checks yet");
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

    /**
     * Sends a Colibri message about the status of an endpoint to a specific
     * target endpoint or to all endpoints.
     * @param subjectEndpoint the endpoint which the message concerns
     * @param isConnected whether the subject endpoint is connected
     * @param msgReceiver the target endpoint, or {@code null} to broadcast
     * to all endpoints.
     */
    private void sendEndpointConnectionStatus(
        Endpoint subjectEndpoint, boolean isConnected, Endpoint msgReceiver)
    {
        Conference conference = subjectEndpoint.getConference();
        if (conference != null)
        {
            String msg
                = createEndpointConnectivityStatusChangeEvent(
                        subjectEndpoint.getID(), isConnected);
            if (msgReceiver == null)
            {
                // We broadcast the message also to the endpoint itself for
                // debugging purposes, and we also broadcast it through Octo.
                conference.broadcastMessage(msg, true /* sendToOcto */);
            }
            else
            {
                // Send only to the receiver endpoint
                List<AbstractEndpoint> receivers
                    = Collections.singletonList(msgReceiver);
                conference.sendMessage(msg, receivers);
            }
        }
        else
        {
            logger.warn(
                    "Attempt to send connectivity status update for"
                        + " endpoint " + subjectEndpoint.getID()
                        + " without parent conference instance (expired?)");
        }
    }

    /**
     * Prunes the {@link #inactiveEndpoints} list.
     */
    private void cleanupExpiredEndpointsStatus()
    {
        inactiveEndpoints.removeIf(e -> {
            Conference conference = e.getConference();
            AbstractEndpoint replacement = conference.getEndpoint(e.getID());
            boolean endpointReplaced = replacement != null && replacement != e;

            // If an Endpoint from the inactive list has been re-created it
            // means that at this point all participants currently have it in
            // the "inactive" state, so broadcast "active" in order to reset.
            if (endpointReplaced)
            {
                if (replacement instanceof Endpoint)
                {
                    sendEndpointConnectionStatus(
                        (Endpoint) replacement, true, null);
                }
            }

            return conference.isExpired() || endpointReplaced;
        });
        if (logger.isDebugEnabled())
        {
            inactiveEndpoints.stream()
                .filter(Endpoint::isExpired)
                .forEach(
                    e ->
                        logger.debug("Endpoint has expired: " + e.getID()
                            + ", but is still on the list"));
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        // Verify the topic just in case
        // FIXME eventually add this verification to the base class
        String topic = event.getTopic();
        if (!EventFactory.MSG_TRANSPORT_READY_TOPIC.equals(topic))
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
        inactiveEndpoints.stream()
            .filter(e -> e.getConference() == conference)
            .forEach(e -> sendEndpointConnectionStatus(e, false, endpoint));
    }
}
