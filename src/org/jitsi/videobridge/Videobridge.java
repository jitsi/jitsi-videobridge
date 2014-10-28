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

import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.osgi.*;
import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.sim.*;
import org.jitsi.videobridge.xmpp.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.provider.*;
import org.osgi.framework.*;

/**
 * Represents the Jitsi Videobridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 * @author Hristo Terezov
 * @author Boris Grozev
 */
public class Videobridge
{
    public static final String COLIBRI_CLASS = "colibriClass";

    /**
     * The name of configuration property used to specify default processing
     * options passed as the second argument to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)}.
     */
    private static final String DEFAULT_OPTIONS_PROPERTY_NAME
        = "org.jitsi.videobridge.defaultOptions";

    /**
     * The XML namespace of the <tt>TransportManager</tt> type to be initialized
     * by <tt>Channel</tt> by default.
     */
    private static String defaultTransportManager;

    /**
    * The name of the property which specifies the path to the directory in
    * which media recordings will be stored.
    */
    static final String ENABLE_MEDIA_RECORDING_PNAME
        = "org.jitsi.videobridge.ENABLE_MEDIA_RECORDING";

    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Videobridge.class);

    /**
     * The name of the property which controls whether media recording is
     * enabled.
     */
    static final String MEDIA_RECORDING_PATH_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_PATH";

    /**
     * The name of the property which specifies the token used to authenticate
     * requests to enable media recording.
     */
    static final String MEDIA_RECORDING_TOKEN_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_TOKEN";

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s can be accessed by any peer(not only by the
     * focus that created the conference).
     */
    public static final int OPTION_ALLOW_ANY_FOCUS = 2;

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s without an associated conference focus are
     * allowed.
     */
    public static final int OPTION_ALLOW_NO_FOCUS = 1;

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} and {@link Channel} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    static final Random RANDOM = new Random();

    /**
     * The REST-like HTTP/JSON API of Jitsi Videobridge.
     */
    public static final String REST_API = "rest";

    /**
     * The (base) <tt>System</tt> and/or <tt>ConfigurationService</tt> property
     * of the REST-like HTTP/JSON API of Jitsi Videobridge.
     */
    public static final String REST_API_PNAME
        = "org.jitsi.videobridge." + REST_API;

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use when there are less than 3 participants.
     */
    static final String RTCP_TERMINATION_FALLBACK_STRATEGY_PNAME
            = "org.jitsi.videobridge.rtcp.fallbackStrategy";

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use by default.
     */
    static final String RTCP_TERMINATION_STRATEGY_PNAME
            = "org.jitsi.videobridge.rtcp.strategy";

    /**
     * The XMPP API of Jitsi Videobridge.
     */
    public static final String XMPP_API = "xmpp";

    /**
     * The (base) <tt>System</tt> and/or <tt>ConfigurationService</tt> property
     * of the XMPP API of Jitsi Videobridge.
     */
    public static final String XMPP_API_PNAME
        = "org.jitsi.videobridge." + XMPP_API;

    public static Collection<Videobridge> getVideobridges(
            BundleContext bundleContext)
    {
        return ServiceUtils2.getServices(bundleContext, Videobridge.class);
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
     * Default options passed as second argument to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)}
     */
    private int defaultProcessingOptions;

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
     * instances. Optionally the new instance is owned by a specific conference
     * focus i.e. further/future requests to manage the new instance must come
     * from the specified <tt>focus</tt> or they will be ignored. If the focus
     * is not specified this safety check is overridden.
     *
     * @param focus (optional) a <tt>String</tt> which specifies the JID of
     * the conference focus which will own the new instance i.e. from whom
     * further/future requests to manage the new instance must come or they will
     * be ignored. Pass <tt>null</tt> to override this safety check.
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
        if (logger.isInfoEnabled())
        {
            logger.info(
                    "Created conference " + conference.getID()
                        + ". The total number of conferences is now "
                        + getConferenceCount() + ", channels "
                        + getChannelCount() + ".");
        }

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

    /**
     * Gets the <tt>ComponentImpl</tt> instances which implement the XMPP API of
     * this <tt>Videobridge</tt>.
     *
     * @return the <tt>ComponentImpl</tt> instances which implement the XMPP API
     * of this <tt>Videobridge</tt>
     */
    public Collection<ComponentImpl> getComponents()
    {
        return ComponentImpl.getComponents(getBundleContext());
    }

    /**
     * Gets an existing {@link Conference} with a specific ID and a specific
     * conference focus.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @param focus (optional) the JID of the conference focus of the existing
     * <tt>Conference</tt> to get. A <tt>Conference</tt> does not take orders
     * from a (remote) entity other than the conference focus who has
     * initialized it. Pass <tt>null</tt> if you want any participant to be able
     * to modify the conference.
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
             * (Optional) A conference is owned by the focus who has initialized
             * it and it may be managed by that focus only.
             */
            String conferenceFocus = conference.getFocus();

            if ((focus == null) || focus.equals(conferenceFocus))
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
     * Returns the <tt>ConfigurationService</tt> used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>ConfigurationService</tt> used by this
     * <tt>Videobridge</tt>.
     */
    ConfigurationService getConfigurationService()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext != null)
        {
            return ServiceUtils2.getService(bundleContext,
                                           ConfigurationService.class);
        }

        return null;
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
                        = ServiceUtils2.getService(
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
        return
            handleColibriConferenceIQ(conferenceIQ, defaultProcessingOptions);
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

        if ((options & OPTION_ALLOW_ANY_FOCUS) > 0)
        {
            // Act like the focus was not provided at all
            options |= OPTION_ALLOW_NO_FOCUS;
            focus = null;
        }

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

            if (id == null)
                conference = createConference(focus);
            else
                conference = getConference(id, focus);

            if (conference != null)
                conference.setLastKnownFocus(conferenceIQ.getFrom());
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

            ColibriConferenceIQ.Recording recordingIQ
                = conferenceIQ.getRecording();

            if (recordingIQ != null)
            {
                String tokenIQ = recordingIQ.getToken();

                if (tokenIQ != null)
                {
                    String tokenConfig
                        = getConfigurationService().getString(
                                Videobridge.MEDIA_RECORDING_TOKEN_PNAME);

                    if (tokenIQ.equals(tokenConfig))
                    {
                        boolean recording
                            = conference.setRecording(recordingIQ.getState());
                        ColibriConferenceIQ.Recording responseRecordingIq
                                = new ColibriConferenceIQ.Recording(recording);

                        if (recording)
                        {
                            responseRecordingIq.setPath(
                                    conference.getRecordingPath());
                        }
                        responseConferenceIQ.setRecording(responseRecordingIq);
                    }
                }
            }

            // Get the RTCP termination strategy.
            ColibriConferenceIQ.RTCPTerminationStrategy strategyIQ
                = conferenceIQ.getRTCPTerminationStrategy();
            String strategyFQN;

            if (strategyIQ == null)
            {
                strategyFQN = null;
            }
            else
            {
                strategyFQN = strategyIQ.getName();
                if (strategyFQN != null)
                {
                    strategyFQN = strategyFQN.trim();
                    if (strategyFQN.length() == 0)
                        strategyFQN = null;
                }
            }

            for (ColibriConferenceIQ.Content contentIQ
                    : conferenceIQ.getContents())
            {
                /*
                 * The content element springs into existence whenever it gets
                 * mentioned, it does not need explicit creation (in contrast to
                 * the conference and channel elements).
                 */
                Content content
                    = conference.getOrCreateContent(contentIQ.getName());

                if (content == null)
                {
                    responseConferenceIQ = null;
                }
                else
                {
                    // Set the RTCP termination strategy.
                    if (strategyFQN != null)
                        content.setRTCPTerminationStrategyFQN(strategyFQN);

                    ColibriConferenceIQ.Content responseContentIQ
                        = new ColibriConferenceIQ.Content(content.getName());

                    responseConferenceIQ.addContent(responseContentIQ);

                    for (ColibriConferenceIQ.Channel channelIQ
                            : contentIQ.getChannels())
                    {
                        String channelID = channelIQ.getID();
                        int channelExpire = channelIQ.getExpire();
                        String channelBundleId = channelIQ.getChannelBundleId();
                        RtpChannel channel;

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
                                    : content.createRtpChannel(channelBundleId);
                        }
                        else
                        {
                            channel
                                = (RtpChannel) content.getChannel(channelID);
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

                            if (channelBundleId != null)
                            {
                                TransportManager transportManager
                                    = conference.getTransportManager(
                                            channelBundleId,
                                            true);

                                transportManager.addChannel(channel);
                            }

                            // endpoint
                            // The attribute endpoint is optional. If a value is
                            // not specified, then the Channel endpoint is to
                            // not be changed.
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

                            Boolean adaptiveLastN
                                    = channelIQ.getAdaptiveLastN();
                            if (adaptiveLastN != null)
                                channel.setAdaptiveLastN(adaptiveLastN);

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

                            if (channel instanceof VideoChannel)
                            {
                                List<SourceGroupPacketExtension> sourceGroups
                                    = channelIQ.getSourceGroups();
                                VideoChannel videoChannel
                                    = (VideoChannel) channel;

                                if (sourceGroups != null)
                                {
                                    SimulcastManager manager = videoChannel
                                            .getSimulcastManager();

                                    SortedSet<SimulcastLayer> layers =
                                            SimulcastLayersFactory
                                                    .fromSourceGroups(
                                                            sourceGroups,
                                                            manager);

                                    manager.setSimulcastLayers(layers);
                                }

                                Integer receivingSimulcastLayer
                                    = channelIQ.getReceivingSimulcastLayer();

                                if (receivingSimulcastLayer != null)
                                {
                                    // TODO(gp) remove the receiving simulcast
                                    // layer attribute from the COLIBRI stanza. It
                                    // was introduced in the very early stages of
                                    // simulcast development and it is no longer
                                    // required.
                                }
                            }

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

                    for (ColibriConferenceIQ.SctpConnection sctpConnIq
                            : contentIQ.getSctpConnections())
                    {
                        String id = sctpConnIq.getID();
                        String endpointID = sctpConnIq.getEndpoint();
                        SctpConnection sctpConn;
                        int expire = sctpConnIq.getExpire();
                        String channelBundleId = sctpConnIq.getChannelBundleId();

                        // No ID means SCTP connection is to either be created
                        // or focus uses endpoint identity.
                        if (id == null)
                        {
                            // FIXME The method
                            // Content.getSctpConnection(Endpoint) is annotated
                            // as deprecated but SctpConnection identification
                            // by Endpoint (ID) is to continue to be supported
                            // for legacy purposes.
                            Endpoint endpoint
                                = (endpointID == null)
                                    ? null
                                    : conference.getOrCreateEndpoint(
                                            endpointID);

                            sctpConn = content.getSctpConnection(endpoint);
                            if (sctpConn == null)
                            {
                                // Expire an expired/non-existing SCTP
                                // connection.
                                if (expire == 0)
                                    continue;

                                int sctpPort = sctpConnIq.getPort();

                                sctpConn
                                    = content.createSctpConnection(
                                            endpoint,
                                            sctpPort,
                                            channelBundleId);
                            }
                        }
                        else
                        {
                            sctpConn = content.getSctpConnection(id);
                            // Expire an expired/non-existing SCTP connection.
                            if (sctpConn == null && expire == 0)
                                continue;
                            // endpoint
                            if (endpointID != null)
                                sctpConn.setEndpoint(endpointID);
                        }

                        // expire
                        if (expire
                                != ColibriConferenceIQ.Channel
                                        .EXPIRE_NOT_SPECIFIED)
                        {
                            sctpConn.setExpire(expire);
                        }

                        // Check if SCTP connection has expired.
                        if (sctpConn.isExpired())
                            continue;

                        // initiator
                        Boolean initiator = sctpConnIq.isInitiator();

                        if (initiator != null)
                            sctpConn.setInitiator(initiator);

                        // transport
                        sctpConn.setTransport(sctpConnIq.getTransport());

                        if (channelBundleId != null)
                        {
                            TransportManager transportManager
                                = conference.getTransportManager(
                                        channelBundleId,
                                        true);

                            transportManager.addChannel(sctpConn);
                        }

                        // response
                        ColibriConferenceIQ.SctpConnection responseSctpIq
                            = new ColibriConferenceIQ.SctpConnection();

                        sctpConn.describe(responseSctpIq);

                        responseContentIQ.addSctpConnection(responseSctpIq);
                    }
                }

                if (responseConferenceIQ == null)
                    break;
            }
            for (ColibriConferenceIQ.ChannelBundle channelBundleIq
                    : conferenceIQ.getChannelBundles())
            {
                TransportManager transportManager
                    = conference.getTransportManager(channelBundleIq.getId());
                IceUdpTransportPacketExtension transportIq
                    = channelBundleIq.getTransport();

                if (transportManager != null && transportIq != null)
                {
                    transportManager.startConnectivityEstablishment(
                            transportIq);
                }
            }
        }

        // Update the endpoint information of Videobridge with the endpoint
        // information of the IQ.
        if (conference != null)
        {
            for (ColibriConferenceIQ.Endpoint colibriEndpoint
                    : conferenceIQ.getEndpoints())
            {
                conference.updateEndpoint(colibriEndpoint);
            }

            if (responseConferenceIQ != null)
                conference.describeChannelBundles(responseConferenceIQ);
        }

        if (responseConferenceIQ != null)
        {
            responseConferenceIQ.setType(
                    org.jivesoftware.smack.packet.IQ.Type.RESULT);
        }
        return responseConferenceIQ;
    }

    public void handleIQResponse(org.jivesoftware.smack.packet.IQ response)
        throws Exception
    {
        PubSubPublisher.handleIQResponse(response);
    }

    /**
     * Starts this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is to start
     */
    void start(final BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

        this.defaultProcessingOptions
            = (cfg == null)
                ? 0
                : cfg.getInt(DEFAULT_OPTIONS_PROPERTY_NAME, 0);

        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Default videobridge processing options: 0x"
                        + Integer.toHexString(defaultProcessingOptions));
        }

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
        providerManager.addExtensionProvider(
                RtcpmuxPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<RtcpmuxPacketExtension>(
                        RtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        providerManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT_NAME,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider
                    <DtlsFingerprintPacketExtension>(
                        DtlsFingerprintPacketExtension.class));

        // PubSub
        providerManager.addIQProvider(
                PubSubElementType.PUBLISH.getElementName(),
                PubSubElementType.PUBLISH.getNamespace().getXmlns(),
                new PubSubProvider());

        // TODO Packet logging for ice4j is not supported at this time.
        StunStack.setPacketLogger(null);

        // Make all ice4j properties system properties.

        if (cfg != null)
        {
            List<String> ice4jPropertyNames
                = cfg.getPropertyNamesByPrefix("org.ice4j.", false);

            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
            {
                for (String propertyName : ice4jPropertyNames)
                {
                    String propertyValue = cfg.getString(propertyName);

                    // we expect the getString to return either null or a
                    // non-empty String object.
                    if (propertyValue != null)
                        System.setProperty(propertyName, propertyValue);
                }
            }
        }

        // Initialize the the host candidate interface filters in the ice4j
        // stack.
        try
        {
            HostCandidateHarvester.initializeInterfaceFilters();
        }
        catch (Exception e)
        {
            logger.warn(
                    "There were errors during host candidate interface filters"
                        + " initialization.",
                    e);
        }

        this.bundleContext = bundleContext;
    }

    /**
     * Stops this <tt>Videobridge</tt> in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this
     * <tt>Videobridge</tt> is to stop
     */
    void stop(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = null;
    }
}
