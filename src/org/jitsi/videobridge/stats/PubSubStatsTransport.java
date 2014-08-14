/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.xmpp.*;
import org.osgi.framework.*;

/**
 * Implements <tt>StatsTransport</tt> for PubSub.
 *
 * @author Hristo Terezov
 */
public class PubSubStatsTransport
    extends StatsTransport
    implements PubSubResponseListener
{
    /**
     * Logger instance.
     */
    private static final Logger logger
        = Logger.getLogger(PubSubStatsTransport.class);

    /**
     * The name of the PubSub node.
     */
    private final String nodeName;

    /**
     * The PubSub manager.
     */
    private PubSubPublisher publisher;

    private final ServiceListener serviceListener
        = new ServiceListener()
        {
            @Override
            public void serviceChanged(ServiceEvent ev)
            {
                PubSubStatsTransport.this.serviceChanged(ev);
            }
        };

    /**
     * The name of the service.
     */
    private final String serviceName;

    /**
     * Initializes a new <tt>PubSubStatsTransport</tt> instance.
     *
     * @param serviceName the name of the service.
     * @param nodeName the name of the PubSub node.
     */
    public PubSubStatsTransport(String serviceName, String nodeName)
    {
        this.serviceName = serviceName;
        this.nodeName = nodeName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void bundleContextChanged(
            BundleContext oldValue,
            BundleContext newValue)
    {
        super.bundleContextChanged(oldValue, newValue);

        if (oldValue != null)
            oldValue.removeServiceListener(serviceListener);
        if (newValue != null)
            newValue.addServiceListener(serviceListener);

        initOrDispose(null);
    }

    /**
     * Releases the resources.
     */
    private void dispose()
    {
        if (publisher != null)
        {
            PubSubPublisher.releasePubsubManager(publisher);
            publisher = null;
        }
    }

    private void init()
    {
        if (publisher == null)
        {
            publisher = PubSubPublisher.getPubsubManager(serviceName);
            publisher.addResponseListener(this);
            try
            {
                publisher.createNode(nodeName);
            }
            catch (Exception e)
            {
                logger.error("Error creating pubsub node.");
                dispose();
            }
        }
    }

    private void initOrDispose(ComponentImpl unregistering)
    {
        BundleContext bundleContext = getBundleContext();
        boolean init, dispose;

        if (bundleContext == null)
        {
            init = false;
            dispose = true;
        }
        else
        {
            Collection<ComponentImpl> components
                = ComponentImpl.getComponents(bundleContext);
            int componentCount = components.size();

            if (unregistering == null)
            {
                init = (componentCount > 0);
                dispose = !init;
            }
            else
            {
                init = false;
                if (components.contains(unregistering))
                {
                    --componentCount;
                }
                dispose = (componentCount < 1);
            }
        }

        if (init)
            init();
        else if (dispose)
            dispose();
    }

    @Override
    public void onCreateNodeResponse(Response response)
    {
        if(Response.FAIL.equals(response))
            dispose();
    }

    @Override
    public void onPublishResponse(Response response)
    {
        if(Response.FAIL.equals(response))
            dispose();
    }

    @Override
    public void publishStatistics(Statistics stats)
    {
        PubSubPublisher publisher = this.publisher;

        if (publisher != null)
        {
            try
            {
                publisher.publish(nodeName, Statistics.toXMPP(stats));
            }
            catch (Exception e)
            {
                logger.error("Failed to publish to PubSub node: " + nodeName);
                dispose();
            }
        }
    }

    private void serviceChanged(ServiceEvent ev)
    {
        int type = ev.getType();

        if ((type == ServiceEvent.REGISTERED)
                || (type == ServiceEvent.UNREGISTERING))
        {
            BundleContext bundleContext = getBundleContext();

            if (bundleContext != null)
            {
                Object service = null;

                try
                {
                    service
                        = bundleContext.getService(ev.getServiceReference());
                }
                catch (IllegalArgumentException ex)
                {
                }
                catch (IllegalStateException ex)
                {
                }
                catch (SecurityException ex)
                {
                }
                if (service instanceof ComponentImpl)
                    initOrDispose((ComponentImpl) service);
            }
        }
    }
}
