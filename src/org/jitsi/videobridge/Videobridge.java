/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.lang.management.*;
import java.text.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.stats.transport.*;
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
 */
public class Videobridge implements StatsGenerator
{
    /**
     * The XML namespace of the <tt>TransportManager</tt> type to be initialized
     * by <tt>Channel</tt> by default.
     */
    private static String defaultTransportManager;

    /**
     * The name of configuration property used to specify default processing
     * options passed as the second argument to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)}.
     */
    private static final String DEFAULT_OPTIONS_PROPERTY_NAME
        = "org.jitsi.videobridge.defaultOptions";

    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Videobridge.class);

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s without an associated conference focus are
     * allowed.
     */
    public static final int OPTION_ALLOW_NO_FOCUS = 1;

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s can be accessed by any peer(not only by the
     * focus that created the conference).
     */
    public static final int OPTION_ALLOW_ANY_FOCUS = 2;

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
     * The XMPP API of Jitsi Videobridge.
     */
    public static final String XMPP_API = "xmpp";

    /**
     * The (base) <tt>System</tt> and/or <tt>ConfigurationService</tt> property
     * of the XMPP API of Jitsi Videobridge.
     */
    public static final String XMPP_API_PNAME
        = "org.jitsi.videobridge." + XMPP_API;

    /**
     * The name of the property which controls whether media recording is
     * enabled.
     */
    static final String MEDIA_RECORDING_PATH_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_PATH";

    /**
    * The name of the property which specifies the path to the directory in
    * which media recordings will be stored.
    */
    static final String ENABLE_MEDIA_RECORDING_PNAME
        = "org.jitsi.videobridge.ENABLE_MEDIA_RECORDING";

    /**
     * The name of the property which specifies the token used to authenticate
     * requests to enable media recording.
     */
    static final String MEDIA_RECORDING_TOKEN_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_TOKEN";

    /**
     * The name of the property which enables generating and sending statistics
     * about the videobridge.
     */
    private static final String ENABLE_STATISTICS
        = "org.jitsi.videobridge.ENABLE_STATISTICS";

    /**
     * The name of the property which specifies the transport for
     * sending statistics about the videobridge.
     */
    private static final String STATISTICS_TRANSPORT
        = "org.jitsi.videobridge.STATISTICS_TRANSPORT";

    /**
     * The name of the property which specifies the interval in milliseconds for
     * sending statistics about the videobridge.
     */
    private static final String STATISTICS_INTERVAL
        = "org.jitsi.videobridge.STATISTICS_INTERVAL";

    /**
     * The name of the property which specifies the name of the service that
     * will receive the statistics about the videobridge if PubSub transport is
     * used to send statistics.
     */
    private static final String PUBSUB_SERVICE
        = "org.jitsi.videobridge.PUBSUB_SERVICE";

    /**
     * The name of the property which specifies the name of the PubSub node that
     * will receive the statistics about the videobridge if PubSub transport is
     * used to send statistics.
     */
    private static final String PUBSUB_NODE
        = "org.jitsi.videobridge.PUBSUB_NODE";

    /**
     * The default value for statistics transport.
     */
    private static final String DEFAULT_STAT_TRANSPORT = "pubsub";

    /**
     * The value for PubSub statistics transport.
     */
    private static final String STAT_TRANSPORT_PUBSUB = "pubsub";

    /**
     * The value for COLIBRI statistics transport.
     */
    private static final String STAT_TRANSPORT_COLIBRI = "colibri";

    /**
     * The default value for statistics interval.
     */
    private static final int DEFAULT_STAT_INTERVAL = 1000;

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use by default.
     */
    static final String RTCP_TERMINATION_STRATEGY_PNAME
            = "org.jitsi.videobridge.rtcp.strategy";

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level
     */
    private static void logd(String s)
    {
        logger.debug(s);
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
     * Interval in milliseconds for stats generation.
     */
    private int statsInterval = -1;

    /**
     * Listener for <tt>ComponentImpl</tt>
     */
    private ServiceListener serviceListener = new ServiceListener()
    {

        @Override
        public void serviceChanged(ServiceEvent event)
        {
            if(!getComponents().isEmpty())
            {
                startStatistics();
                bundleContext.removeServiceListener(this);
            }

        }
    };

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

            if ((conferenceFocus == null) || conferenceFocus.equals(focus))
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
        return handleColibriConferenceIQ(conferenceIQ,
                                         defaultProcessingOptions);
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
                        content.setRTCPTerminationStrategyFromFQN(strategyFQN);

                    ColibriConferenceIQ.Content responseContentIQ
                        = new ColibriConferenceIQ.Content(content.getName());

                    responseConferenceIQ.addContent(responseContentIQ);

                    for (ColibriConferenceIQ.Channel channelIQ
                            : contentIQ.getChannels())
                    {
                        String channelID = channelIQ.getID();
                        int channelExpire = channelIQ.getExpire();
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
                                    : content.createChannel();
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

                    for(ColibriConferenceIQ.SctpConnection sctpConnIq
                        : contentIQ.getSctpConnections())
                    {
                        Endpoint endpoint
                            = conference.getOrCreateEndpoint(
                                    sctpConnIq.getEndpoint());

                        int sctpPort = sctpConnIq.getPort();

                        SctpConnection sctpConn
                            = content.getSctpConnection(endpoint);

                        int sctpExpire = sctpConnIq.getExpire();

                        if(sctpConn == null && sctpExpire == 0)
                        {
                            // Expire request for already expired/non-existing
                            // SCTP connection
                            continue;
                        }

                        if(sctpConn == null)
                        {
                            sctpConn
                                = content.createSctpConnection(
                                        endpoint, sctpPort);
                        }

                        // Expire
                        if (sctpExpire
                            != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
                        {
                            sctpConn.setExpire(sctpExpire);
                        }

                        // Check if SCTP connection has expired
                        if(sctpConn.isExpired())
                            continue;

                        // Initiator
                        Boolean initiator = sctpConnIq.isInitiator();

                        if (initiator != null)
                            sctpConn.setInitiator(initiator);

                        // Transport
                        sctpConn.setTransport(sctpConnIq.getTransport());

                        // Response
                        ColibriConferenceIQ.SctpConnection responseSctpIq
                            = new ColibriConferenceIQ.SctpConnection();

                        sctpConn.describe(responseSctpIq);

                        responseContentIQ.addSctpConnection(responseSctpIq);
                    }
                }

                if (responseConferenceIQ == null)
                    break;
            }
        }

        // Update the endpoint information.
        if (conference != null)
        {
            for (ColibriConferenceIQ.Endpoint colibriEndpoint
                    : conferenceIQ.getEndpoints())
            {
                conference.updateEndpoint(colibriEndpoint);
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
     * Start to send statistics.
     */
    private void startStatistics()
    {
        StatsManager statsManager
            = ServiceUtils.getService(bundleContext,
                StatsManager.class);
        if(statsManager != null)
        {
            ConfigurationService config
                = ServiceUtils.getService(bundleContext,
                                          ConfigurationService.class);

            String transport
                = config.getString(STATISTICS_TRANSPORT, DEFAULT_STAT_TRANSPORT);

            statsInterval
                = config.getInt(STATISTICS_INTERVAL, DEFAULT_STAT_INTERVAL);

            statsManager.addStat(this, VideobridgeStatistics.getStatistics());

            if(STAT_TRANSPORT_COLIBRI.equals(transport))
            {
                statsManager.start(new ColibriStatsTransport(this), this,
                    statsInterval);
            }
            else if(STAT_TRANSPORT_PUBSUB.equals(transport))
            {
                String service = config.getString(PUBSUB_SERVICE);
                String node = config.getString(PUBSUB_NODE);
                if(service != null && node != null)
                {
                    statsManager.start(
                        new PubsubStatsTransport(this, service, node),
                        this, statsInterval);
                }
                else
                {
                    logger.error("No configuration options for "
                        + "PubSub service and node are found.");
                }
            }
            else
            {
                logger.error("Unknown statistics transport is specified.");
            }
        }
        else
        {
            logger.error("Stat manager is not started.");
        }

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
        ConfigurationService config
            = ServiceUtils.getService(bundleContext,
                                      ConfigurationService.class);

        this.defaultProcessingOptions
            = config.getInt(DEFAULT_OPTIONS_PROPERTY_NAME, 0);

        logd("Default videobridge processing options: 0x"
                        + Integer.toHexString(defaultProcessingOptions));

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

        // PubSub
        providerManager.addIQProvider(
            PubSubElementType.PUBLISH.getElementName(),
            PubSubElementType.PUBLISH.getNamespace().getXmlns(),
            new PubSubProvider());

        // TODO Packet logging for ice4j is not supported at this time.
        StunStack.setPacketLogger(null);

        // Make all ice4j properties system properties.

        if (config != null)
        {
            List<String> ice4jPropertyNames =
                    config.getPropertyNamesByPrefix("org.ice4j.", false);

            if (ice4jPropertyNames != null && ice4jPropertyNames.size() != 0)
            {
                for (String propertyName : ice4jPropertyNames)
                {
                    String propertyValue = config.getString(propertyName);

                    // we expect the getString to return either null or a
                    // non-empty String object.
                    if (propertyValue == null)
                        continue;

                    System.setProperty(propertyName,
                            propertyValue);
                }
            }
        }

        // Initialize the the host candidate interface filters in the ice4j
        // stack.
        try
        {
            HostCandidateHarvester.initializeInterfaceFilters();
        }
        catch (Exception e){
            logger.warn("There were errors during host " +
                    "candidate interface filters initialization.", e);
        }

        this.bundleContext = bundleContext;

        boolean isStatsEnabled = config.getBoolean(ENABLE_STATISTICS, false);
        if(isStatsEnabled)
        {
            if(getComponents().isEmpty())
            {
                bundleContext.addServiceListener(serviceListener);
            }
            else
            {
                startStatistics();
            }
        }
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
        this.bundleContext.removeServiceListener(serviceListener);
        this.bundleContext = null;

        StatsManager statsManager
            = ServiceUtils.getService(bundleContext,
                StatsManager.class);

        if(statsManager != null)
        {
            statsManager.removeStat(this);
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
            return ServiceUtils.getService(bundleContext,
                                           ConfigurationService.class);
        }

        return null;
    }

    @Override
    public void generateStatistics(Statistics stats)
    {
        int audioChannels = 0, videoChannels = 0, conferences = 0, endpoints = 0;
        long packets = 0, packetsLost = 0, bytesRecived = 0, bytesSent = 0;

        for(Conference conference : getConferences())
        {
            for(Content content : conference.getContents())
            {
                if(MediaType.AUDIO.equals(content.getMediaType()))
                {
                     audioChannels += content.getChannelCount();
                }
                else if(MediaType.VIDEO.equals(content.getMediaType()))
                {
                    videoChannels += content.getChannelCount();
                }
                for(Channel channel : content.getChannels())
                {
                    if(!(channel instanceof RtpChannel))
                        continue;
                    RtpChannel rtpChannel = (RtpChannel) channel;
                    packets += rtpChannel.getLastPacketsNB();
                    packetsLost += rtpChannel.getLastPacketsLostNB();
                    bytesRecived += rtpChannel.getNBReceivedBytes();
                    bytesSent += rtpChannel.getNBSentBytes();
                }
            }
            conferences++;
            endpoints += conference.getEndpointsCount();
        }

        double packetLostPercent = 0;
        if(packets > 0)
        {
            packetLostPercent = ((double)packetsLost)/packets;
        }

        DecimalFormat formater = new DecimalFormat("#.#####");

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_RTP_LOSS,
            formater.format(packetLostPercent));

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_BITRATE_DOWNLOAD,
            formater.format(VideobridgeStatistics.calculateBitRate(
                bytesRecived, statsInterval)));

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_BITRATE_UPLOAD,
            formater.format(VideobridgeStatistics.calculateBitRate(
                bytesSent, statsInterval)));

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_NUMBEROFTHREADS,
            ManagementFactory.getThreadMXBean().getThreadCount());

        stats.setStat(
            VideobridgeStatistics.VIDEOBRIDGESTATS_NUMBEROFPARTICIPANTS,
            endpoints);

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_AUDIOCHANNELS,
            audioChannels);

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_VIDEOCHANNELS,
            videoChannels);

        stats.setStat(VideobridgeStatistics.VIDEOBRIDGESTATS_CONFERENCES,
            conferences);
    }
}
