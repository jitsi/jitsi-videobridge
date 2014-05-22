/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.ice4j.stack.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.provider.*;
import org.osgi.framework.*;

/**
 * Represents the Jitsi Videobridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 */
public class Videobridge
{
    /**
     * The XML namespace of the <tt>TransportManager</tt> type to be initialized
     * by <tt>Channel</tt> by default.
     */
    private static String defaultTransportManager;

    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Videobridge.class);

    public static final int OPTION_ALLOW_NO_FOCUS = 1;

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} and {@link Channel} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    static final Random RANDOM = new Random();

    public static final String REST_API = "rest";

    public static final String REST_API_PNAME
        = "org.jitsi.videobridge." + REST_API;

    public static final String XMPP_API = "xmpp";

    public static final String XMPP_API_PNAME
        = "org.jitsi.videobridge." + XMPP_API;

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        /*
         * FIXME Jitsi Videobridge uses the defaults of java.util.logging at the
         * time of this writing but wants to log at debug level at all times for
         * the time being in order to facilitate early development.
         */
        logger.info(s);
    }

    /**
     * The (OSGi) <tt>BundleContext</tt> in which this <tt>Videobridge</tt> has
     * been started.
     */
    private BundleContext bundleContext;

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferences
        = new HashMap<String, Conference>();

    /**
     * Initializes a new <tt>Videobridge</tt> instance.
     */
    public Videobridge()
    {
        new VideobridgeExpireThread(this).start();
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances. The new instance is owned by a specific conference focus i.e.
     * further/future requests to manage the new instance must come from the
     * specified <tt>focus</tt> or they will be ignored.
     *
     * @param focus a <tt>String</tt> which specifies the JID of the conference
     * focus which will own the new instance i.e. from whom further/future
     * requests to manage the new instance must come or they will be ignored 
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(String focus)
    {
        Conference conference = null;

        do
        {
            String id = generateConferenceID();

            synchronized (conferences)
            {
                if (!conferences.containsKey(id))
                {
                    conference = new Conference(this, id, focus);
                    conferences.put(id, conference);
                }
            }
        }
        while (conference == null);

        /*
         * The method Videobridge.getChannelCount() should better be executed
         * outside synchronized blocks in order to reduce the risks of causing
         * deadlocks.
         */
        logd(
                "Created conference " + conference.getID()
                    + ". The total number of conferences is now "
                    + getConferenceCount() + ", channels " + getChannelCount()
                    + ".");

        return conference;
    }

    /**
     * Expires a specific <tt>Conference</tt> of this <tt>Videobridge</tt> (i.e.
     * if the specified <tt>Conference</tt> is not in the list of
     * <tt>Conference</tt>s of this <tt>Videobridge</tt>, does nothing).
     *
     * @param conference the <tt>Conference</tt> to be expired by this
     * <tt>Videobridge</tt>
     */
    public void expireConference(Conference conference)
    {
        String id = conference.getID();
        boolean expireConference;

        synchronized (conferences)
        {
            if (conference.equals(conferences.get(id)))
            {
                conferences.remove(id);
                expireConference = true;
            }
            else
                expireConference = false;
        }
        if (expireConference)
            conference.expire();
    }

    /**
     * Generates a new <tt>Conference</tt> ID which is not guaranteed to be
     * unique.
     *
     * @return a new <tt>Conference</tt> ID which is not guaranteed to be unique
     */
    private String generateConferenceID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Returns the OSGi <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is executing.
     */
    public BundleContext getBundleContext()
    {
        return bundleContext;
    }

    /**
     * Gets the number of <tt>Channel</tt>s in this <tt>Videobridge</tt> (across
     * all <tt>Conference</tt>s and <tt>Content</tt>s).
     *
     * @return the number of <tt>Channel</tt>s in this <tt>Videobridge</tt>
     */
    int getChannelCount()
    {
        int channelCount = 0;

        for (Conference conference : getConferences())
        {
            for (Content contents : conference.getContents())
                channelCount += contents.getChannelCount();
        }
        return channelCount;
    }

    public Collection<ComponentImpl> getComponents()
    {
        return ComponentImpl.getComponents(getBundleContext());
    }

    /**
     * Gets an existing {@link Conference} with a specific ID and a specific
     * conference focus.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @param focus the JID of the conference focus of the existing
     * <tt>Conference</tt> to get. A <tt>Conference</tt> does not take orders
     * from a (remote) entity other than the conference focus who has
     * initialized it.
     * @return an existing <tt>Conference</tt> with the specified ID and the
     * specified conference focus or <tt>null</tt> if no <tt>Conference</tt>
     * with the specified ID and the specified conference focus is known to this
     * <tt>Videobridge</tt>
     */
    public Conference getConference(String id, String focus)
    {
        Conference conference;

        synchronized (conferences)
        {
            conference = conferences.get(id);
        }

        if (conference != null)
        {
            /*
             * A conference is owned by the focus who has initialized it and it
             * may be managed by that focus only.
             */
            String conferenceFocus = conference.getFocus();

            if ((conferenceFocus == null)
                    ? (focus == null)
                    : conferenceFocus.equals(focus))
            {
                // It seems the conference is still active.
                conference.touch();
            }
            else
            {
                conference = null;
            }
        }

        return conference;
    }

    /**
     * Gets the number of <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the number of <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public int getConferenceCount()
    {
        synchronized (conferences)
        {
            return conferences.size();
        }
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public Conference[] getConferences()
    {
        synchronized (conferences)
        {
            Collection<Conference> values = conferences.values();

            return values.toArray(new Conference[values.size()]);
        }
    }

    /**
     * Gets the XML namespace of the <tt>TransportManager</tt> type to be
     * initialized by <tt>Channel</tt> by default.
     *
     * @return the XML namespace of the <tt>TransportManager</tt> type to be
     * initialized by <tt>Channel</tt> by default
     */
    public String getDefaultTransportManager()
    {
        synchronized (Videobridge.class)
        {
            if (defaultTransportManager == null)
            {
                BundleContext bundleContext = getBundleContext();

                if (bundleContext != null)
                {
                    ConfigurationService cfg
                        = ServiceUtils.getService(
                                bundleContext,
                                ConfigurationService.class);

                    if (cfg != null)
                    {
                        defaultTransportManager
                            = cfg.getString(
                                    Videobridge.class.getName()
                                        + ".defaultTransportManager");
                    }
                }
                if (!IceUdpTransportPacketExtension.NAMESPACE.equals(
                            defaultTransportManager)
                        && !RawUdpTransportPacketExtension.NAMESPACE.equals(
                                defaultTransportManager))
                {
                    defaultTransportManager
                        = IceUdpTransportPacketExtension.NAMESPACE;
                }
            }
            return defaultTransportManager;
        }
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
    public ColibriConferenceIQ handleColibriConferenceIQ(
            ColibriConferenceIQ conferenceIQ)
        throws Exception
    {
        return handleColibriConferenceIQ(conferenceIQ, 0);
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @param options
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    public ColibriConferenceIQ handleColibriConferenceIQ(
            ColibriConferenceIQ conferenceIQ,
            int options)
        throws Exception
    {
        String focus = conferenceIQ.getFrom();
        Conference conference;

        if ((focus == null) && ((options & OPTION_ALLOW_NO_FOCUS) == 0))
        {
            throw new NullPointerException("focus");
        }
        else
        {
            /*
             * The presence of the id attribute in the conference element
             * signals whether a new conference is to be created or an existing
             * conference is to be modified.
             */
            String id = conferenceIQ.getID();

            conference
                = (id == null)
                    ? createConference(focus)
                    : getConference(id, focus);
        }

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
            conference.describeShallow(responseConferenceIQ);

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
                             * The attribute rtp-level-relay-type specifies the
                             * vale of pretty much the most important Channel
                             * property given that Jitsi Videobridge implements
                             * an RTP-level relay. Consequently, it is
                             * intuitively a sign of common sense to take the
                             * value into account as possible.
                             * 
                             * The attribute rtp-level-relay-type is optional.
                             * If a value is not specified, then the Channel
                             * rtpLevelRelayType is to not be changed.
                             */
                            RTPLevelRelayType rtpLevelRelayType
                                = channelIQ.getRTPLevelRelayType();

                            if (rtpLevelRelayType != null)
                                channel.setRTPLevelRelayType(rtpLevelRelayType);

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

    void start(BundleContext bundleContext)
        throws Exception
    {
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

        this.bundleContext = bundleContext;
    }

    void stop(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = null;
    }
}
