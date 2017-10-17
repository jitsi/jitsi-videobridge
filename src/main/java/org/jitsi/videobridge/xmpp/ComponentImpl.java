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
package org.jitsi.videobridge.xmpp;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.util.*;

import org.jitsi.meet.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.component.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.iqversion.packet.Version;
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
 */
public class ComponentImpl
    extends ComponentBase
    implements BundleActivator
{
    private static final org.jitsi.util.Logger logger
            =  org.jitsi.util.Logger.getLogger(ComponentImpl.class);

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
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level
     */
    private static void logd(String s)
    {
        if ( logger.isDebugEnabled() ) {
            logger.debug(s);
        }
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
     * The <tt>BundleContext</tt> in which this instance has been started as an
     * OSGi bundle.
     */
    private BundleContext bundleContext;

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
    public ComponentImpl(String          host,
                         int             port,
                         String        domain,
                         String     subDomain,
                         String        secret)
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
        return
            new String[]
                    {
                        ColibriConferenceIQ.NAMESPACE,
                        HealthCheckIQ.NAMESPACE,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_DTLS_SRTP,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_ICE_UDP_1,
                        ProtocolProviderServiceJabberImpl
                            .URN_XMPP_JINGLE_RAW_UDP_0,
                        Version.NAMESPACE
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
     * Returns the {@link Videobridge} instance that is managing conferences
     * for this component. Returns <tt>null</tt> if no instance is running.
     *
     * @return the videobridge instance, <tt>null</tt> when none is running.
     */
    public Videobridge getVideobridge()
    {
        BundleContext bundleContext = getBundleContext();
        Videobridge videobridge;

        if (bundleContext == null)
        {
            videobridge = null;
        }
        else
        {
            videobridge
                = ServiceUtils2.getService(bundleContext, Videobridge.class);
        }
        return videobridge;
    }

    /**
     * Returns the <tt>VersionService</tt> used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>VersionService</tt> used by this
     * <tt>Videobridge</tt>.
     */
    public VersionService getVersionService()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext != null)
        {
            return ServiceUtils2.getService(bundleContext,
                VersionService.class);
        }

        return null;
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

            org.jivesoftware.smack.packet.IQ resultSmackIQ = handleIQ(smackIQ);
            IQ resultIQ;

            if (resultSmackIQ == null)
            {
                resultIQ = null;
            }
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
        org.jivesoftware.smack.packet.IQ responseIQ = null;

        if (iq != null)
        {
            org.jivesoftware.smack.packet.IQ.Type type = iq.getType();

            if (org.jivesoftware.smack.packet.IQ.Type.get.equals(type)
                    || org.jivesoftware.smack.packet.IQ.Type.set.equals(type))
            {
                responseIQ = handleIQRequest(iq);
                if (responseIQ != null)
                {
                    responseIQ.setFrom(iq.getTo());
                    responseIQ.setStanzaId(iq.getStanzaId());
                    responseIQ.setTo(iq.getFrom());
                }
            }
            else if (org.jivesoftware.smack.packet.IQ.Type.error.equals(type)
                    || org.jivesoftware.smack.packet.IQ.Type.result.equals(
                            type))
            {
                handleIQResponse(iq);
            }
        }
        return responseIQ;
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
            logd("An error occurred while trying to handle error IQ.");
            loge(e);
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

    private org.jivesoftware.smack.packet.IQ handleIQRequest(
            org.jivesoftware.smack.packet.IQ request)
        throws Exception
    {
        // Requests can be categorized in pieces of Videobridge functionality
        // based on the org.jivesoftware.smack.packet.IQ runtime type (of their
        // child element) and forwarded to specialized Videobridge methods for
        // convenience.
        if (request instanceof org.jivesoftware.smackx.iqversion.packet.Version)
        {
            return
                handleVersionIQ(
                        (org.jivesoftware.smackx.iqversion.packet.Version)
                                request);
        }

        Videobridge videobridge = getVideobridge();
        if (videobridge == null)
        {
            return IQUtils.createError(
                    request,
                    XMPPError.Condition.internal_server_error,
                    "No Videobridge service is running");
        }

        org.jivesoftware.smack.packet.IQ response;

        if (request instanceof ColibriConferenceIQ)
        {
            response
                = videobridge.handleColibriConferenceIQ(
                        (ColibriConferenceIQ) request);
        }
        else if (request instanceof HealthCheckIQ)
        {
            response = videobridge.handleHealthCheckIQ((HealthCheckIQ) request);
        }
        else if (request instanceof ShutdownIQ)
        {
            response = videobridge.handleShutdownIQ((ShutdownIQ) request);
        }
        else
        {
            response = null;
        }
        return response;
    }

    /**
     * Handles a <tt>Version</tt> stanza which represents a request.
     *
     * @param versionRequest the <tt>Version</tt> stanza represents
     * the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request.
     */
    private org.jivesoftware.smack.packet.IQ handleVersionIQ(
            org.jivesoftware.smackx.iqversion.packet.Version versionRequest)
    {
        VersionService versionService = getVersionService();
        if (versionService == null)
        {
            return org.jivesoftware.smack.packet.IQ.createErrorResponse(
                versionRequest,
                XMPPError.getBuilder(XMPPError.Condition.service_unavailable));
        }

        org.jitsi.service.version.Version
            currentVersion = versionService.getCurrentVersion();

        if (currentVersion == null)
        {
            return org.jivesoftware.smack.packet.IQ.createErrorResponse(
                versionRequest,
                XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        }

        // send packet
        org.jivesoftware.smackx.iqversion.packet.Version versionResult =
            new org.jivesoftware.smackx.iqversion.packet.Version(
                    currentVersion.getApplicationName(),
                    currentVersion.toString(),
                    System.getProperty("os.name")
            );

        // to, from and packetId are set by the caller.
        // versionResult.setTo(versionRequest.getFrom());
        // versionResult.setFrom(versionRequest.getTo());
        // versionResult.setPacketID(versionRequest.getPacketID());
        versionResult.setType(org.jivesoftware.smack.packet.IQ.Type.result);

        return versionResult;
    }

    private void handleIQResponse(org.jivesoftware.smack.packet.IQ response)
        throws Exception
    {
        /*
         * Requests can be categorized in pieces of Videobridge functionality
         * based on the org.jivesoftware.smack.packet.IQ runtime type (of their
         * child element) and forwarded to specialized Videobridge methods for
         * convenience. However, responses cannot be categorized because they
         * care the id of their respective requests only.
         */
        Videobridge videobridge = getVideobridge();

        if (videobridge != null)
            videobridge.handleIQResponse(response);
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
            logd("An error occurred while trying to handle result IQ.");
            loge(e);
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

        // Schedule ping task
        // note: the task if stopped automatically on component shutdown
        ConfigurationService config
            = ServiceUtils.getService(
                    bundleContext, ConfigurationService.class);

        loadConfig(config, "org.jitsi.videobridge");

        if (!isPingTaskStarted())
            startPingTask();
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
