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
package org.jitsi.videobridge.pubsub;

import java.util.*;
import java.util.concurrent.*;

import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.packet.*;
import org.jivesoftware.smackx.xdata.packet.*;
import org.jxmpp.jid.*;
import org.osgi.framework.*;

/**
 * Implements some parts of PubSub (XEP-0060: Publish-Subscribe) for the
 * purposes of a publisher (e.g. statistics transport).
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class PubSubPublisher
{
    /**
     * Maps a service name (e.g. &quot;pubsub.example.com&quot;) to the
     * <tt>PubSubPublisher</tt> instance responsible for it.
     */
    private static final Map<Jid, PubSubPublisher> instances
        = new ConcurrentHashMap<>();

    /**
     * The <tt>Logger</tt> used by the <tt>PubSubPublisher</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(PubSubPublisher.class);

    /**
     * The default timeout of the packets in milliseconds.
     */
    private static final int PACKET_TIMEOUT = 5000;

    /**
     * Gets a <tt>PubSubPublisher</tt> instance for a specific service (name).
     * If a <tt>PubSubPublisher</tt> instance for the specified
     * <tt>serviceName</tt> does not exist yet, a new instance is initialized.
     *
     * @param serviceName the name of the service
     * @return the <tt>PubSubPublisher</tt> instance for the specified
     * <tt>serviceName</tt>
     */
    public static PubSubPublisher getPubsubManager(Jid serviceName)
    {
        PubSubPublisher publisher = instances.get(serviceName);

        if (publisher == null)
        {
            publisher = new PubSubPublisher(serviceName);
            instances.put(serviceName, publisher);
        }

        return publisher;
    }

    /**
     * Handles received response IQ packet.
     *
     * @param response the IQ packet.
     */
    public static void handleIQResponse(IQ response)
    {
        IQ.Type type = response.getType();

        if (IQ.Type.error.equals(type))
        {
            PubSubPublisher publisher = instances.get(response.getFrom());

            if (publisher != null)
            {
                publisher.handleErrorResponse(response);
            }
        }
        else if (IQ.Type.result.equals(type))
        {
            PubSubPublisher publisher = instances.get(response.getFrom());

            if (publisher != null)
            {
                publisher.handleCreateNodeResponse(response);
                publisher.handleConfigureResponse(response);
                publisher.handlePublishResponse(response);
            }
        }
    }

    /**
     * Releases the resources for the <tt>PubSubPublisher</tt> and removes it
     * from the list of available instances.
     *
     * @param publisher the <tt>PubSubPublisher</tt> release.
     */
    public static void releasePubsubManager(PubSubPublisher publisher)
    {
        instances.values().remove(publisher);
        publisher.dispose();
    }

    /**
     * Listeners for response events.
     */
    private List<PubSubResponseListener> listeners = new LinkedList<>();

    /**
     * List of the accessible PubSub nodes.
     */
    private List<String> nodes = new LinkedList<>();

    /**
     * Map with the requests for configuring a node.
     */
    private Map<String, String> pendingConfigureRequests
        = new ConcurrentHashMap<>();

    /**
     * Map with the requests for node creation.
     */
    private Map<String, String> pendingCreateRequests
        = new ConcurrentHashMap<>();

    /**
     * Map with the publish requests.
     */
    private Map<String, String> pendingPublishRequests
        = new ConcurrentHashMap<>();

    /**
     * The name of the PubSub service.
     */
    private Jid serviceName;

    /**
     * Timer for timeout of the requests that we are sending.
     */
    private Timer timeoutTimer = new Timer();

    /**
     * Initializes a new <tt>PubSubPublisher</tt> instance for a specific
     * service (name).
     *
     * @param serviceName the name of the service.
     */
    private PubSubPublisher(Jid serviceName)
    {
        this.serviceName = serviceName;
    }

    /**
     * Adds a new <tt>PubSubResponseListener</tt> to the list of listeners.
     *
     * @param l the listener to add.
     * @throws NullPointerException if <tt>l</tt> is <tt>null</tt>
     */
    public void addResponseListener(PubSubResponseListener l)
    {
        if (l == null)
            throw new NullPointerException("l");
        else if(!listeners.contains(l))
            listeners.add(l);
    }

    /**
     * Configures PubSub node.
     *
     * @param nodeName the name of the node
     */
    private void configureNode(String nodeName)
    {
        ConfigureForm cfg = new ConfigureForm(DataForm.Type.submit);
        PubSub pubsub = new PubSub();

        cfg.setAccessModel(AccessModel.open);
        cfg.setPersistentItems(false);
        cfg.setPublishModel(PublishModel.open);
        pubsub.setTo(serviceName);
        pubsub.setType(IQ.Type.set);

        final String packetID = StanzaIdUtil.newStanzaId();

        pubsub.setStanzaId(packetID);
        pubsub.addExtension(
            new FormNode(FormNodeType.CONFIGURE_OWNER, nodeName ,cfg));
        try
        {
            send(pubsub);
        }
        catch (Exception e)
        {
            logger.error("Error sending configuration form.");
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
            return;
        }
        pendingConfigureRequests.put(packetID, nodeName);
        timeoutTimer.schedule(
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        String nodeName
                            = pendingConfigureRequests.remove(packetID);

                        if(nodeName != null)
                        {
                            logger.error(
                                    "Timed out a configuration request "
                                        + "(packetID=: " + packetID
                                        + " nodeName=" + nodeName + ")");
                            fireResponseCreateEvent(
                                    PubSubResponseListener.Response.SUCCESS);
                        }
                    }
                },
                PACKET_TIMEOUT);
    }

    /**
     * Creates a PubSub node.
     *
     * @param nodeName the name of the node.
     * @throws Exception if sending the request fails.
     */
    public void createNode(String nodeName)
        throws Exception
    {
        PubSub request = new PubSub();

        request.setTo(serviceName);
        request.setType(IQ.Type.set);

        final String packetID = StanzaIdUtil.newStanzaId();

        request.setStanzaId(packetID);
        request.addExtension(
                new NodeExtension(PubSubElementType.CREATE, nodeName));

        pendingCreateRequests.put(packetID, nodeName);

        // Send the request before starting the timer, as we have observed
        // sending to be significantly delayed (possibly waiting for the XMPP
        // component connection to become ready).
        send(request);

        timeoutTimer.schedule(
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        String nodeName = pendingCreateRequests.remove(packetID);
                        if (nodeName != null)
                        {
                            logger.warn("Timed out a create request with ID "
                                            + packetID);
                        }
                    }
                },
                PACKET_TIMEOUT);
    }

    /**
     * Releases the resources of this <tt>PubSubPublisher</tt> i.e. prepares it
     * for garbage collection.
     */
    private void dispose()
    {
        timeoutTimer.cancel();
        timeoutTimer = null;

        listeners = null;
        nodes = null;
        pendingConfigureRequests = null;
        pendingCreateRequests = null;
        pendingPublishRequests = null;
        serviceName = null;
    }

    /**
     * Fires event about the response of creating a node.
     *
     * @param type the type of the response
     */
    private void fireResponseCreateEvent(PubSubResponseListener.Response type)
    {
        for(PubSubResponseListener l : listeners)
            l.onCreateNodeResponse(type);
    }

    /**
     * Fires event about the response of publishing an item to a node.
     *
     * @param type the type of the response
     */
    private void fireResponsePublishEvent(
            PubSubResponseListener.Response type,
            IQ iq)
    {
        for(PubSubResponseListener l : listeners)
            l.onPublishResponse(type, iq);
    }

    /**
     * Handles PubSub configuration responses.
     *
     * @param response the configuration response.
     */
    private void handleConfigureResponse(IQ response)
    {
        if(pendingConfigureRequests.remove(response.getStanzaId()) != null)
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
    }

    /**
     * Handles responses about PubSub node creation.
     *
     * @param response the response
     */
    private void handleCreateNodeResponse(IQ response)
    {
        String packetID = response.getStanzaId();
        String nodeName = pendingCreateRequests.remove(packetID);

        if (nodeName != null)
        {
            nodes.add(nodeName);
            configureNode(nodeName);
        }
    }

    /**
     * Handles all error responses.
     *
     * @param response the response
     */
    private void handleErrorResponse(IQ response)
    {
        XMPPError err = response.getError();
        String packetID = response.getStanzaId();

        if(err != null)
        {
            XMPPError.Type errType = err.getType();
            XMPPError.Condition errCondition = err.getCondition();

            if((XMPPError.Type.CANCEL.equals(errType)
                        && (XMPPError.Condition.conflict.equals(errCondition)
                            || XMPPError.Condition.forbidden.equals(
                                    errCondition)))
                    /* prosody bug, for backward compatibility */
                    || (XMPPError.Type.AUTH.equals(errType)
                        && XMPPError.Condition.forbidden.equals(errCondition)))
            {
                if (XMPPError.Condition.forbidden.equals(errCondition))
                {
                    logger.warn(
                            "Creating node failed with <forbidden/> error."
                                + " Continuing anyway.");
                }

                String nodeName = pendingCreateRequests.remove(packetID);
                logger.info("PubSub node already exists (packetID=" + packetID
                        + " nodeName=" + nodeName +")");

                if (nodeName != null)
                {
                    // The node exists already (<conflict/>) or we are not
                    // allowed (forbidden/>).
                    nodes.add(nodeName);
                    fireResponseCreateEvent(
                            PubSubResponseListener.Response.SUCCESS);
                    return;
                }
            }
        }

        String nodeName;
        StringBuilder errMsg = new StringBuilder("Error received");

        if((nodeName = pendingCreateRequests.remove(packetID)) != null)
        {
            fireResponseCreateEvent(PubSubResponseListener.Response.FAIL);
            errMsg.append(" when creating the node: ");
        }
        else if((nodeName = pendingConfigureRequests.remove(packetID)) != null)
        {
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
            errMsg.append(" when configuring the node: ");
        }
        else if((nodeName = pendingPublishRequests.remove(packetID)) != null)
        {
            fireResponsePublishEvent(
                    PubSubResponseListener.Response.FAIL,
                    response);
            errMsg.append(" when publishing to the node: ");
        }
        else
        {
            nodeName = null;
        }
        if (nodeName != null)
            errMsg.append(nodeName);
        // Finish the sentence started with "Error received".
        errMsg.append(".");
        if(err != null)
        {
            errMsg.append(" Message: ").append(err.getDescriptiveText())
                    .append(". Condition: ").append(err.getCondition())
                    .append(". For packet with id: ").append(packetID)
                    .append(".");
        }
        logger.error(errMsg);
    }

    /**
     * Handles PubSub publish responses.
     *
     * @param response the response
     */
    private void handlePublishResponse(IQ response)
    {
        if (pendingPublishRequests.remove(response.getStanzaId()) != null)
        {
            fireResponsePublishEvent(
                    PubSubResponseListener.Response.SUCCESS,
                    response);
        }
    }

    /**
     * Publishes items to a given PubSub node.
     *
     * @param nodeName the PubSub node.
     * @param itemId the ID of the item to be published. If <tt>null</tt> the
     *               XMPP server will generate random ID by itself.
     * @param ext the item to be send.
     * @throws IllegalArgumentException if the node does not exist.
     * @throws Exception if fail to send the item.
     */
    public void publish(String nodeName, String itemId, ExtensionElement ext)
        throws Exception
    {
        if(!nodes.contains(nodeName))
            throw new IllegalArgumentException("The node doesn't exists");

        PubSub packet = new PubSub();

        packet.setTo(serviceName);
        packet.setType(IQ.Type.set);

        final String packetID = StanzaIdUtil.newStanzaId();

        packet.setStanzaId(packetID);

        PayloadItem<ExtensionElement> item = new PayloadItem<>(itemId, ext);

        packet.addExtension(new PublishItem<>(nodeName, item));
        pendingPublishRequests.put(packetID, nodeName);
        timeoutTimer.schedule(
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        String nodeName
                            = pendingPublishRequests.remove(packetID);

                        if(nodeName != null)
                        {
                            logger.error(
                                    "Timed out a publish request: " + nodeName);
                        }
                    }
                },
                PACKET_TIMEOUT);
        send(packet);
    }

    /**
     * Removes a <tt>PubSubResponseListener</tt> from the list of listeners.
     *
     * @param l the listener to be removed
     */
    public void removeResponseListener(PubSubResponseListener l)
    {
        listeners.remove(l);
    }

    /**
     * Sends <tt>IQ</tt> packet.
     *
     * @param iq the packet.
     * @throws Exception if sending fails.
     */
    private void send(IQ iq)
        throws Exception
    {
        BundleContext bundleContext
            = StatsManagerBundleActivator.getBundleContext();

        if (bundleContext != null)
        {
            Collection<ComponentImpl> components
                = ComponentImpl.getComponents(bundleContext);

            for (ComponentImpl component : components)
                component.send(iq);
        }
    }
}
