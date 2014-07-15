/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.stats.transport.StatsTransportEvent.*;

/**
 * Implements <tt>StatsTransport</tt> for PubSub.
 *
 * @author Hristo Terezov
 */
public class PubsubStatsTransport
    extends StatsTransport
    implements PubsubResponseListener
{
    /**
     * The PubSub manager.
     */
    private PubsubManager pubsubManager;

    /**
     * The name of the PubSub node.
     */
    private String nodeName;

    /**
     * The name of the service.
     */
    private String serviceName;

    /**
     * The <tt>Videobridge</tt> instance.
     */
    private Videobridge videobridge;

    /**
     * Logger instance.
     */
    private static Logger logger = Logger.getLogger(PubsubStatsTransport.class);

    /**
     * Creates instance of <tt>PubsubStatsTransport</tt>
     * @param videobridge the <tt>Videobridge</tt> instance.
     * @param serviceName the name of the service.
     * @param nodeName the name of the PubSub node.
     */
    public PubsubStatsTransport(Videobridge videobridge,
        String serviceName, String nodeName)
    {
        this.nodeName = nodeName;
        this.serviceName = serviceName;
        this.videobridge = videobridge;
    }

    @Override
    public void init()
    {
        pubsubManager = PubsubManager.getPubsubManager(serviceName, videobridge);
        pubsubManager.addResponseListener(this);
        try
        {
            pubsubManager.createNode(nodeName);
        }
        catch (Exception e)
        {
            logger.error("Error creating pubsub node.");
            fireStatsTransportEvent(
                new StatsTransportEvent(StatTransportEventTypes.INIT_FAIL));
            dispose();
        }
    }

    /**
     * Releases the resources.
     */
    public void dispose()
    {
        PubsubManager.releasePubsubManager(pubsubManager);
        pubsubManager = null;
        videobridge = null;
    }

    @Override
    public void publishStatistics(Statistics stats)
    {
        try
        {
            pubsubManager.publish(nodeName, StatsFormatter.format(stats));
        }
        catch (Exception e)
        {
            logger.error("Error publishing to pubsub node.");
            fireStatsTransportEvent(
                new StatsTransportEvent(StatTransportEventTypes.PUBLISH_FAIL));
            dispose();
        }
    }

    @Override
    public void onCreateNodeResponse(Response response)
    {
        StatsTransportEvent event;
        if(Response.SUCCESS.equals(response))
        {
            event
                = new StatsTransportEvent(StatTransportEventTypes.INIT_SUCCESS);
        }
        else
        {
            dispose();
            event
                = new StatsTransportEvent(StatTransportEventTypes.INIT_FAIL);
        }
        fireStatsTransportEvent(event);
    }

    @Override
    public void onPublishResponse(Response response)
    {
        StatsTransportEvent event;
        if(Response.SUCCESS.equals(response))
        {
            event
                = new StatsTransportEvent(
                    StatTransportEventTypes.PUBLISH_SUCCESS);
        }
        else
        {
            dispose();
            event
                = new StatsTransportEvent(StatTransportEventTypes.PUBLISH_FAIL);
        }
        fireStatsTransportEvent(event);
    }
}
