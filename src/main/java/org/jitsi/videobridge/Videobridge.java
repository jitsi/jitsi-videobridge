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
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.Constants;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.Logger;
import org.jitsi.util.*;
import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.provider.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

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
     * {@link #handleColibriConferenceIq2(ColibriConferenceIQ, int)}}.
     */
    public static final String DEFAULT_OPTIONS_PROPERTY_NAME
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
    public static final String ENABLE_MEDIA_RECORDING_PNAME
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
    public static final String MEDIA_RECORDING_PATH_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_PATH";

    /**
     * The name of the property which specifies the token used to authenticate
     * requests to enable media recording.
     */
    public static final String MEDIA_RECORDING_TOKEN_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_TOKEN";

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIq2(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s can be accessed by any peer(not only by the
     * focus that created the conference).
     */
    public static final int OPTION_ALLOW_ANY_FOCUS = 2;

    /**
     * The optional flag which specifies to
     * {@link #handleColibriConferenceIq2(ColibriConferenceIQ, int)} that
     * <tt>ColibriConferenceIQ</tt>s without an associated conference focus are
     * allowed.
     */
    public static final int OPTION_ALLOW_NO_FOCUS = 1;

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} and {@link Channel} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    public static final Random RANDOM = new Random();

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
     * The property that specifies allowed entities for turning on graceful
     * shutdown mode. For XMPP API this is "from" JID. In case of REST
     * the source IP is being copied into the "from" field of the IQ.
     */
    public static final String SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME
        = "org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP";

    /**
     * The property that specifies entities authorized to operate the bridge.
     * For XMPP API this is "from" JID. In case of REST the source IP is being
     * copied into the "from" field of the IQ.
     */
    public static final String AUTHORIZED_SOURCE_REGEXP_PNAME
        = "org.jitsi.videobridge.AUTHORIZED_SOURCE_REGEXP";

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
     * The pattern used to filter entities that are allowed to operate
     * the videobridge.
     */
    private Pattern authorizedSourcePattern;

    /**
     * The (OSGi) <tt>BundleContext</tt> in which this <tt>Videobridge</tt> has
     * been started.
     */
    private BundleContext bundleContext;

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferences = new HashMap<>();

    /**
     * Default options passed as second argument to
     * {@link #handleColibriConferenceIq2(ColibriConferenceIQ, int)}
     */
    private int defaultProcessingOptions;

    /**
     * Indicates if this bridge instance has entered graceful shutdown mode.
     */
    private boolean shutdownInProgress;

    /**
     * The pattern used to filter entities that are allowed to trigger graceful
     * shutdown mode.
     */
    private Pattern shutdownSourcePattern;

    /**
     * A class that holds some instance statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Thread that checks expiration for conferences, contents, channels and
     * execute expire procedure for any of them.
     */
    private VideobridgeExpireThread videobridgeExpireThread;

    /**
     * The {@link Health} instance responsible for periodically performing
     * health checks on this videobridge.
     */
    private Health health;

    /**
     * Initializes a new <tt>Videobridge</tt> instance.
     */
    public Videobridge()
    {
        videobridgeExpireThread = new VideobridgeExpireThread(this);
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
     * @param name world readable name of the conference to create.
     * @param gid the optional "global" id of the conference.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(Jid focus, Localpart name, String gid)
    {
        return this.createConference(focus, name, /* enableLogging */ true, gid);
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
     * @param name world readable name of the conference to create.
     * @param enableLogging whether logging should be enabled or disabled for
     * the {@link Conference}.
     * @param gid the optional "global" id of the conference.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(
            Jid focus, Localpart name, boolean enableLogging, String gid)
    {
        Conference conference = null;

        do
        {
            String id = generateConferenceID();

            synchronized (conferences)
            {
                if (!conferences.containsKey(id))
                {
                    conference
                        = new Conference(
                                this,
                                id,
                                focus,
                                name,
                                enableLogging,
                                gid);
                    conferences.put(id, conference);
                }
            }
        }
        while (conference == null);

        // The method Videobridge.getConferenceCountString() should better
        // be executed outside synchronized blocks in order to reduce the
        // risks of causing deadlocks.
        if (logger.isInfoEnabled())
        {
            logger.info(Logger.Category.STATISTICS,
                        "create_conf," + conference.getLoggingId()
                        + " conf_name=" + name
                        + ",logging=" + enableLogging
                        + "," + getConferenceCountString());
        }

        return conference;
    }

    /**
     * Enables graceful shutdown mode on this bridge instance and eventually
     * starts the shutdown immediately if no conferences are currently being
     * hosted. Otherwise bridge will shutdown once all conferences expire.
     */
    private void enableGracefulShutdownMode()
    {
        if (!shutdownInProgress)
        {
            logger.info("Entered graceful shutdown mode");
        }
        this.shutdownInProgress = true;
        maybeDoShutdown();
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

        // Check if it's the time to shutdown now
        maybeDoShutdown();
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
     * Gets the statistics of this instance.
     *
     * @return the statistics of this instance.
     */
    public Statistics getStatistics()
    {
        return statistics;
    }

    /**
     * Gets the number of active <tt>Channel</tt>s in this <tt>Videobridge</tt>
     * (across all active <tt>Conference</tt>s and active <tt>Content</tt>s).
     *
     * @return the number of active <tt>Channel</tt>s in this
     * <tt>Videobridge</tt>
     */
    public int getChannelCount()
    {
        int channelCount = 0;

        for (Conference conference : getConferences())
        {
            if (conference != null && !conference.isExpired())
            {
                for (Content content : conference.getContents())
                {
                    if (content != null && !content.isExpired())
                    {
                        channelCount += content.getChannelCount();
                    }
                }
            }
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
    public Conference getConference(String id, Jid focus)
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
            Jid conferenceFocus = conference.getFocus();

            // If no 'focus' was given as an argument or if conference is not
            // owned by any 'conferenceFocus' then skip equals()
            if (focus == null || conferenceFocus == null
                || focus.equals(conferenceFocus))
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
     * Gets the number of <tt>Conference</tt>s of this <tt>Videobridge</tt> that
     * are not expired.
     *
     * @return the number of <tt>Conference</tt>s of this <tt>Videobridge</tt>
     * that are not expired.
     */
    public int getConferenceCount()
    {
        int sz = 0;

        Conference[] cs = getConferences();
        if (cs != null && cs.length != 0)
        {
            for (Conference c : cs)
            {
                if (c != null && !c.isExpired())
                {
                    sz++;
                }
            }
        }

        return sz;
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
    public ConfigurationService getConfigurationService()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext == null)
        {
            return null;
        }
        else
        {
            return
                ServiceUtils2.getService(
                        bundleContext,
                        ConfigurationService.class);
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
     * Returns the <tt>EventAdmin</tt> instance (to be) used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>EventAdmin</tt> instance (to be) used by this
     * <tt>Videobridge</tt>.
     */
    public EventAdmin getEventAdmin()
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext == null)
            return null;
        else
            return ServiceUtils2.getService(bundleContext, EventAdmin.class);
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     */
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ)
    {
        return
            handleColibriConferenceIq2(conferenceIQ, defaultProcessingOptions);
    }

    /**
     * Checks whether a COLIBRI request from a specific source ({@code focus})
     * with specific {@code options} should be accepted or not.
     * @param focus the source of the request (i.e. the JID of the conference
     * focus).
     * @param options
     * @return {@code true} if a COLIBRI request from focus should be accepted,
     * given the specified {@code options}, and {@code false} otherwise.
     */
    private boolean accept(Jid focus, int options)
    {
        if ((options & OPTION_ALLOW_ANY_FOCUS) > 0)
        {
            return true;
        }

        if (focus == null)
        {
            return (options & OPTION_ALLOW_NO_FOCUS) != 0;
        }

        if (authorizedSourcePattern != null)
        {
            return authorizedSourcePattern.matcher(focus).matches();
        }
        else
        {
            return true;
        }
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
     */
    public IQ handleColibriConferenceIq2(ColibriConferenceIQ conferenceIQ, int options)
    {
        Jid focus = conferenceIQ.getFrom();
        System.out.println("Received colibriConferenceIq \n" + conferenceIQ.toXML());

        if (!accept(focus, options))
        {
            return IQUtils.createError(
                    conferenceIQ, XMPPError.Condition.not_authorized);
        }

        ColibriShim.ConferenceShim conference;

        String conferenceId = conferenceIQ.getID();
        if (conferenceId == null)
        {
            if (isShutdownInProgress())
            {
                return ColibriConferenceIQ.createGracefulShutdownErrorResponse(conferenceIQ);
            }
            else
            {
                conference = colibriShim.createConference(focus, conferenceIQ.getName(), conferenceIQ.getGID());
                if (conference == null)
                {
                    return IQUtils.createError(
                            conferenceIQ,
                            XMPPError.Condition.internal_server_error,
                            "Failed to create new conference");
                }
            }
        }
        else
        {
            conference = colibriShim.getConference(conferenceId);
            if (conference == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.bad_request,
                        "Conference not found for ID: " + conferenceId);
            }
        }

        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        responseConferenceIQ.setGracefulShutdown(isShutdownInProgress());

        Map<String, List<PayloadTypePacketExtension>> endpointPayloadTypes = new HashMap<>();
        Map<String, List<RTPHdrExtPacketExtension>> endpointHeaderExts = new HashMap<>();
        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents())
        {
            /*
             * The content element springs into existence whenever it gets
             * mentioned, it does not need explicit creation (in contrast to
             * the conference and channel elements).
             */
            String contentName = contentIQ.getName();
            ColibriShim.ContentShim content = conference.getOrCreateContent(contentName);
            if (content == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.internal_server_error,
                        "Failed to create new content for name: "
                                + contentName);
            }

            ColibriConferenceIQ.Content responseContentIQ = new ColibriConferenceIQ.Content(content.getName());

            responseConferenceIQ.addContent(responseContentIQ);

            try {
                List<ColibriConferenceIQ.Channel> describedChannels = processChannels(
                        contentIQ.getChannels(),
                        endpointPayloadTypes,
                        endpointHeaderExts,
                        conference,
                        content);
                describedChannels.forEach(responseContentIQ::addChannel);
            } catch (IqProcessingException e) {
                logger.error("Error processing channels in IQ: " + e.toString());
                return IQUtils.createError(conferenceIQ, e.condition, e.errorMessage);
            }

            notifyEndpointsOfPayloadTypes(endpointPayloadTypes, getConference(conference.getId(), null));
            notifyEndpointsOfRtpHeaderExtensions(endpointHeaderExts, getConference(conference.getId(), null));

            try {
                List<ColibriConferenceIQ.SctpConnection> describedSctpConnections =
                        processSctpConnections(contentIQ.getSctpConnections(), conference, content);
                describedSctpConnections.forEach(responseContentIQ::addSctpConnection);
            } catch (IqProcessingException e) {
                logger.error("Error processing sctp connections in IQ: " + e.toString());
                return IQUtils.createError(conferenceIQ, e.condition, e.errorMessage);
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIQ.getChannelBundles())
        {
            ColibriShim.ChannelBundleShim channelBundleShim =
                    conference.getOrCreateChannelBundle(channelBundleIq.getId());
            IceUdpTransportPacketExtension transportIq = channelBundleIq.getTransport();

            if (channelBundleShim != null && transportIq != null)
            {
                channelBundleShim.startConnectivityEstablishment(transportIq);
            }
        }

        //TODO update endpoints(?)

        Set<String> channelBundleIdsToDescribe = getAllSignaledChannelBundleIds(conferenceIQ);
        conference.describeChannelBundles(responseConferenceIQ, channelBundleIdsToDescribe);
        conference.describeEndpoints(responseConferenceIQ);

        responseConferenceIQ.setType(org.jivesoftware.smack.packet.IQ.Type.result);

        System.out.println("Sending colibri conference iq response:\n" + responseConferenceIQ.toXML());
        return responseConferenceIQ;
    }

    /**
     * This method collects all of the channel bundle IDs referenced in the given IQ.
     * @param conferenceIq
     * @return
     */
    private Set<String> getAllSignaledChannelBundleIds(ColibriConferenceIQ conferenceIq)
    {
        Set<String> channelBundleIds = new HashSet<>();
        for (ColibriConferenceIQ.Content contentIq : conferenceIq.getContents()) {
            for (ColibriConferenceIQ.Channel channelIq : contentIq.getChannels()) {
                channelBundleIds.add(channelIq.getChannelBundleId());
            }
            for (ColibriConferenceIQ.SctpConnection sctpConnIq : contentIq.getSctpConnections())
            {
                channelBundleIds.add(sctpConnIq.getChannelBundleId());
            }
        }
        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIq.getChannelBundles())
        {
            channelBundleIds.add(channelBundleIq.getId());
        }
        return channelBundleIds;
    }

    private class IqProcessingException extends Exception {
        public final XMPPError.Condition condition;
        public final String errorMessage;

        public IqProcessingException(XMPPError.Condition condition, String errorMessage) {
            this.condition = condition;
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString()
        {
            return condition.toString() + " " + errorMessage;
        }
    }

    private ColibriShim colibriShim = new ColibriShim(this);

    private List<ColibriConferenceIQ.Channel> processChannels(
            List<ColibriConferenceIQ.Channel> channels,
            Map<String, List<PayloadTypePacketExtension>> endpointPayloadTypes,
            Map<String, List<RTPHdrExtPacketExtension>> endpointHeaderExts,
            ColibriShim.ConferenceShim conference,
            ColibriShim.ContentShim content) throws IqProcessingException
    {
        List<ColibriConferenceIQ.Channel> createdOrUpdatedChannels = new ArrayList<>();
        Map<String, List<SourceGroupPacketExtension>> endpointSourceGroups = new HashMap<>();

        for (ColibriConferenceIQ.Channel channelIq : channels)
        {
            String channelId = channelIq.getID();
            int channelExpire = channelIq.getExpire();
            String channelBundleId = channelIq.getChannelBundleId();
            String endpointId = channelIq.getEndpoint();

            ColibriConferenceIQ.OctoChannel octoChannelIQ
                    = channelIq instanceof ColibriConferenceIQ.OctoChannel
                    ? (ColibriConferenceIQ.OctoChannel) channelIq
                    : null;

            ColibriShim.Channel channel;
            if (channelId == null)
            {
                if (channelExpire == 0)
                {
                    // An expire attribute in the channel element with
                    // value equal to zero requests the immediate
                    // expiration of the channel in question.
                    // Consequently, it does not make sense to have it in a
                    // channel allocation request.
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Channel expire request for empty ID");
                }
                if (endpointId == null)
                {
                    //TODO: is it reasonable to enforce this?
                    // If we're creating a channel, we need to know which endpoint it belongs
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Channel creation requested without endpoint ID");
                }
                if (!endpointId.equals(channelBundleId))
                {
                    //TODO: can we enforce this?
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Endpoint ID does not match channel bundle ID");
                }
                channel = content.createRtpChannel(conference, endpointId, octoChannelIQ != null);
                if (channel == null)
                {
                    throw new IqProcessingException(XMPPError.Condition.internal_server_error, "Error creating channel");
                }
            }
            else
            {
                channel = content.getChannel(channelId);
                if (channel == null)
                {
                    if (channelExpire == 0)
                    {
                        // Channel expired on its own before it was requested to be expired
                        continue;
                    }
                    throw new IqProcessingException(
                            XMPPError.Condition.internal_server_error, "Error finding channel " + channelId);
                }
            }
            MediaDirection channelDirection = channelIq.getDirection();
            Collection<PayloadTypePacketExtension> channelPayloadTypes = channelIq.getPayloadTypes();
            Collection<RTPHdrExtPacketExtension> channelRtpHeaderExtensions = channelIq.getRtpHeaderExtensions();
            Collection<SourcePacketExtension> channelSources = channelIq.getSources();
            Collection<SourceGroupPacketExtension> channelSourceGroups = channelIq.getSourceGroups();
            Integer channelLastN = channelIq.getLastN();

            if (channelExpire != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
            {
                if (channelExpire < 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Invalid 'expire' value: " + channelExpire);
                }
                channel.setExpire(channelExpire);
                /*
                 * If the request indicates that it wants the channel
                 * expired and the channel is indeed expired, then
                 * the request is valid and has correctly been acted upon.
                 */
                if ((channelExpire == 0) && channel.isExpired())
                {
                    continue;
                }
            }
            channel.direction = channelDirection;

            List<PayloadTypePacketExtension> epPayloadTypes =
                    endpointPayloadTypes.computeIfAbsent(endpointId, key -> new ArrayList<>());
            epPayloadTypes.addAll(channelPayloadTypes);
            channel.setPayloadTypes(channelPayloadTypes);

            List<RTPHdrExtPacketExtension> epHeaderExts =
                    endpointHeaderExts.computeIfAbsent(endpointId, key -> new ArrayList<>());
            epHeaderExts.addAll(channelIq.getRtpHeaderExtensions());
            channel.rtpHeaderExtensions = channelRtpHeaderExtensions;

            channel.sources = channelSources;


            if (channelSourceGroups != null)
            {
                List<SourceGroupPacketExtension> epSourceGroups =
                        endpointSourceGroups.computeIfAbsent(endpointId, key -> new ArrayList<>());
                epSourceGroups.addAll(channelSourceGroups);
            }
            channel.sourceGroups = channelSourceGroups;

            if (channelLastN != null)
            {
                channel.lastN = channelLastN;
            }
            ColibriConferenceIQ.Channel responseChannelIQ = new ColibriConferenceIQ.Channel();
            channel.describe(responseChannelIQ);
            createdOrUpdatedChannels.add(responseChannelIQ);
        }

        addSourceGroups(endpointSourceGroups, getConference(conference.getId(), null));
        return createdOrUpdatedChannels;
    }

    private void addSourceGroups(Map<String, List<SourceGroupPacketExtension>> epSourceGroups, Conference conference)
    {
        epSourceGroups.forEach((epId, currEpSourceGroups) -> {
            currEpSourceGroups.forEach(srcGroup -> {
                long primarySsrc = srcGroup.getSources().get(0).getSSRC();
                long secondarySsrc = srcGroup.getSources().get(1).getSSRC();
                // Translate FID -> RTX (Do it this way so it's effectively final and can be used in the lambda
                // below)
                String semantics =
                        srcGroup.getSemantics().equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_FID) ? Constants.RTX : srcGroup.getSemantics();
                if (!semantics.equalsIgnoreCase(SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
                {
                    conference.encodingsManager.addSsrcAssociation(epId, primarySsrc, secondarySsrc, semantics);
                }

            });
        });
    }


    private void notifyEndpointsOfRtpHeaderExtensions(
            Map<String, List<RTPHdrExtPacketExtension>> epHeaderExtensions,
            Conference conference)
    {
        //TODO: like payload types, we may have a buf here if the extensions get updated for a single channel.  if that
        // happens we will clear all of them, but only re-add the ones from the updated channel
        epHeaderExtensions.forEach((epId, headerExtensions) -> {
            logger.info("Notifying ep " + epId + " about " + headerExtensions.size() + " header extensions");
            AbstractEndpoint ep = conference.getEndpoint(epId);
            if (ep != null)
            {
                ep.transceiver.clearRtpExtensions();
                headerExtensions.forEach(hdrExt -> {
                    URI uri = hdrExt.getURI();
                    Byte id = Byte.valueOf(hdrExt.getID());

                    ep.transceiver.addRtpExtension(id, new RTPExtension(uri));
                });
            }
        });

    }

    private void notifyEndpointsOfPayloadTypes(
            Map<String, List<PayloadTypePacketExtension>> epPayloadTypes,
            Conference conference)
    {
        // TODO(brian): the code below is an transitional step in moving logic out of the channel.  instead of
        //  relying on the channel to update the transceiver with the payload types, we do it here (after gathering them
        //  for the entire endpoint, rather than one channel at a time).  This should go elsewhere, but at least here
        //  we've gotten that code out of the channel.
        //TODO: there's a bug here, where i think only the video channel is being updated so we clear the payload types
        // and then only re-set the video ones.  not sure exactly what changed from the logic being moved, but we
        // need to come up with a new way to do this anyway.
        epPayloadTypes.forEach((epId, payloadTypes) -> {
            logger.info("Notifying ep " + epId + " about " + payloadTypes.size() + " payload type mappings");
            AbstractEndpoint ep = conference.getEndpoint(epId);
            if (ep != null) {
                ep.transceiver.clearDynamicRtpPayloadTypes();
                MediaService mediaService = conference.getMediaService();
                payloadTypes.forEach(pt -> {
                    //TODO(brian): the code in JingleUtils#payloadTypeToMediaFormat is a bit confusing.  If it's
                    // an 'unknown' format, it creates an 'unknown format' instance, but then returns null instead
                    // of returning the created format.  i see this happening with ISAC and h264 in my tests, which
                    // i guess aren't configured as supported formats? (when i looked into the supported formats
                    // checking, there was some weirdness there too, so worth taking another look at all this at
                    // some point)
                    MediaFormat mediaFormat
                            = JingleUtils.payloadTypeToMediaFormat(
                            pt,
                            mediaService,
                            null);
                    if (mediaFormat == null) {
                        logger.info("Unable to parse a format for pt " + pt.getID() + " -> " +
                                pt.getName());
                    } else {
                        logger.info("Notifying ep " + epId + " about payload type mapping: " +
                                pt.getID() + " -> " + mediaFormat.toString());
                        ep.transceiver.addDynamicRtpPayloadType((byte)pt.getID(), mediaFormat);
                    }
                });
            }
        });

    }

    /**
     * Processes the list of {@link ColibriConferenceIQ.SctpConnection}s present in a receive
     * {@link ColibriConferenceIQ}.  Returns a list of {@link ColibriConferenceIQ.SctpConnection} elements that contain
     * descriptions of the created and/or updated SCTP connection instances.
     * @param sctpConnections
     * @param conference
     * @param content
     * @return
     * @throws IqProcessingException if there are any errors during the processing of the incoming connections
     */
    private List<ColibriConferenceIQ.SctpConnection> processSctpConnections(
            List<ColibriConferenceIQ.SctpConnection> sctpConnections,
            ColibriShim.ConferenceShim conference,
            ColibriShim.ContentShim content) throws IqProcessingException {
        List<ColibriConferenceIQ.SctpConnection> createdOrUpdatedSctpConnections = new ArrayList<>();
        for (ColibriConferenceIQ.SctpConnection sctpConnIq : sctpConnections)
        {
            String sctpConnId = sctpConnIq.getID();
            String endpointID = sctpConnIq.getEndpoint();
            ColibriShim.SctpConnection sctpConnection;
            int sctpConnExpire = sctpConnIq.getExpire();

            if (sctpConnId == null)
            {
                if (sctpConnExpire == 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "SCTP connection expire request for empty ID");
                }

                if (endpointID == null)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "No endpoint ID specified for the new SCTP connection");
                }

                sctpConnection = content.createSctpConnection(conference.getId(), endpointID);
            }
            else
            {
                sctpConnection = content.getSctpConnection(sctpConnId);
                if (sctpConnection == null && sctpConnExpire == 0)
                {
                    continue;
                }
                else if (sctpConnection == null)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "No SCTP connection found for ID: " + sctpConnId);
                }
            }
            if (sctpConnExpire != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
            {
                if (sctpConnExpire < 0)
                {
                    throw new IqProcessingException(
                            XMPPError.Condition.bad_request, "Invalid 'expire' value: " + sctpConnExpire);
                }
                sctpConnection.setExpire(sctpConnExpire);
                if (sctpConnExpire == 0 && sctpConnection.isExpired())
                {
                    continue;
                }
            }
            ColibriConferenceIQ.SctpConnection responseSctpIq = new ColibriConferenceIQ.SctpConnection();

            sctpConnection.describe(responseSctpIq);

            createdOrUpdatedSctpConnections.add(responseSctpIq);
        }
        return createdOrUpdatedSctpConnections;
    }

    /**
     * Handles <tt>HealthCheckIQ</tt> by performing health check on this
     * <tt>Videobridge</tt> instance.
     *
     * @param healthCheckIQ the <tt>HealthCheckIQ</tt> to be handled.
     * @return IQ with &quot;result&quot; type if the health check succeeded or
     * IQ with &quot;error&quot; type if something went wrong.
     * {@link XMPPError.Condition#internal_server_error} is returned when the
     * health check fails or {@link XMPPError.Condition#not_authorized} if the
     * request comes from a JID that is not authorized to do health checks on
     * this instance.
     */
    public IQ handleHealthCheckIQ(HealthCheckIQ healthCheckIQ)
    {
        if (authorizedSourcePattern != null
                && !authorizedSourcePattern
                    .matcher(healthCheckIQ.getFrom())
                        .matches())
        {
            return
                IQUtils.createError(
                    healthCheckIQ, XMPPError.Condition.not_authorized);
        }

        try
        {
//            System.out.println("BRIAN: skipping health check");
//            healthCheck();

            return IQ.createResultIQ(healthCheckIQ);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return
                IQUtils.createError(
                        healthCheckIQ,
                        XMPPError.Condition.internal_server_error,
                        e.getMessage());
        }
    }

    /**
     * Checks the health of this {@link Videobridge}. If it is healthy it just
     * returns silently, otherwise it throws an exception. Note that this
     * method does not perform any tests, but only checks the cached value
     * provided by the bridge's {@link Health} instance.
     *
     * @throws Exception if the videobridge is not healthy.
     */
    public void healthCheck()
        throws Exception
    {
        if (health == null)
        {
            throw new Exception("No health checks running");
        }
        else
        {
            health.check();
        }
    }

    /**
     * Handles a <tt>GracefulShutdownIQ</tt> stanza which represents a request.
     *
     * @param shutdownIQ the <tt>GracefulShutdownIQ</tt> stanza represents
     *        the request to handle
     * @return an <tt>IQ</tt> stanza which represents the response to
     *         the specified request or <tt>null</tt> to reply with
     *         <tt>feature-not-implemented</tt>
     */
    public IQ handleShutdownIQ(ShutdownIQ shutdownIQ)
    {
        // Security not configured - service unavailable
        if (shutdownSourcePattern == null)
        {
            return IQUtils.createError(
                    shutdownIQ, XMPPError.Condition.service_unavailable);
        }
        // Check if source matches pattern
        Jid from = shutdownIQ.getFrom();
        if (from != null && shutdownSourcePattern.matcher(from).matches())
        {
            logger.info("Accepted shutdown request from: " + from);
            if (shutdownIQ.isGracefulShutdown())
            {
                if (!isShutdownInProgress())
                {
                    enableGracefulShutdownMode();
                }
            }
            else
            {
                new Thread(() -> {
                    try
                    {
                        Thread.sleep(1000);

                        logger.warn("JVB force shutdown - now");

                        System.exit(0);
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                }, "ForceShutdownThread").start();
            }
            return IQ.createResultIQ(shutdownIQ);
        }
        else
        {
            // Unauthorized
            logger.error("Rejected shutdown request from: " + from);
            return IQUtils.createError(
                    shutdownIQ, XMPPError.Condition.not_authorized);
        }
    }

    public void handleIQResponse(org.jivesoftware.smack.packet.IQ response)
    {
        PubSubPublisher.handleIQResponse(response);
    }

    /**
     * Returns {@code true} if this instance has entered graceful shutdown mode.
     *
     * @return {@code true} if this instance has entered graceful shutdown mode;
     * otherwise, {@code false}
     */
    public boolean isShutdownInProgress()
    {
        return shutdownInProgress;
    }

    /**
     * Returns {@code true} if XMPP API has been enabled.
     *
     * @return {@code true} if XMPP API has been enabled; otherwise,
     * {@code false}
     */
    public boolean isXmppApiEnabled()
    {
        ConfigurationService config
            = ServiceUtils.getService(
                getBundleContext(), ConfigurationService.class);

        // The XMPP API is disabled by default.
        return config != null &&
            config.getBoolean(Videobridge.XMPP_API_PNAME, false);
    }

    /**
     * Triggers the shutdown given that we're in graceful shutdown mode and
     * there are no conferences currently in progress.
     */
    private void maybeDoShutdown()
    {
        if (!shutdownInProgress)
            return;

        synchronized (conferences)
        {
            if (conferences.isEmpty())
            {
                ShutdownService shutdownService
                    = ServiceUtils.getService(
                            bundleContext,
                            ShutdownService.class);

                logger.info("Videobridge is shutting down NOW");
                shutdownService.beginShutdown();
            }
        }
    }

    /**
     * Configures regular expression used to filter users authorized to manage
     * conferences and trigger graceful shutdown(if separate pattern has not
     * been configured).
     * @param authorizedSourceRegExp regular expression string
     */
    public void setAuthorizedSourceRegExp(String authorizedSourceRegExp)
    {
        if (!StringUtils.isNullOrEmpty(authorizedSourceRegExp))
        {
            authorizedSourcePattern
                = Pattern.compile(authorizedSourceRegExp);

            // If no shutdown regexp, then authorized sources are also allowed
            // to trigger graceful shutdown.
            if (shutdownSourcePattern == null)
            {
                shutdownSourcePattern = authorizedSourcePattern;
            }
        }
        // Turn off
        else
        {
            if (shutdownSourcePattern == authorizedSourcePattern)
            {
                shutdownSourcePattern = null;
            }
            authorizedSourcePattern = null;
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
        UlimitCheck.printUlimits();

        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

        videobridgeExpireThread.start(bundleContext);
        if (health != null)
        {
            health.stop();
        }
        health = new Health(this, cfg);

        defaultProcessingOptions
            = (cfg == null)
                ? 0
                : cfg.getInt(DEFAULT_OPTIONS_PROPERTY_NAME, 0);
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Default videobridge processing options: 0x"
                        + Integer.toHexString(defaultProcessingOptions));
        }

        String shutdownSourcesRegexp
            = (cfg == null)
                ? null
                : cfg.getString(SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME);

        if (!StringUtils.isNullOrEmpty(shutdownSourcesRegexp))
        {
            try
            {
                shutdownSourcePattern = Pattern.compile(shutdownSourcesRegexp);
            }
            catch (PatternSyntaxException exc)
            {
                logger.error(
                   "Error parsing enableGracefulShutdownMode sources reg expr: "
                        + shutdownSourcesRegexp, exc);
            }
        }

        String authorizedSourceRegexp
            = (cfg == null)
                    ? null : cfg.getString(AUTHORIZED_SOURCE_REGEXP_PNAME);
        if (!StringUtils.isNullOrEmpty(authorizedSourceRegexp))
        {
            try
            {
                logger.info(
                    "Authorized source regexp: " + authorizedSourceRegexp);

                setAuthorizedSourceRegExp(authorizedSourceRegexp);
            }
            catch (PatternSyntaxException exc)
            {
                logger.error(
                    "Error parsing authorized sources regexp: "
                        + shutdownSourcesRegexp, exc);
            }
        }
        else
        {
            logger.warn("No authorized source regexp configured. Will accept "
                            + "requests from any source.");
        }

        // <conference>
        ProviderManager.addIQProvider(
                ColibriConferenceIQ.ELEMENT_NAME,
                ColibriConferenceIQ.NAMESPACE,
                new ColibriIQProvider());

        // ICE-UDP <transport>
        ProviderManager.addExtensionProvider(
                IceUdpTransportPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        IceUdpTransportPacketExtension.class));
        // Raw UDP <transport>
        ProviderManager.addExtensionProvider(
                RawUdpTransportPacketExtension.ELEMENT_NAME,
                RawUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        RawUdpTransportPacketExtension.class));

        DefaultPacketExtensionProvider<CandidatePacketExtension>
            candidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(
                    CandidatePacketExtension.class);

        // ICE-UDP <candidate>
        ProviderManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                candidatePacketExtensionProvider);
        // Raw UDP <candidate>
        ProviderManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                RawUdpTransportPacketExtension.NAMESPACE,
                candidatePacketExtensionProvider);
        ProviderManager.addExtensionProvider(
                RtcpmuxPacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        RtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        ProviderManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT_NAME,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        DtlsFingerprintPacketExtension.class));

        // PubSub
        ProviderManager.addIQProvider(
                PubSubElementType.PUBLISH.getElementName(),
                PubSubElementType.PUBLISH.getNamespace().getXmlns(),
                new PubSubProvider());

        // Health-check
        ProviderManager.addIQProvider(
                HealthCheckIQ.ELEMENT_NAME,
                HealthCheckIQ.NAMESPACE,
                new HealthCheckIQProvider());

        this.bundleContext = bundleContext;

        startIce4j(bundleContext, cfg);

        // MediaService may take (non-trivial) time to initialize so initialize
        // it as soon as possible, don't wait to initialize it after an
        // RtpChannel is requested.
        LibJitsi.getMediaService();
    }

    /**
     * Implements the ice4j-related portion of {@link #start(BundleContext)}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Videobridge} is to start
     * @param cfg the {@code ConfigurationService} registered in
     * {@code bundleContext}. Explicitly provided for the sake of performance.
     */
    private void startIce4j(
            BundleContext bundleContext,
            ConfigurationService cfg)
    {
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

            // These properties are moved to ice4j. This is to make sure that we
            // still support the old names.
            String oldPrefix = "org.jitsi.videobridge";
            String newPrefix = "org.ice4j.ice.harvest";
            for (String propertyName : new String[]{
                HarvesterConfiguration.NAT_HARVESTER_LOCAL_ADDRESS,
                HarvesterConfiguration.NAT_HARVESTER_PUBLIC_ADDRESS,
                HarvesterConfiguration.DISABLE_AWS_HARVESTER,
                HarvesterConfiguration.FORCE_AWS_HARVESTER,
                HarvesterConfiguration.STUN_MAPPING_HARVESTER_ADDRESSES})
            {
                String propertyValue = cfg.getString(propertyName);

                if (propertyValue != null)
                {
                    String newPropertyName
                        = newPrefix
                                + propertyName.substring(oldPrefix.length());
                    System.setProperty(newPropertyName, propertyValue);
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

        // Start the initialization of the mapping candidate harvesters.
        // Asynchronous, because the AWS and STUN harvester may take a long
        // time to initialize.
        new Thread(MappingCandidateHarvesters::initialize).start();
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
        try
        {
            if (health != null)
            {
                health.stop();
                health = null;
            }

            ConfigurationService cfg
                = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

            stopIce4j(bundleContext, cfg);
        }
        finally
        {
            videobridgeExpireThread.stop(bundleContext);
            this.bundleContext = null;
        }
    }

    /**
     * Implements the ice4j-related portion of {@link #stop(BundleContext)}.
     *
     * @param bundleContext the {@code BundleContext} in which this
     * {@code Videobridge} is to start
     * @param cfg the {@code ConfigurationService} registered in
     * {@code bundleContext}. Explicitly provided for the sake of performance.
     */
    private void stopIce4j(
        BundleContext bundleContext,
        ConfigurationService cfg)
    {
        // Shut down harvesters.
        IceUdpTransportManager.closeStaticConfiguration(cfg);

        // Clear all system properties that were ice4j properties. This is done
        // to deal with any properties that are conditionally set during
        // initialization. If the conditions have changed upon restart (of the
        // component, rather than the JVM), it would not be enough to "not set"
        // the system property (as it would have survived the restart).
        if (cfg != null)
        {
            List<String> ice4jPropertyNames
                = cfg.getPropertyNamesByPrefix("org.ice4j.", false);

            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
            {
                for (String propertyName : ice4jPropertyNames)
                {
                    System.clearProperty(propertyName);
                }
            }

            // These properties are moved to ice4j. This is to make sure that we
            // still support the old names.
            String oldPrefix = "org.jitsi.videobridge";
            String newPrefix = "org.ice4j.ice.harvest";
            for (String propertyName : new String[]{
                HarvesterConfiguration.NAT_HARVESTER_LOCAL_ADDRESS,
                HarvesterConfiguration.NAT_HARVESTER_PUBLIC_ADDRESS,
                HarvesterConfiguration.DISABLE_AWS_HARVESTER,
                HarvesterConfiguration.FORCE_AWS_HARVESTER,
                HarvesterConfiguration.STUN_MAPPING_HARVESTER_ADDRESSES})
            {
                String propertyValue = cfg.getString(propertyName);

                if (propertyValue != null)
                {
                    String newPropertyName
                        = newPrefix
                        + propertyName.substring(oldPrefix.length());
                    System.clearProperty(newPropertyName);
                }
            }

            System.clearProperty(RtxTransformer.DISABLE_NACK_TERMINATION_PNAME);
        }
    }

    /**
     * Returns an array that contains the total number of conferences (at index
     * 0), channels (at index 1) and video streams (at index 2).
     *
     * The "video streams" count is an estimation of the total number of
     * video streams received or sent. It may not be exactly accurate, because
     * we assume, for example, that all endpoints are sending video. It is a
     * better representation of the level of usage of the bridge than simply
     * the number of channels, because it takes into account the size of each
     * conference.
     *
     * We return these three together to avoid looping through all conferences
     * multiple times when all three values are needed.
     *
     * @return an array that contains the total number of
     * conferences (at index 0), channels (at index 1) and video streams (at
     * index 2).
     */
    public int[] getConferenceChannelAndStreamCount()
    {
        Conference[] conferences = getConferences();
        int conferenceCount = 0, channelCount = 0, streamCount = 0;

        if (conferences != null && conferences.length != 0)
        {
            for (Conference conference : conferences)
            {
                if (conference != null && !conference.isExpired())
                {
                    conferenceCount++;
                    //TODO(brian): for now just assume every endpoint has 3 streams (audio/video/data)
                    streamCount += conference.getEndpointCount() * 3;

//                    for (Content content : conference.getContents())
//                    {
//                        if (content != null && !content.isExpired())
//                        {
//                            int contentChannelCount = content.getChannelCount();
//
//                            channelCount += contentChannelCount;
//                            if (MediaType.VIDEO.equals(content.getMediaType()))
//                            {
//                                streamCount
//                                    += getContentStreamCount(
//                                            content,
//                                            contentChannelCount);
//                            }
//                        }
//                    }
                }
            }
        }

        return new int[]{conferenceCount, channelCount, streamCount};
    }

    /**
     * Returns a short string that contains the total number of
     * conferences/channels/video streams, for the purposes of logging.
     *
     * @return a short string that contains the total number of
     * conferences/channels/video streams, for the purposes of logging.
     */
    String getConferenceCountString()
    {
        int[] metrics = getConferenceChannelAndStreamCount();

        StringBuilder sb = new StringBuilder();
        sb.append("conf_count=").append(metrics[0])
            .append(",ch_count=").append(metrics[1])
            .append(",v_streams=").append(metrics[2]);

        return sb.toString();
    }

    /**
     * Gets the number of video streams for a given <tt>Content</tt>. See the
     * documentation for {@link #getConferenceCountString()}.
     *
     * @param content the content.
     * @param contentChannelCount the number of channels in the content.
     * @return  the number of video streams for a given <tt>Content</tt>. See the
     * documentation for {@link #getConferenceCountString()}.
     */
//    private int getContentStreamCount(
//        Content content,
//        final int contentChannelCount)
//    {
//        return content.getChannels().stream()
//            .filter(
//                c -> c != null && !c.isExpired() && c instanceof VideoChannel)
//            .mapToInt(c ->
//            {
//                int lastN = ((VideoChannel) c).getLastN();
//                int lastNSteams =
//                    lastN == -1
//                        ? contentChannelCount - 1
//                        : Math.min(lastN, contentChannelCount - 1);
//                return lastNSteams + 1; // assume we're receiving 1 stream
//            }).sum();
//    }

    /**
     * Basic statistics/metrics about the videobridge like cumulative/total
     * number of channels created, cumulative/total number of channels failed,
     * etc.
     */
    public static class Statistics
    {
        /**
         * The cumulative/total number of channels created on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalChannels = new AtomicInteger(0);

        /**
         * The cumulative/total number of channels that failed because of no
         * transport activity on this {@link Videobridge}.
         */
        public AtomicInteger totalNoTransportChannels
            = new AtomicInteger(0);

        /**
         * The cumulative/total number of channels that failed because of no
         * payload activity on this {@link Videobridge}.
         */
        public AtomicInteger totalNoPayloadChannels = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences that had all of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalFailedConferences = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences that had some of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalPartiallyFailedConferences
            = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences completed/expired on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCompleted = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences created on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCreated = new AtomicInteger(0);

        /**
         * The total duration in seconds of all completed conferences on this
         * {@link Videobridge}.
         */
        public AtomicLong totalConferenceSeconds = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-controlled
         * (i.e. the sum of the lengths is seconds) on this {@link Videobridge}.
         */
        public AtomicLong totalLossControlledParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-limited
         * on this {@link Videobridge}.
         */
        public AtomicLong totalLossLimitedParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-degraded
         * on this {@link Videobridge}. We chose the unit to be millis because
         * we expect that a lot of our calls spend very few ms (<500) in the
         * lossDegraded state for example, and they might get cut to 0.
         */
        public AtomicLong totalLossDegradedParticipantMs = new AtomicLong();

        /**
         * The total number of ICE transport managers on this videobridge which
         * successfully connected over UDP.
         */
        public AtomicInteger totalUdpTransportManagers = new AtomicInteger();

        /**
         * The total number of ICE transport managers on this videobridge which
         * successfully connected over TCP.
         */
        public AtomicInteger totalTcpTransportManagers = new AtomicInteger();

        /**
         * The total number of messages received from the data channels of
         * the {@link Endpoint}s of this conference.
         */
        public AtomicLong totalDataChannelMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * {@link Endpoint}s of this conference.
         */
        public AtomicLong totalDataChannelMessagesSent = new AtomicLong();

        /**
         * The total number of messages received from the data channels of
         * the {@link Endpoint}s of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * {@link Endpoint}s of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesSent = new AtomicLong();

        /**
         * The total number of bytes received in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets sent in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalPacketsSent = new AtomicLong();

        /**
         * The total number of bytes received in Octo packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesReceivedOcto = new AtomicLong();

        /**
         * The total number of bytes sent in Octo packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesSentOcto = new AtomicLong();

        /**
         * The total number of Octo packets received in conferences on this
         * videobridge. Note that this is only updated when conferences expire.
         */
        public AtomicLong totalPacketsReceivedOcto = new AtomicLong();

        /**
         * The total number of Octo packets sent in conferences on this
         * videobridge. Note that this is only updated when conferences expire.
         */
        public AtomicLong totalPacketsSentOcto = new AtomicLong();
    }
}
