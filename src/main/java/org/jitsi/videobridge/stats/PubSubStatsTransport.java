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
package org.jitsi.videobridge.stats;

import java.util.*;

import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.osgi.framework.*;

/**
 * Implements <tt>StatsTransport</tt> for PubSub.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class PubSubStatsTransport
    extends StatsTransport
    implements PubSubResponseListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>PubSubStatsTransport</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(PubSubStatsTransport.class.getName());

    /**
     * The ID of PubSub item which stores bridge statistics.
     * As a value the JID of first currently registered <tt>ComponentImpl</tt>
     * service will be used.
     */
    private String itemId;

    /**
     * The name of the PubSub node.
     */
    private final String nodeName;

    /**
     * The PubSub manager.
     */
    private PubSubPublisher publisher;

    /**
     * The <tt>ServiceListener</tt> which listens to the <tt>BundleContext</tt>
     * in which this <tt>StatsTransport</tt> is started in order to track when
     * <tt>ComponentImpl</tt>s are registered and unregistering.
     */
    private final ServiceListener serviceListener = this::serviceChanged;

    /**
     * The name of the service.
     */
    private final Jid serviceName;

    /**
     * Initializes a new <tt>PubSubStatsTransport</tt> instance.
     *
     * @param serviceName the name of the service.
     * @param nodeName the name of the PubSub node.
     */
    public PubSubStatsTransport(Jid serviceName, String nodeName)
    {
        this.serviceName = serviceName;
        this.nodeName = nodeName;
    }

    /**
     * {@inheritDoc}
     *
     * Initializes this <tt>StatsTransport</tt> if it has not been initialized
     * yet.
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
     * Disposes of this instance by releasing the resources it has acquired by
     * now and, effectively, preparing it for garbage collection.
     */
    private void dispose()
    {
        if (publisher != null)
        {
            publisher.removeResponseListener(this);
            PubSubPublisher.releasePubsubManager(publisher);
            publisher = null;
        }
    }

    /**
     * Initializes this <tt>StatsTransport</tt> by initializing a new PubSub
     * publisher if this <tt>StatsTransport</tt> does not have an associated
     * PubSub publisher yet; otherwise, does nothing.
     */
    private void init()
    {
        if (publisher == null)
        {
            // We're using JID as PubSub item ID of the first
            // registered ComponentImpl service
            Iterator<ComponentImpl> components
                = ComponentImpl.getComponents(getBundleContext()).iterator();
            if (components.hasNext())
            {
                itemId = components.next().getJID().toString();
            }

            publisher = PubSubPublisher.getPubsubManager(serviceName);
            publisher.addResponseListener(this);
            try
            {
                publisher.createNode(nodeName);
            }
            catch (Exception ex)
            {
                logger.error("Failed to create PubSub node: " + nodeName);
                dispose();
            }
        }
    }

    /**
     * Invokes either {@link #init()} or {@link #dispose()} depending on the
     * state of this instance and on whether the method invocation is the result
     * of the unregistering of a specific <tt>ComponentImpl</tt>.
     *
     * @param unregistering the <tt>ComponentImpl</tt> which is unregistering
     * and thus has caused the method invocation if any; otherwise,
     * <tt>null</tt> 
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreateNodeResponse(Response response)
    {
        if(Response.FAIL.equals(response))
            dispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPublishResponse(Response type, IQ iq)
    {
        if (Response.FAIL.equals(type))
        {
            // It appears that Prosody may destroy the node for unknown reasons.
            // We want to continue publishing statistics in such a case though
            // so we have to re-create the node.
            XMPPError err = iq.getError();

            if (err != null
                    && XMPPError.Type.CANCEL.equals(err.getType())
                    && XMPPError.Condition.item_not_found.equals(
                            err.getCondition()))
            {
                // We are about to attempt to resurrect this
                // PubSubStatsTransport which means that it must have been alive
                // at some point.
                PubSubPublisher publisher = this.publisher;

                if (publisher != null)
                {
                    String nodeName = this.nodeName;

                    try
                    {
                        publisher.createNode(nodeName);
                        // Do not abandon/dispose of this PubSubStatsTransport
                        // because we've just initiated its resurrection.
                        return;
                    }
                    catch (Exception ex)
                    {
                        logger.error(
                                "Failed to re-create PubSub node: " + nodeName);
                        // Fall through and, thus, abandon/dispose of this
                        // PubSubStatsTransport because we failed to resurrect
                        // it.
                    }
                }
            }

            dispose();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStatistics(Statistics stats)
    {
        PubSubPublisher publisher = this.publisher;

        if (publisher != null)
        {
            try
            {
                publisher.publish(nodeName, itemId,
                    Statistics.toXmppExtensionElement(stats));
            }
            catch (IllegalArgumentException e)
            {
                logger.error(
                    "Failed to publish to PubSub node: " + nodeName +
                        " - it does not exist yet");
            }
            catch (Exception e)
            {
                logger.error(
                    "Failed to publish to PubSub node: " + nodeName, e);
                dispose();
            }
        }
    }

    /**
     * Notifies this instance that there was an OSGi service-related change in
     * the <tt>BundleContext</tt> in which this <tt>StatsTransport</tt> is
     * started. Initializes or disposes of this <tt>StatsTransport</tt>
     * depending on whether there is a Jabber component
     * (protocol implementation) registered in the <tt>BundleContext</tt>
     * because this <tt>StatsTransport</tt> utilizes XMPP to transport
     * statistics.
     *
     * @param ev a <tt>ServiceEvent</tt> which details the specifics of the OSGi
     * service-related change of which this instance is being notified
     */
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
                catch ( IllegalArgumentException
                        | IllegalStateException | SecurityException ex )
                {
                    logger.debug(() -> "An unexpected exception occurred: " + ex.toString());
                }
                if (service instanceof ComponentImpl)
                {
                    initOrDispose(
                            (type == ServiceEvent.UNREGISTERING)
                                ? (ComponentImpl) service
                                : null);
                }
            }
        }
    }
}
