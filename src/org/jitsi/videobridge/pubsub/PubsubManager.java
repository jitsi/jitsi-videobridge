/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.pubsub;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.pubsub.PubsubResponseListener.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.IQ.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.packet.*;

/**
 * A class that implements some parts of PubSub (XEP-0060).
 *
 * @author Hristo Terezov
 */
public class PubsubManager
{
    /**
     * The name of the PubSub service.
     */
    private String serviceName;

    /**
     * Handlers for the response packets that are received from the PubSub
     * service.
     */
    private static List<IQResponseHandler> handlers
        = new ArrayList<IQResponseHandler>();

    /**
     * A map with the PubSub instances and their service names.
     */
    private static Map<String, PubsubManager> instances
        = new HashMap<String, PubsubManager>();

    /**
     * The videobridge instance.
     */
    private Videobridge videobridge;

    /**
     * List of the accessible PubSub nodes.
     */
    private List<String> nodes = new LinkedList<String>();

    /**
     * Map with the requests for node creation.
     */
    private Map<String, String> pendingCreateRequests
        =  new HashMap<String, String>();

    /**
     * Map with the requests for configuring a node.
     */
    private Map<String, String> pendingConfigureRequests
        =  new HashMap<String, String>();

    /**
     * Map with the publish requests.
     */
    private Map<String, String> pendingPublishRequests
        =  new HashMap<String, String>();

    /**
     * Timer for timeout of the requests that we are sending.
     */
    private Timer timeoutTimer =  new Timer();

    /**
     * The default timeout of the packets in milliseconds.
     */
    private static int PACKET_TIMEOUT = 500;

    /**
     * Logger instance.
     */
    private static Logger logger = Logger.getLogger(PubsubManager.class);

    /**
     * Listeners for response events.
     */
    private List<PubsubResponseListener> listeners
        = new LinkedList<PubsubResponseListener>();

    static
    {
        handlers.add(new ErrorIQResponseHandler());
        handlers.add(new ResultIQResponseHandler());
        handlers.add(new PubSubIQResponseHandler());

    }

    /**
     * Creates and returns <tt>PubsubManager</tt> instance for the given service
     * @param serviceName the name of the service
     * @return <tt>PubsubManager</tt> instance.
     */
    public static PubsubManager getPubsubManager(String serviceName,
        Videobridge videobridge)
    {
        if(!instances.containsKey(serviceName))
        {
            instances.put(serviceName, new PubsubManager(serviceName,
                videobridge));
        }
        return instances.get(serviceName);
    }

    /**
     * Returns <tt>PubsubManager</tt> instance for the given service
     * @param serviceName the name of the service
     * @return <tt>PubsubManager</tt> instance or <tt>null</tt> if the instance
     * haven't been created yet.
     */
    public static PubsubManager getPubsubManager(String serviceName)
    {
        return instances.get(serviceName);
    }

    /**
     * Releases the resources for the <tt>PubsubManager</tt> and removes it from
     * the list of available instances.
     * @param mgr the <tt>PubsubManager</tt> that will be released.
     */
    public static void releasePubsubManager(PubsubManager mgr)
    {
        instances.remove(mgr);
        mgr.dispose();
    }

    /**
     * Creates new <tt>PubsubManager</tt> for the given service.
     * @param serviceName the name of the service.
     * @param videobridge the <tt>Videobridge</tt> instance.
     */
    private PubsubManager(String serviceName, Videobridge videobridge)
    {
        this.serviceName = serviceName;
        this.videobridge = videobridge;
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
        timeoutTimer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                pendingCreateRequests.remove(packetID);
            }
        }, PACKET_TIMEOUT);

        request.addExtension(
            new NodeExtension(PubSubElementType.CREATE, nodeName));

        send(request);


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
        timeoutTimer.schedule(new TimerTask()
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
        }, PACKET_TIMEOUT);
        send(packet);
    }

    /**
     * Handles received response IQ packet
     * @param response the IQ packet.
     */
    public static void handleIQResponse(IQ response)
    {
        for(IQResponseHandler handler : handlers)
        {
            if(!handler.canProcess(response))
                continue;
            handler.process(response);
        }
    }

    /**
     * Handles responses about PubSub node creation.
     * @param response the response
     */
    private void handleCreateNodeResponse(IQ response)
    {
        String nodeName = pendingCreateRequests.get(response.getPacketID());
        if(nodeName == null)
            return;
        nodes.add(nodeName);
        pendingCreateRequests.remove(response.getPacketID());
        configureNode(nodeName);
    }

    /**
     * Handles all error responses
     * @param error the error object.
     * @param packetID the id of the received packet.
     */
    private void handleErrorResponses(XMPPError error, String packetID)
    {
        if(error != null
            && error.getType().equals(XMPPError.Type.CANCEL)
            && error.getCondition().equals(
                XMPPError.Condition.conflict.toString()))
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
     * Handles PubSub configuration responses.
     * @param response the configuration response.
     */
    private void handleConfigurationResponse(IQ response)
    {
        if(pendingConfigureRequests.remove(response) != null)
            fireResponseCreateEvent(Response.SUCCESS);

    }

    /**
     * Handles PubSub publish responses.
     * @param response the response
     */
    private void handlePublishResponse(Packet response)
    {
        if(pendingPublishRequests.containsKey(response.getPacketID()))
        {
            pendingPublishRequests.remove(response.getPacketID());
            fireResponsePublishEvent(Response.SUCCESS);
        }
    }

    /**
     * Adds <tt>PubsubResponseListener</tt> to the list of listeners.
     * @param l the listener to be added.
     */
    public void addResponseListener(PubsubResponseListener l)
    {
        if(!listeners.contains(l))
        {
            listeners.add(l);
        }
    }

    /**
     * Removes <tt>PubsubResponseListener</tt> from the list of listeners.
     * @param l the listener to be removed
     */
    public void removeResponseListener(PubsubResponseListener l)
    {
        listeners.remove(l);
    }

    /**
     * Fires event about the response of creating a node.
     * @param type the type of the response
     */
    private void fireResponseCreateEvent(Response type)
    {
        for(PubsubResponseListener l : listeners)
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
        for(PubsubResponseListener l : listeners)
        {
            l.onPublishResponse(type);
        }
    }

    /**
     * Sends <tt>IQ</tt> packet.
     * @param iq the packet.
     * @throws Exception if sending fails.
     */
    private void send(IQ iq)
        throws Exception
    {
        Collection<ComponentImpl> components = videobridge.getComponents();
        for(ComponentImpl c : components)
        {
            c.send(iq);
        }
    }

    /**
     * Releases the resources for the <tt>PubsubManager</tt>.
     */
    private void dispose()
    {
        videobridge = null;
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
     * Interface for response handlers.
     */
    private static interface IQResponseHandler
    {
        /**
         * Checks whether the handler can process this packet or not.
         * @param response the response packet
         * @return <tt>true</tt> if the handler can process the packet and
         * <tt>false</tt> otherwise.
         */
        public boolean canProcess(IQ response);

        /**
         * Processes the response packet
         * @param response the response packet.
         */
        public void process(IQ response);
    }

    /**
     * Response handler for IQ error packets.
     */
    private static class ErrorIQResponseHandler implements IQResponseHandler
    {

        @Override
        public boolean canProcess(IQ response)
        {
            return response.getType() == IQ.Type.ERROR;
        }

        @Override
        public void process(IQ response)
        {
            PubsubManager mgr = instances.get(response.getFrom());
            if(mgr == null)
                return;

            mgr.handleErrorResponses(response.getError(),
                response.getPacketID());
        }

    }

    /**
     * Response handler for IQ packets of type "result".
     */
    private static class ResultIQResponseHandler implements IQResponseHandler
    {

        @Override
        public boolean canProcess(IQ response)
        {
            return response.getType() == IQ.Type.RESULT;
        }

        @Override
        public void process(IQ response)
        {
            PubsubManager mgr = instances.get(response.getFrom());
            if(mgr == null)
                return;

            mgr.handleCreateNodeResponse(response);
            mgr.handleConfigurationResponse(response);
        }

    }

    /**
     * Response handler for PubSub packets.
     */
    private static class PubSubIQResponseHandler implements IQResponseHandler
    {

        @Override
        public boolean canProcess(IQ response)
        {
            return response instanceof PubSub;
        }

        @Override
        public void process(IQ response)
        {
            PubsubManager mgr = instances.get(response.getFrom());
            if(mgr == null)
                return;

            mgr.handlePublishResponse(response);
        }

    }

}
