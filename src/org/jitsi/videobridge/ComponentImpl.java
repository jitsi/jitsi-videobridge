package org.jitsi.videobridge;

import java.util.*;

import net.java.sip.communicator.impl.neomedia.*;
import net.java.sip.communicator.impl.osgi.framework.launch.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.cobri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.configuration.*;

import org.jitsi.videobridge.util.*;
import org.jivesoftware.smack.provider.*;
import org.osgi.framework.*;
import org.osgi.framework.launch.*;
import org.osgi.framework.startlevel.*;
import org.xmpp.component.*;
import org.xmpp.packet.*;

/**
 * Implements <tt>org.xmpp.component.Component</tt> to provide Jitsi Video
 * Bridge as an internal Jabber component.
 *
 * @author Lyubomir Marinov
 */
public class ComponentImpl
    extends AbstractComponent
    implements BundleActivator
{
    /**
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Video Bridge.
     */
    private static final String[][] BUNDLES
        = {
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
                "net/java/sip/communicator/impl/neomedia/NeomediaActivator"
            },
            {
                "org/jitsi/videobridge/ComponentImplBundleActivator"
            }
        };

    /**
     * The (default) description of <tt>ComponentImpl</tt> instances.
     */
    private static final String DESCRIPTION
        = "Jitsi Video Bridge Jabber Component";

    /**
     * The (default) name of <tt>ComponentImpl</tt> instances.
     */
    private static final String NAME = "JitsiVideoBridge";

    /**
     * The (default) subdomain of the address with which <tt>ComponentImpl</tt>
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
     * The <tt>VideoBridge</tt> which creates, lists and destroys
     * {@link Conference} instances and which is being represented as a Jabber
     * component by this instance.
     */
    private VideoBridge videoBridge;

    /**
     * Initializes a new <tt>ComponentImpl</tt> instance.
     */
    public ComponentImpl()
    {
    }

    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return new String[] { CobriConferenceIQ.NAMESPACE };
    }

    @Override
    protected String discoInfoIdentityCategoryType()
    {
        return "conference";
    }

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
            System.err.println("RECV: " + iq.toXML());

            org.jivesoftware.smack.packet.IQ smackIQ = IQUtils.convert(iq);
            org.jivesoftware.smack.packet.IQ resultSmackIQ = handleIQ(smackIQ);
            IQ resultIQ;

            if (resultSmackIQ == null)
                resultIQ = null;
            else
            {
                resultIQ = IQUtils.convert(resultSmackIQ);

                System.err.println("SENT: " + resultIQ.toXML());
            }
                
            return resultIQ;
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
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
        org.jivesoftware.smack.packet.IQ resultIQ = null;

        if (iq instanceof CobriConferenceIQ)
        {
            CobriConferenceIQ requestConferenceIQ = (CobriConferenceIQ) iq;
            /*
             * The presence of the id attribute in the conference element
             * signals whether a new conference is to be created or an existing
             * conference is to be modified.
             */
            String conferenceID = requestConferenceIQ.getID();
            Conference conference
                = (conferenceID == null)
                    ? videoBridge.createConference()
                    : videoBridge.getConference(conferenceID);
            CobriConferenceIQ responseConferenceIQ;

            if (conference == null)
            {
                responseConferenceIQ = null;
            }
            else
            {
                responseConferenceIQ = new CobriConferenceIQ();
                responseConferenceIQ.setID(conference.getID());

                for (CobriConferenceIQ.Content requestContentIQ
                        : requestConferenceIQ.getContents())
                {
                    /*
                     * The content element springs into existence whenever it
                     * gets mentioned, it does not need explicit creation (in
                     * contrast to the conference and channel elements).
                     */
                    Content content
                        = conference.getOrCreateContent(
                                requestContentIQ.getName());

                    if (content == null)
                    {
                        responseConferenceIQ = null;
                    }
                    else
                    {
                        CobriConferenceIQ.Content responseContentIQ
                            = new CobriConferenceIQ.Content(content.getName());

                        responseConferenceIQ.addContent(responseContentIQ);

                        for (CobriConferenceIQ.Channel requestChannelIQ
                                : requestContentIQ.getChannels())
                        {
                            String channelID = requestChannelIQ.getID();
                            int channelExpire = requestChannelIQ.getExpire();
                            Channel channel;

                            /*
                             * The presence of the id attribute in the channel
                             * element signals whether a new channel is to be
                             * created or an existing channel is to be modified.
                             */
                            if (channelID == null)
                            {
                                /*
                                 * An expire attribute in the channel element
                                 * with value equal to zero requests the
                                 * immediate expiration of the channel in
                                 * question. Consequently, it does not make
                                 * sense to have it in a channel allocation
                                 * request.
                                 */
                                if (channelExpire == 0)
                                    channel = null;
                                else
                                    channel = content.createChannel();
                            }
                            else
                                channel = content.getChannel(channelID);

                            if (channel == null)
                            {
                                responseConferenceIQ = null;
                            }
                            else
                            {
                                if (channelExpire
                                        != CobriConferenceIQ.Channel
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

                                channel.setPayloadTypes(
                                        requestChannelIQ.getPayloadTypes());

                                CobriConferenceIQ.Channel responseChannelIQ
                                    = new CobriConferenceIQ.Channel();

                                responseChannelIQ.setExpire(
                                        channel.getExpire());
                                responseChannelIQ.setHost(channel.getHost());
                                responseChannelIQ.setID(channel.getID());
                                responseChannelIQ.setRTCPPort(
                                        channel.getRTCPPort());
                                responseChannelIQ.setRTPPort(
                                        channel.getRTPPort());
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
                resultIQ = responseConferenceIQ;
                resultIQ.setType(org.jivesoftware.smack.packet.IQ.Type.RESULT);
            }
        }

        if (resultIQ != null)
        {
            resultIQ.setFrom(iq.getTo());
            resultIQ.setPacketID(iq.getPacketID());
            resultIQ.setTo(iq.getFrom());
        }

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

        // Jitsi VideoBridge is a relay so it does not need to capture media.
        System.setProperty(
                MediaServiceImpl.DISABLE_AUDIO_SUPPORT_PNAME,
                trueString);
        System.setProperty(
                MediaServiceImpl.DISABLE_VIDEO_SUPPORT_PNAME,
                trueString);

        startOSGi();
    }

    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        ProviderManager providerManager = ProviderManager.getInstance();

        // <conference>
        providerManager.addIQProvider(
                CobriConferenceIQ.ELEMENT_NAME,
                CobriConferenceIQ.NAMESPACE,
                new CobriIQProvider());
        /*
         * <payload-type> and <parameter> defined by XEP-0167: Jingle RTP
         * Sessions
         */
        providerManager.addExtensionProvider(
                PayloadTypePacketExtension.ELEMENT_NAME,
                CobriConferenceIQ.NAMESPACE,
                new DefaultPacketExtensionProvider<PayloadTypePacketExtension>(
                        PayloadTypePacketExtension.class));
        providerManager.addExtensionProvider(
                ParameterPacketExtension.ELEMENT_NAME,
                CobriConferenceIQ.NAMESPACE,
                new DefaultPacketExtensionProvider<ParameterPacketExtension>(
                        ParameterPacketExtension.class));

        videoBridge = new VideoBridge(this);
    }

    /**
     * Starts the OSGi implementation and the Jitsi Video Bridge bundles.
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

    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (this.bundleContext == bundleContext)
            this.bundleContext = null;

        videoBridge = null;
    }

    /**
     * Stops the Jitsi Video Bridge bundles and the OSGi implementation.
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
