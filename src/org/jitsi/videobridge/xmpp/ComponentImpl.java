/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.xmpp;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.pubsub.*;
import org.jivesoftware.smackx.pubsub.packet.*;
import org.osgi.framework.*;
import org.xmpp.component.*;
import org.xmpp.packet.*;

/**
 * Implements <tt>org.xmpp.component.Component</tt> to provide Jitsi Videobridge
 * as an internal Jabber component.
 *
 * @author Lyubomir Marinov
 */
public class ComponentImpl
    extends AbstractComponent
    implements BundleActivator
{
    /**
     * The (default) description of <tt>ComponentImpl</tt> instances.
     */
    private static final String DESCRIPTION
        = "Jitsi Videobridge Jabber Component";

    /**
     * The (default) name of <tt>ComponentImpl</tt> instances.
     */
    private static final String NAME = "JitsiVideobridge";

    /**
     * The (default) sub-domain of the address with which <tt>ComponentImpl</tt>
     * instances are to be added to their respective <tt>ComponentManager</tt>s.
     */
    public static final String SUBDOMAIN = "jitsi-videobridge";

    /**
     * Gets the <tt>ComponentImpl</tt> instances registered as OSGi services in
     * a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> from which the registered
     * <tt>ComponentImpl</tt> instances registered as OSGi services are to be
     * returned
     * @return the <tt>ComponentImpl</tt> instances registered as OSGi services
     * in the specified <tt>bundleContext</tt>
     */
    public static Collection<ComponentImpl> getComponents(
            BundleContext bundleContext)
    {
        List<ComponentImpl> components = new LinkedList<ComponentImpl>();

        if (bundleContext != null)
        {
            Collection<ServiceReference<ComponentImpl>> serviceReferences
                = null;

            try
            {
                serviceReferences
                    = bundleContext.getServiceReferences(
                            ComponentImpl.class,
                            null);
            }
            catch (IllegalStateException e)
            {
            }
            catch (InvalidSyntaxException e)
            {
            }
            if (serviceReferences != null)
            {
                for (ServiceReference<ComponentImpl> serviceReference
                        : serviceReferences)
                {
                    ComponentImpl component = null;

                    try
                    {
                        component = bundleContext.getService(serviceReference);
                    }
                    catch (IllegalArgumentException e)
                    {
                    }
                    catch (IllegalStateException e)
                    {
                        // The bundleContext is no longer valid.
                        break;
                    }
                    catch (SecurityException e)
                    {
                    }
                    if ((component != null) && !components.contains(component))
                        components.add(component);
                }
            }
        }
        return components;
    }

    /**
     * The <tt>BundleContext</tt> in which this instance has been started as an
     * OSGi bundle.
     */
    private BundleContext bundleContext;

    /**
     * Initializes a new <tt>ComponentImpl</tt> instance.
     */
    public ComponentImpl()
    {
    }

    /**
     * {@inheritDoc}
     *
     * Gets the namespaces of features that this <tt>Component</tt>
     * offers/supports i.e. {@link ColibriConferenceIQ#NAMESPACE}.
     */
    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return
            new String[]
                    {
                        ColibriConferenceIQ.NAMESPACE,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_DTLS_SRTP,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_ICE_UDP_1,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_RAW_UDP_0
                    };
    }

    /**
     * {@inheritDoc}
     *
     * Gets the type of the Service Discovery Identity of this
     * <tt>Component</tt> i.e. &quot;conference&quot;.
     */
    @Override
    protected String discoInfoIdentityCategoryType()
    {
        return "conference";
    }

    /**
     * Gets the OSGi <tt>BundleContext</tt> in which this Jabber component is
     * executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which this Jabber component is
     * executing
     */
    public BundleContext getBundleContext()
    {
        return bundleContext;
    }

    /**
     * Gets the description of this <tt>Component</tt>.
     *
     * @return the description of this <tt>Component</tt>
     * @see Component#getDescription()
     */
    @Override
    public String getDescription()
    {
        return DESCRIPTION;
    }

    /**
     * Gets the name of this <tt>Component</tt>.
     *
     * @return the name of this <tt>Component</tt>
     * @see Component#getName()
     */
    @Override
    public String getName()
    {
        return NAME;
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    private org.jivesoftware.smack.packet.IQ handleColibriConferenceIQ(
            ColibriConferenceIQ conferenceIQ)
        throws Exception
    {
        BundleContext bundleContext = getBundleContext();
        org.jivesoftware.smack.packet.IQ iq = null;

        if (bundleContext != null)
        {
            Videobridge videobridge
                = ServiceUtils.getService(bundleContext, Videobridge.class);

            if (videobridge != null)
                iq = videobridge.handleColibriConferenceIQ(conferenceIQ);
        }
        return iq;
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>get</tt> or
     * <tt>set</tt> which represents a request. Converts the specified
     * <tt>org.xmpp.packet.IQ</tt> to an
     * <tt>org.jivesoftware.smack.packet.IQ</tt> stanza, handles the Smack
     * stanza via a call to {@link #handleIQ(org.jivesoftware.smack.packet.IQ)},
     * converts the result Smack stanza to an <tt>org.xmpp.packet.iQ</tt> which
     * is returned as the response to the request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>get</tt> or
     * <tt>set</tt> which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    private IQ handleIQ(IQ iq)
        throws Exception
    {
        try
        {
            logd("RECV: " + iq.toXML());

            org.jivesoftware.smack.packet.IQ smackIQ = IQUtils.convert(iq);
            org.jivesoftware.smack.packet.IQ resultSmackIQ = handleIQ(smackIQ);
            IQ resultIQ;

            if (resultSmackIQ == null)
                resultIQ = null;
            else
            {
                resultIQ = IQUtils.convert(resultSmackIQ);

                logd("SENT: " + resultIQ.toXML());
            }

            return resultIQ;
        }
        catch (Exception e)
        {
            loge(e);
            throw e;
        }
    }

    /**
     * Handles an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza of type
     * <tt>get</tt> or <tt>set</tt> which represents a request.
     *
     * @param iq the <tt>org.jivesoftware.smack.packet.IQ</tt> stanza of type
     * <tt>get</tt> or <tt>set</tt> which represents the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    private org.jivesoftware.smack.packet.IQ handleIQ(
            org.jivesoftware.smack.packet.IQ iq)
        throws Exception
    {
        org.jivesoftware.smack.packet.IQ resultIQ;

        if (iq instanceof ColibriConferenceIQ)
        {
            resultIQ = handleColibriConferenceIQ((ColibriConferenceIQ) iq);
            if (resultIQ != null)
            {
                resultIQ.setFrom(iq.getTo());
                resultIQ.setPacketID(iq.getPacketID());
                resultIQ.setTo(iq.getFrom());
            }
        }
        else if(iq.getType().equals(
            org.jivesoftware.smack.packet.IQ.Type.RESULT)
            || iq.getType().equals(
                org.jivesoftware.smack.packet.IQ.Type.ERROR)
            || iq instanceof PubSub)
        {
            PubsubManager.handleIQResponse(iq);
            resultIQ = null;
        }
        else
            resultIQ = null;
        return resultIQ;
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>get</tt> which
     * represents a request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>get</tt>
     * which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     * @see AbstractComponent#handleIQGet(IQ)
     */
    @Override
    protected IQ handleIQGet(IQ iq)
        throws Exception
    {
        IQ resultIQ = handleIQ(iq);

        return (resultIQ == null) ? super.handleIQGet(iq) : resultIQ;
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt> which
     * represents a request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt>
     * which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     * @see AbstractComponent#handleIQSet(IQ)
     */
    @Override
    protected IQ handleIQSet(IQ iq)
        throws Exception
    {
        IQ resultIQ = handleIQ(iq);

        return (resultIQ == null) ? super.handleIQSet(iq) : resultIQ;
    }

    @Override
    protected void handleIQError(IQ iq)
    {
        super.handleIQError(iq);
        try
        {
            handleIQ(iq);
        }
        catch (Exception e)
        {
            logd("An error occured when trying to handle IQ error packet");
            loge(e);
        }
    }

    @Override
    protected void handleIQResult(IQ iq)
    {
        super.handleIQResult(iq);
        try
        {
            handleIQ(iq);
        }
        catch (Exception e)
        {
            logd("An error occured when trying to handle IQ result packet");
            loge(e);
        }
    }
    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level
     */
    private static void logd(String s)
    {
        System.err.println(s);
    }

    /**
     * Logs a specific <tt>Throwable</tt> at error level.
     *
     * @param t the <tt>Throwable</tt> to log at error level
     */
    private static void loge(Throwable t)
    {
        t.printStackTrace(System.err);
    }

    /**
     * Called as part of the execution of {@link AbstractComponent#shutdown()}
     * to enable this <tt>Component</tt> to finish cleaning resources up after
     * it gets completely shutdown.
     *
     * @see AbstractComponent#postComponentShutdown()
     */
    @Override
    public void postComponentShutdown()
    {
        super.postComponentShutdown();

        OSGi.stop(this);
    }

    /**
     * Called as part of the execution of {@link AbstractComponent#start()} to
     * enable this <tt>Component</tt> to finish initializing resources after it
     * gets completely started.
     *
     * @see AbstractComponent#postComponentStart()
     */
    @Override
    public void postComponentStart()
    {
        super.postComponentStart();

        OSGi.start(this);
    }

    /**
     * Implements a helper to send <tt>org.jivesoftware.smack.packet.IQ</tt>s.
     *
     * @param iq the <tt>org.jivesoftware.smack.packet.IQ</tt> to send
     * @throws Exception if an error occurs during the conversion of the
     * specified <tt>iq</tt> to an <tt>org.xmpp.packet.IQ</tt> instance or while
     * sending the specified <tt>iq</tt>
     */
    public void send(org.jivesoftware.smack.packet.IQ iq)
        throws Exception
    {
        try
        {
            /*
             * The javadoc on ComponentManager.sendPacket(Component,Packet)
             * which is invoked by AbstractComponent.send(Packet) says that the
             * value of the from property of the Packet must not be null;
             * otherwise, an IllegalArgumentException will be thrown.
             */
            String from = iq.getFrom();

            if ((from == null) || (from.length() == 0))
            {
                JID fromJID = getJID();

                if (fromJID != null)
                    iq.setFrom(fromJID.toString());
            }

            Packet packet = IQUtils.convert(iq);

            send(packet);

            logd("SENT: " + packet.toXML());
        }
        catch (Exception e)
        {
            loge(e);
            throw e;
        }
    }

    /**
     * Starts this Jabber component implementation and the Jitsi Videobridge it
     * provides in a specific OSGi <tt>BundleContext</tt>.
     *
     * @param bundleContext the OSGi <tt>BundleContext</tt> in which this Jabber
     * component implementation and the Jitsi Videobridge it provides are to be
     * started
     * @throws Exception if anything irreversible goes wrong while starting this
     * Jabber component implementation and the Jitsi Videobridge it provides in
     * the specified <tt>bundleContext</tt>
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        // Register this instance as an OSGi service.
        Collection<ComponentImpl> components = getComponents(bundleContext);

        if (!components.contains(this))
            bundleContext.registerService(ComponentImpl.class, this, null);
    }

    /**
     * Stops this Jabber component implementation and the Jitsi Videobridge it
     * provides in a specific OSGi <tt>BundleContext</tt>.
     *
     * @param bundleContext the OSGi <tt>BundleContext</tt> in which this Jabber
     * component implementation and the Jitsi Videobridge it provides are to be
     * stopped
     * @throws Exception if anything irreversible goes wrong while stopping this
     * Jabber component implementation and the Jitsi Videobridge it provides in
     * the specified <tt>bundleContext</tt>
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        try
        {
            // Unregister this instance as an OSGi service.
            Collection<ServiceReference<ComponentImpl>> serviceReferences
                = bundleContext.getServiceReferences(ComponentImpl.class, null);

            if (serviceReferences != null)
            {
                for (ServiceReference<ComponentImpl> serviceReference
                        : serviceReferences)
                {
                    Object service = bundleContext.getService(serviceReference);

                    if (service == this)
                        bundleContext.ungetService(serviceReference);
                }
            }
        }
        finally
        {
            this.bundleContext = null;
        }
    }
}
