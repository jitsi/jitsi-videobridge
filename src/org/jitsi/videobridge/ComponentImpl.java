/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.util.*;

import net.java.sip.communicator.impl.osgi.framework.launch.*;
import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.ice4j.stack.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.util.*;
import org.jivesoftware.smack.provider.*;
import org.osgi.framework.*;
import org.osgi.framework.launch.*;
import org.osgi.framework.startlevel.*;
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
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Videobridge.
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    private static final String[][] BUNDLES
        = {
            {
                "net/java/sip/communicator/impl/libjitsi/LibJitsiActivator"
            },
            {
                "net/java/sip/communicator/util/UtilActivator",
                "net/java/sip/communicator/impl/fileaccess/FileAccessActivator"
            },
            {
                "net/java/sip/communicator/impl/configuration/ConfigurationActivator"
            },
            {
                "net/java/sip/communicator/impl/resources/ResourceManagementActivator"
            },
            {
                "net/java/sip/communicator/util/dns/DnsUtilActivator"
            },
            {
                "net/java/sip/communicator/impl/netaddr/NetaddrActivator"
            },
            {
                "net/java/sip/communicator/impl/packetlogging/PacketLoggingActivator"
            },
            {
                "net/java/sip/communicator/service/gui/internal/GuiServiceActivator"
            },
            {
                "net/java/sip/communicator/service/protocol/media/ProtocolMediaActivator"
            },
            {
                "org/jitsi/videobridge/ComponentImplBundleActivator"
            }
        };

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
     * The <tt>BundleContext</tt> in which this instance has been started as an
     * OSGi bundle.
     */
    private BundleContext bundleContext;

    /**
     * The <tt>org.osgi.framework.launch.Framework</tt> instance which
     * represents the OSGi instance launched by this <tt>ComponentImpl</tt>.
     */
    private Framework framework;

    /**
     * The <tt>Object</tt> which synchronizes the access to {@link #framework}.
     */
    private final Object frameworkSyncRoot = new Object();

    /**
     * The <tt>Videobridge</tt> which creates, lists and destroys
     * {@link Conference} instances and which is being represented as a Jabber
     * component by this instance.
     */
    private Videobridge videobridge;

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
        /*
         * The presence of the id attribute in the conference element
         * signals whether a new conference is to be created or an existing
         * conference is to be modified.
         */
        String id = conferenceIQ.getID();
        String focus = conferenceIQ.getFrom();
        Conference conference
            = (id == null)
                ? videobridge.createConference(focus)
                : videobridge.getConference(id, focus);
        ColibriConferenceIQ responseConferenceIQ;

        if (conference == null)
        {
            /*
             * Possible reasons for having no Conference instance include
             * failure to produce an ID which identifies an existing Conference
             * instance or the JID of a conference focus which owns an existing
             * Conference instance with a valid ID. 
             */
            responseConferenceIQ = null;
        }
        else
        {
            responseConferenceIQ = new ColibriConferenceIQ();
            conference.describe(responseConferenceIQ);

            for (ColibriConferenceIQ.Content contentIQ
                    : conferenceIQ.getContents())
            {
                /*
                 * The content element springs into existence whenever it
                 * gets mentioned, it does not need explicit creation (in
                 * contrast to the conference and channel elements).
                 */
                Content content
                    = conference.getOrCreateContent(contentIQ.getName());

                if (content == null)
                {
                    responseConferenceIQ = null;
                }
                else
                {
                    ColibriConferenceIQ.Content responseContentIQ
                        = new ColibriConferenceIQ.Content(content.getName());

                    responseConferenceIQ.addContent(responseContentIQ);

                    for (ColibriConferenceIQ.Channel channelIQ
                            : contentIQ.getChannels())
                    {
                        String channelID = channelIQ.getID();
                        int channelExpire = channelIQ.getExpire();
                        Channel channel;

                        /*
                         * The presence of the id attribute in the channel
                         * element signals whether a new channel is to be
                         * created or an existing channel is to be modified.
                         */
                        if (channelID == null)
                        {
                            /*
                             * An expire attribute in the channel element with
                             * value equal to zero requests the immediate
                             * expiration of the channel in question.
                             * Consequently, it does not make sense to have it
                             * in a channel allocation request.
                             */
                            channel
                                = (channelExpire == 0)
                                    ? null
                                    : content.createChannel();
                        }
                        else
                        {
                            channel = content.getChannel(channelID);
                        }

                        if (channel == null)
                        {
                            responseConferenceIQ = null;
                        }
                        else
                        {
                            if (channelExpire
                                    != ColibriConferenceIQ.Channel
                                            .EXPIRE_NOT_SPECIFIED)
                            {
                                channel.setExpire(channelExpire);
                                /*
                                 * If the request indicates that it wants
                                 * the channel expired and the channel is
                                 * indeed expired, then the request is valid
                                 * and has correctly been acted upon.
                                 */
                                if ((channelExpire == 0)
                                        && channel.isExpired())
                                    continue;
                            }

                            /*
                             * The attribute endpoint is optional. If a value is
                             * not specified, then the Channel endpoint is to
                             * not be changed.
                             */
                            String endpoint = channelIQ.getEndpoint();

                            if (endpoint != null)
                                channel.setEndpoint(endpoint);

                            /*
                             * The attribute last-n is optional. If a value is
                             * not specified, then the Channel lastN is to not
                             * be changed.
                             */
                            Integer lastN = channelIQ.getLastN();

                            if (lastN != null)
                                channel.setLastN(lastN);

                            /*
                             * XXX The attribute initiator is optional. If a
                             * value is not specified, then the Channel
                             * initiator is to be assumed default or to not be
                             * changed.
                             */
                            Boolean initiator = channelIQ.isInitiator();

                            if (initiator != null)
                                channel.setInitiator(initiator);

                            channel.setPayloadTypes(
                                    channelIQ.getPayloadTypes());
                            channel.setTransport(channelIQ.getTransport());

                            channel.setDirection(channelIQ.getDirection());

                            /*
                             * Provide (a description of) the current state of
                             * the channel as part of the response.
                             */
                            ColibriConferenceIQ.Channel responseChannelIQ
                                = new ColibriConferenceIQ.Channel();

                            channel.describe(responseChannelIQ);
                            responseContentIQ.addChannel(responseChannelIQ);
                        }

                        if (responseConferenceIQ == null)
                            break;
                    }
                }

                if (responseConferenceIQ == null)
                    break;
            }
        }

        if (responseConferenceIQ != null)
        {
            responseConferenceIQ.setType(
                    org.jivesoftware.smack.packet.IQ.Type.RESULT);
        }
        return responseConferenceIQ;
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

        stopOSGi();
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

        String trueString = Boolean.toString(true);

        /*
         * The design at the time of this writing considers the configuration
         * file read-only (in a read-only directory) and provides only manual
         * editing for it.
         */
        System.setProperty(
                ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY,
                trueString);

        // Jitsi Videobridge is a relay so it does not need to capture media.
        System.setProperty(
                MediaServiceImpl.DISABLE_AUDIO_SUPPORT_PNAME,
                trueString);
        System.setProperty(
                MediaServiceImpl.DISABLE_VIDEO_SUPPORT_PNAME,
                trueString);

        // It makes no sense for Jitsi Videobridge to pace its RTP output.
        if (System.getProperty(
                    DeviceConfiguration.PROP_VIDEO_RTP_PACING_THRESHOLD)
                == null)
        {
            System.setProperty(
                    DeviceConfiguration.PROP_VIDEO_RTP_PACING_THRESHOLD,
                    Integer.toString(Integer.MAX_VALUE));
        }

        startOSGi();
    }

    /**
     * Implements a helper to send <tt>org.jivesoftware.smack.packet.IQ</tt>s.
     *
     * @param iq the <tt>org.jivesoftware.smack.packet.IQ</tt> to send
     * @throws Exception if an error occurs during the conversion of the
     * specified <tt>iq</tt> to an <tt>org.xmpp.packet.IQ</tt> instance or while
     * sending the specified <tt>iq</tt>
     */
    void send(org.jivesoftware.smack.packet.IQ iq)
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
    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        ProviderManager providerManager = ProviderManager.getInstance();

        // <conference>
        providerManager.addIQProvider(
                ColibriConferenceIQ.ELEMENT_NAME,
                ColibriConferenceIQ.NAMESPACE,
                new ColibriIQProvider());

        // ICE-UDP <transport>
        providerManager.addExtensionProvider(
                IceUdpTransportPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider
                    <IceUdpTransportPacketExtension>(
                        IceUdpTransportPacketExtension.class));
        // Raw UDP <transport>
        providerManager.addExtensionProvider(
                RawUdpTransportPacketExtension.ELEMENT_NAME,
                RawUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider
                    <RawUdpTransportPacketExtension>(
                        RawUdpTransportPacketExtension.class));

        PacketExtensionProvider candidatePacketExtensionProvider
            = new DefaultPacketExtensionProvider<CandidatePacketExtension>(
                    CandidatePacketExtension.class);

        // ICE-UDP <candidate>
        providerManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                candidatePacketExtensionProvider);
        // Raw UDP <candidate>
        providerManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                RawUdpTransportPacketExtension.NAMESPACE,
                candidatePacketExtensionProvider);

        // DTLS-SRTP <fingerprint>
        providerManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT_NAME,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider
                    <DtlsFingerprintPacketExtension>(
                        DtlsFingerprintPacketExtension.class));

        // TODO Packet logging for ice4j is not supported at this time.
        StunStack.setPacketLogger(null);

        videobridge = new Videobridge(this);
    }

    /**
     * Starts the OSGi implementation and the Jitsi Videobridge bundles.
     */
    private void startOSGi()
    {
        /*
         * The documentation of AbstractComponent#start() says that it gets
         * called once for each host that this Component connects to and that
         * extending classes should take care to avoid double initialization.
         */
        synchronized (frameworkSyncRoot)
        {
            if (this.framework != null)
                return;
        }

        FrameworkFactory frameworkFactory = new FrameworkFactoryImpl();
        Map<String, String> configuration = new HashMap<String, String>();

        configuration.put(
                Constants.FRAMEWORK_BEGINNING_STARTLEVEL,
                Integer.toString(BUNDLES.length));

        Framework framework = frameworkFactory.newFramework(configuration);

        try
        {
            framework.init();

            BundleContext bundleContext = framework.getBundleContext();

            bundleContext.registerService(Component.class, this, null);

            for (int startLevelMinus1 = 0;
                    startLevelMinus1 < BUNDLES.length;
                    startLevelMinus1++)
            {
                int startLevel = startLevelMinus1 + 1;

                for (String location : BUNDLES[startLevelMinus1])
                {
                    Bundle bundle = bundleContext.installBundle(location);

                    if (bundle != null)
                    {
                        BundleStartLevel bundleStartLevel
                            = bundle.adapt(BundleStartLevel.class);

                        if (bundleStartLevel != null)
                            bundleStartLevel.setStartLevel(startLevel);
                    }
                }
            }

            framework.start();
        }
        catch (BundleException be)
        {
            throw new RuntimeException(be);
        }

        synchronized (frameworkSyncRoot)
        {
            this.framework = framework;
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
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (this.bundleContext == bundleContext)
            this.bundleContext = null;

        videobridge = null;
    }

    /**
     * Stops the Jitsi Videobridge bundles and the OSGi implementation.
     */
    private void stopOSGi()
    {
        Framework framework;

        synchronized (frameworkSyncRoot)
        {
            framework = this.framework;
            this.framework = null;
        }

        if (framework != null)
        {
            try
            {
                framework.stop();
            }
            catch (BundleException be)
            {
                throw new RuntimeException(be);
            }
        }
    }
}
