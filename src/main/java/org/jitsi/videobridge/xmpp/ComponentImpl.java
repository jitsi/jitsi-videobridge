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
package org.jitsi.videobridge.xmpp;

import java.util.*;

import org.jitsi.meet.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.xmpp.component.*;
import org.jitsi.xmpp.util.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;
import org.xmpp.component.*;
import org.xmpp.packet.*;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Packet;

/**
 * Implements <tt>org.xmpp.component.Component</tt> to provide Jitsi Videobridge
 * as a Jabber component.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ComponentImpl
    extends ComponentBase
    implements BundleActivator
{
    /**
     * The {@link Logger} used by the {@link ComponentImpl} class and its
     * instances for logging output.
     */
    private static final org.jitsi.utils.logging.Logger logger
            =  org.jitsi.utils.logging.Logger.getLogger(ComponentImpl.class);

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
        return ServiceUtils2.getServices(bundleContext, ComponentImpl.class);
    }

    /**
     * The {@link XmppCommon} instance which implements handling of Smack IQs
     * for this {@link ComponentImpl}.
     */
    private final XmppCommon common = new XmppCommon();

    /**
     * Initializes a new <tt>ComponentImpl</tt> instance.
     * @param host the hostname or IP address to which this component will be
     *             connected.
     * @param port the port of XMPP server to which this component will connect.
     * @param domain the name of main XMPP domain on which this component will
     *               be served.
     * @param subDomain the name of subdomain on which this component will be
     *                  available.
     * @param secret the password used by the component to authenticate with
     *               XMPP server.
     */
    public ComponentImpl(
        String host,
        int port,
        String domain,
        String subDomain,
        String secret)
    {
        super(host, port, domain, subDomain, secret);
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
        return XmppCommon.FEATURES.clone();
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
     * Returns the {@link Videobridge} instance that is managing conferences
     * for this component. Returns <tt>null</tt> if no instance is running.
     *
     * @return the videobridge instance, <tt>null</tt> when none is running.
     */
    public Videobridge getVideobridge()
    {
        return common.getVideobridge();
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>get</tt> or
     * <tt>set</tt> which represents a request. Converts the specified
     * <tt>org.xmpp.packet.IQ</tt> to an
     * <tt>org.jivesoftware.smack.packet.IQ</tt> stanza, handles the Smack
     * stanza via a call to {@link XmppCommon#handleIQ},
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
            org.jivesoftware.smack.packet.IQ smackIQ = IQUtils.convert(iq);
            // Failed to convert to Smack IQ ?
            if (smackIQ == null)
            {
                if (iq.isRequest())
                {
                    IQ error = new IQ(IQ.Type.error, iq.getID());
                    error.setFrom(iq.getTo());
                    error.setTo(iq.getFrom());
                    error.setError(
                            new PacketError(
                                    PacketError.Condition.bad_request,
                                    PacketError.Type.modify,
                                    "Failed to parse incoming stanza"));
                    return error;
                }
                else
                {
                    logger.error("Failed to convert stanza: " + iq.toXML());
                }
            }

            org.jivesoftware.smack.packet.IQ resultSmackIQ
                = common.handleIQ(smackIQ);
            IQ resultIQ;

            if (resultSmackIQ == null)
            {
                resultIQ = null;
            }
            else
            {
                resultIQ = IQUtils.convert(resultSmackIQ);
            }

            return resultIQ;
        }
        catch (Exception e)
        {
            logger.error(
                "Failed to handle IQ with id="
                    + (iq == null ? "null" : iq.getID()),
                e);

            throw e;
        }
    }

    @Override
    protected void handleIQErrorImpl(IQ iq)
    {
        super.handleIQErrorImpl(iq);

        try
        {
            handleIQ(iq);
        }
        catch (Exception e)
        {
            logger.error(
                "An error occurred while trying to handle an 'error' IQ.",
                e);
        }
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
    protected IQ handleIQGetImpl(IQ iq)
        throws Exception
    {
        IQ resultIQ = handleIQ(iq);

        return (resultIQ == null) ? super.handleIQGetImpl(iq) : resultIQ;
    }


    @Override
    protected void handleIQResultImpl(IQ iq)
    {
        super.handleIQResultImpl(iq);

        try
        {
            handleIQ(iq);
        }
        catch (Exception e)
        {
            logger.error(
                "An error occurred while trying to handle a 'result' IQ.",
                e);
        }
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
    protected IQ handleIQSetImpl(IQ iq)
        throws Exception
    {
        IQ resultIQ = handleIQ(iq);

        return (resultIQ == null) ? super.handleIQSetImpl(iq) : resultIQ;
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
            Jid from = iq.getFrom();

            if ((from == null) || (from.length() == 0))
            {
                JID fromJID = getJID();

                if (fromJID != null)
                    iq.setFrom(JidCreate.from(fromJID.toString()));
            }

            Packet packet = IQUtils.convert(iq);

            send(packet);

            if (logger.isDebugEnabled())
            {
                logger.debug("SENT: " + packet.toXML());
            }
        }
        catch (Exception e)
        {
            logger.error(
                "Failed to send an IQ with id="
                    + (iq == null ? "null" : iq.getStanzaId()),
                e);
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
        common.start(bundleContext);

        // Register this instance as an OSGi service.
        Collection<ComponentImpl> components = getComponents(bundleContext);

        if (!components.contains(this))
        {
            bundleContext.registerService(ComponentImpl.class, this, null);
        }

        // Schedule ping task
        // note: the task if stopped automatically on component shutdown
        ConfigurationService config
            = ServiceUtils2.getService(
                    bundleContext, ConfigurationService.class);

        loadConfig(config, "org.jitsi.videobridge");

        if (!isPingTaskStarted())
        {
            startPingTask();
        }
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
                    {
                        bundleContext.ungetService(serviceReference);
                    }
                }
            }
        }
        finally
        {
            common.stop(bundleContext);
        }
    }
}
