/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.pubsub;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.pubsub.PubSubResponseListener.Response;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.packet.*;
import org.osgi.framework.*;

/**
 * A class that implements some parts of PubSub (XEP-0060).
 *
 * @author Hristo Terezov
 */
public class PubSubPublisher
{
    /**
     * Maps a service name (e.g. "pubsub.example.com") to the PubSubPublisher
     * instance responsible for it.
     */
    private static Map<String, PubSubPublisher> instances
        = new HashMap<String, PubSubPublisher>();

    /**
     * Logger instance.
     */
    private static Logger logger = Logger.getLogger(PubSubPublisher.class);

    /**
     * The default timeout of the packets in milliseconds.
     */
    private static int PACKET_TIMEOUT = 500;

    /**
     * Creates and returns <tt>PubSubPublisher</tt> instance for the given service
     *
     * @param serviceName the name of the service
     * @return <tt>PubSubPublisher</tt> instance.
     */
    public static PubSubPublisher getPubsubManager(String serviceName)
    {
        PubSubPublisher publisher;

        if (instances.containsKey(serviceName))
        {
            publisher = instances.get(serviceName);
        }
        else
        {
            publisher = new PubSubPublisher(serviceName);
            instances.put(serviceName, publisher);
        }
        return publisher;
    }

    /**
     * Handles received response IQ packet
     * @param response the IQ packet.
     */
    public static void handleIQResponse(IQ response)
    {
        IQ.Type type = response.getType();

        if (IQ.Type.ERROR.equals(type))
        {
            PubSubPublisher publisher = instances.get(response.getFrom());

            if (publisher != null)
            {
                publisher.handleErrorResponse(
                        response.getError(),
                        response.getPacketID());
            }
        }
        else if (IQ.Type.RESULT.equals(type))
        {
            PubSubPublisher publisher = instances.get(response.getFrom());

            if (publisher != null)
            {
                publisher.handleCreateNodeResponse(response);
                publisher.handleConfigurationResponse(response);
                publisher.handlePublishResponse(response);
            }
        }
    }

    /**
     * Releases the resources for the <tt>PubSubPublisher</tt> and removes it from
     * the list of available instances.
     * @param publisher the <tt>PubSubPublisher</tt> that will be released.
     */
    public static void releasePubsubManager(PubSubPublisher publisher)
    {
        instances.values().remove(publisher);
        publisher.dispose();
    }

    /**
     * Listeners for response events.
     */
    private List<PubSubResponseListener> listeners
        = new LinkedList<PubSubResponseListener>();

    /**
     * List of the accessible PubSub nodes.
     */
    private List<String> nodes = new LinkedList<String>();

    /**
     * Map with the requests for configuring a node.
     */
    private Map<String, String> pendingConfigureRequests
        =  new HashMap<String, String>();

    /**
     * Map with the requests for node creation.
     */
    private Map<String, String> pendingCreateRequests
        =  new HashMap<String, String>();

    /**
     * Map with the publish requests.
     */
    private Map<String, String> pendingPublishRequests
        =  new HashMap<String, String>();

    /**
     * The name of the PubSub service.
     */
    private String serviceName;

    /**
     * Timer for timeout of the requests that we are sending.
     */
    private Timer timeoutTimer = new Timer();

    /**
     * Initializes a new <tt>PubSubPublisher</tt> instance for a specific service.
     *
     * @param serviceName the name of the service.
     */
    private PubSubPublisher(String serviceName)
    {
        this.serviceName = serviceName;
    }

    /**
     * Adds <tt>PubSubResponseListener</tt> to the list of listeners.
     * @param l the listener to be added.
     */
    public void addResponseListener(PubSubResponseListener l)
    {
        if(!listeners.contains(l))
        {
            listeners.add(l);
        }
    }

    /**
     * Configures PubSub node
     * @param nodeName the name of the node
     */
    private void configureNode(String nodeName)
    {
        ConfigureForm cfg = new ConfigureForm(FormType.submit);
        PubSub pubsub = new PubSub();
        pubsub.setTo(serviceName);
        pubsub.setType(Type.SET);
        cfg.setAccessModel(AccessModel.open);
        cfg.setPersistentItems(false);
        cfg.setPublishModel(PublishModel.open);
        final String packetID = IQ.nextID();
        pubsub.setPacketID(packetID);
        pubsub.addExtension(new FormNode(FormNodeType.CONFIGURE_OWNER, cfg));
        try
        {
            send(pubsub);
        }
        catch (Exception e)
        {
            logger.error("Error sending configuration form.");
            fireResponseCreateEvent(Response.SUCCESS);
            return;
        }
        pendingConfigureRequests.put(packetID, nodeName);
        timeoutTimer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                if(pendingConfigureRequests.containsKey(packetID))
                {
                    pendingConfigureRequests.remove(packetID);
                    logger.error("Configuration of the node failed.");
                    fireResponseCreateEvent(Response.SUCCESS);
                }
            }
        }, PACKET_TIMEOUT);
    }

    /**
     * Creates a PubSub node.
     * @param nodeName the name of the node.
     * @throws Exception if sending the request fails.
     */
    public void createNode(String nodeName)
        throws Exception
    {
        PubSub request = new PubSub();
        request.setTo(serviceName);
        request.setType(Type.SET);
        final String packetID = Packet.nextID();
        request.setPacketID(packetID);

        pendingCreateRequests.put(packetID, nodeName);
        timeoutTimer.schedule(
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        pendingCreateRequests.remove(packetID);
                    }
                },
                PACKET_TIMEOUT);

        request.addExtension(
                new NodeExtension(PubSubElementType.CREATE, nodeName));

        send(request);
    }

    /**
     * Releases the resources for the <tt>PubSubPublisher</tt>.
     */
    private void dispose()
    {
        serviceName = null;
        nodes.clear();
        nodes = null;
        pendingConfigureRequests.clear();
        pendingConfigureRequests = null;
        pendingCreateRequests.clear();
        pendingCreateRequests = null;
        pendingPublishRequests.clear();
        pendingPublishRequests = null;
        timeoutTimer.cancel();
        timeoutTimer = null;
        listeners.clear();
        listeners = null;
    }

    /**
     * Fires event about the response of creating a node.
     * @param type the type of the response
     */
    private void fireResponseCreateEvent(Response type)
    {
        for(PubSubResponseListener l : listeners)
        {
            l.onCreateNodeResponse(type);
        }
    }

    /**
     * Fires event about the response of publishing an item to a node.
     * @param type the type of the response
     */
    private void fireResponsePublishEvent(Response type)
    {
        for(PubSubResponseListener l : listeners)
        {
            l.onPublishResponse(type);
        }
    }

    /**
     * Handles PubSub configuration responses.
     * @param response the configuration response.
     */
    private void handleConfigurationResponse(IQ response)
    {
        if(pendingConfigureRequests.remove(response.getPacketID()) != null)
            fireResponseCreateEvent(Response.SUCCESS);
    }

    /**
     * Handles responses about PubSub node creation.
     * @param response the response
     */
    private void handleCreateNodeResponse(IQ response)
    {
        String packetID = response.getPacketID();
        String nodeName = pendingCreateRequests.get(packetID);
        if (nodeName == null)
            return;
        nodes.add(nodeName);
        pendingCreateRequests.remove(packetID);
        configureNode(nodeName);
    }

    /**
     * Handles all error responses.
     * @param error the error object.
     * @param packetID the id of the received packet.
     */
    private void handleErrorResponse(XMPPError error, String packetID)
    {
        if(error != null
            && XMPPError.Type.CANCEL.equals(error.getType())
            && XMPPError.Condition.conflict.toString()
                .equals(error.getCondition()))
        {
            String nodeName = pendingCreateRequests.get(packetID);
            if(nodeName != null)
            {
                //the node already exists
                nodes.add(nodeName);
                pendingCreateRequests.remove(packetID);
                fireResponseCreateEvent(Response.SUCCESS);
                return;
            }
        }
        else if(error != null
            && XMPPError.Type.AUTH.equals(error.getType())
            && XMPPError.Condition.forbidden.toString()
                .equals(error.getCondition()))
        {
            String nodeName = pendingCreateRequests.get(packetID);
            if(nodeName != null)
            {
                //the node already exists but isowned by 
                // someone else
                nodes.add(nodeName);
                pendingCreateRequests.remove(packetID);
                fireResponseCreateEvent(Response.SUCCESS);
                return;
            }
        }

        String errorMessage = "Error received when ";
        if(pendingCreateRequests.remove(packetID) != null)
        {
            fireResponseCreateEvent(Response.FAIL);
            errorMessage += " creating the node.";
        }
        else if(pendingConfigureRequests.remove(packetID) != null)
        {
            errorMessage += " configuring the node.";
            fireResponseCreateEvent(Response.SUCCESS);
        }
        else if(pendingPublishRequests.remove(packetID) != null)
        {
            errorMessage +=  "pubshing to the node.";
            fireResponsePublishEvent(Response.FAIL);
        }

        if(error != null)
            errorMessage += " Message: "
                + error.getMessage() + ". Condition: " + error.getCondition()
                + ". For packet with id: " + packetID + ". ";

        logger.error(errorMessage);
    }

    /**
     * Handles PubSub publish responses.
     * @param response the response
     */
    private void handlePublishResponse(Packet response)
    {
        if (pendingPublishRequests.containsKey(response.getPacketID()))
        {
            pendingPublishRequests.remove(response.getPacketID());
            fireResponsePublishEvent(Response.SUCCESS);
        }
    }

    /**
     * Publishes items to a given PubSub node.
     * @param nodeName the PubSub node.
     * @param ext the item to be send.
     * @throws Exception if fail to send the item or the node is not created.
     */
    public void publish(String nodeName, PacketExtension ext)
        throws Exception
    {
        if(!nodes.contains(nodeName))
            throw new IllegalArgumentException("The node doesn't exists");
        PubSub packet = new PubSub();
        packet.setTo(serviceName);
        packet.setType(Type.SET);
        final String packetID = IQ.nextID();
        packet.setPacketID(packetID);
        PayloadItem<PacketExtension> item
            = new PayloadItem<PacketExtension>(ext);

        packet.addExtension(
            new PublishItem<PayloadItem<PacketExtension>>(nodeName, item));
        pendingPublishRequests.put(packetID, nodeName);
        timeoutTimer.schedule(
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        if(pendingPublishRequests.containsKey(packetID))
                        {
                            pendingPublishRequests.remove(packetID);
                            logger.error("Publish request timeout.");
                        }
                    }
                },
                PACKET_TIMEOUT);
        send(packet);
    }

    /**
     * Removes <tt>PubSubResponseListener</tt> from the list of listeners.
     * @param l the listener to be removed
     */
    public void removeResponseListener(PubSubResponseListener l)
    {
        listeners.remove(l);
    }

    /**
     * Sends <tt>IQ</tt> packet.
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
            {
                component.send(iq);
            }
        }
    }
}
