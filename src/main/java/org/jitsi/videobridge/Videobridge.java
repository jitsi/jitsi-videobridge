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

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;

import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
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
     * {@link #handleColibriConferenceIQ(ColibriConferenceIQ, int)}
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
     * @param name world readable name of the conference to create.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(String focus, String name)
    {
        return this.createConference(focus, name, /* enableLogging */ true);
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
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(
        String focus, String name, boolean enableLogging)
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
                                enableLogging);
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
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ)
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
    public IQ handleColibriConferenceIQ(
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

        if (focus == null && (options & OPTION_ALLOW_NO_FOCUS) == 0)
        {
            return IQUtils.createError(
                    conferenceIQ, XMPPError.Condition.not_authorized);
        }
        else if (authorizedSourcePattern != null
                && (focus == null
                    || !authorizedSourcePattern.matcher(focus).matches()))
        {
            return IQUtils.createError(
                    conferenceIQ, XMPPError.Condition.not_authorized);
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
            {
                if (isShutdownInProgress())
                {
                    return ColibriConferenceIQ
                        .createGracefulShutdownErrorResponse(conferenceIQ);
                }
                else
                {
                    conference
                        = createConference(focus, conferenceIQ.getName());
                    if (conference == null)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.interna_server_error,
                                "Failed to create new conference");
                    }
                }
            }
            else
            {
                conference = getConference(id, focus);
                if (conference == null)
                {
                    return IQUtils.createError(
                            conferenceIQ,
                            XMPPError.Condition.bad_request,
                            "Conference not found for ID: " + id);
                }
            }

            conference.setLastKnownFocus(conferenceIQ.getFrom());
        }

        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);

        responseConferenceIQ.setGracefulShutdown(isShutdownInProgress());

        ColibriConferenceIQ.Recording recordingIQ = conferenceIQ.getRecording();

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
                    ColibriConferenceIQ.Recording.State recState
                        = recordingIQ.getState();
                    boolean recording
                        = conference.setRecording(
                                ColibriConferenceIQ.Recording.State.ON
                                        .equals(recState)
                                    || ColibriConferenceIQ.Recording.State
                                            .PENDING.equals(recState));
                    ColibriConferenceIQ.Recording responseRecordingIq
                            = new ColibriConferenceIQ.Recording(recState);

                    if (recording)
                    {
                        responseRecordingIq.setDirectory(
                                conference.getRecordingDirectory());
                    }
                    responseConferenceIQ.setRecording(responseRecordingIq);
                }
            }
        }

        // TODO(gp) Remove ColibriConferenceIQ.RTCPTerminationStrategy
        for (ColibriConferenceIQ.Content contentIQ
                : conferenceIQ.getContents())
        {
            /*
             * The content element springs into existence whenever it gets
             * mentioned, it does not need explicit creation (in contrast to
             * the conference and channel elements).
             */
            String contentName = contentIQ.getName();
            Content content = conference.getOrCreateContent(contentName);
            if (content == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.interna_server_error,
                        "Failed to create new content for name: "
                            + contentName);
            }

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
                boolean channelCreated = false;
                String transportNamespace
                    = channelIQ.getTransport() != null ?
                        channelIQ.getTransport().getNamespace() : null;

                /*
                 * The presence of the id attribute in the channel
                 * element signals whether a new channel is to be
                 * created or an existing channel is to be modified.
                 */
                if (channelID == null)
                {
                    // What ? Wants to expire a channel while not providing
                    // it's ID ?
                    if (channelExpire == 0)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.bad_request,
                                "Channel expire request for empty ID");
                    }
                    /*
                     * An expire attribute in the channel element with
                     * value equal to zero requests the immediate
                     * expiration of the channel in question.
                     * Consequently, it does not make sense to have it
                     * in a channel allocation request.
                     */
                    channel
                        = content.createRtpChannel(
                                channelBundleId,
                                transportNamespace,
                                channelIQ.isInitiator(),
                                channelIQ.getRTPLevelRelayType());

                    if (channel == null)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.interna_server_error,
                                "Failed to allocate new RTP Channel");
                    }

                    channelCreated = true;
                }
                else
                {
                    // Request for an existing channel.
                    channel
                        = (RtpChannel) content.getChannel(channelID);
                    if (channel == null)
                    {
                        if (channelExpire == 0)
                        {
                            // Channel already expired?
                            continue;
                        }
                        else
                        {
                            return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.bad_request,
                                "No RTP channel found for ID: " + channelID);
                        }
                    }
                }

                if (channelExpire
                        != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
                {
                    if (channelExpire < 0)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.bad_request,
                                "Invalid 'expire' value: " + channelExpire);
                    }

                    channel.setExpire(channelExpire);
                    /*
                     * If the request indicates that it wants the channel
                     * expired and the channel is indeed expired, then
                     * the request is valid and has correctly been acted upon.
                     */
                    if ((channelExpire == 0) && channel.isExpired())
                        continue;
                }

                if (channelCreated && channel instanceof VideoChannel)
                {
                    VideoChannel videoChannel
                        = (VideoChannel)channel;

                    Integer receiveSimulcastLayer =
                        channelIQ.getReceivingSimulcastLayer();

                    videoChannel.setReceiveSimulcastLayer(
                        receiveSimulcastLayer);
                }

                // endpoint
                // The attribute endpoint is optional. If a value is not
                // specified, then the Channel endpoint is to not be changed.
                String endpoint = channelIQ.getEndpoint();

                if (endpoint != null)
                    channel.setEndpoint(endpoint);

                /*
                 * The attribute last-n is optional. If a value is not
                 * specified, then the Channel lastN is to not be changed.
                 */
                Integer lastN = channelIQ.getLastN();

                if (lastN != null)
                    channel.setLastN(lastN);

                Boolean adaptiveLastN = channelIQ.getAdaptiveLastN();
                if (adaptiveLastN != null)
                    channel.setAdaptiveLastN(adaptiveLastN);

                Boolean adaptiveSimulcast = channelIQ.getAdaptiveSimulcast();
                if (adaptiveSimulcast != null)
                    channel.setAdaptiveSimulcast(adaptiveSimulcast);

                // Packet delay - for automated testing purpose only
                Integer packetDelay = channelIQ.getPacketDelay();
                if (packetDelay != null)
                {
                    channel.setPacketDelay(packetDelay);
                }

                /*
                 * XXX The attribute initiator is optional. If a value is not
                 * specified, then the Channel initiator is to be assumed
                 * default or to not be changed.
                 */
                Boolean initiator = channelIQ.isInitiator();

                if (initiator != null)
                {
                    channel.setInitiator(initiator);
                }
                else
                {
                    initiator = true;
                }

                channel.setPayloadTypes(channelIQ.getPayloadTypes());
                channel.setRtpHeaderExtensions(
                        channelIQ.getRtpHeaderExtensions());

                channel.setDirection(channelIQ.getDirection());

                channel.setRtpEncodingParameters(
                    channelIQ.getSources(), channelIQ.getSourceGroups());

                if (channel instanceof VideoChannel)
                {
                    SimulcastMode simulcastMode = channelIQ.getSimulcastMode();

                    if (simulcastMode != null)
                    {
                        ((VideoChannel)channel).setSimulcastMode(simulcastMode);
                    }
                }

                if (channelBundleId != null)
                {
                    TransportManager transportManager
                        = conference.getTransportManager(
                            channelBundleId,
                            true,
                            initiator);

                    transportManager.addChannel(channel);
                }

                channel.setTransport(channelIQ.getTransport());

                /*
                 * Provide (a description of) the current state of the channel
                 * as part of the response.
                 */
                ColibriConferenceIQ.Channel responseChannelIQ
                    = new ColibriConferenceIQ.Channel();

                channel.describe(responseChannelIQ);
                responseContentIQ.addChannel(responseChannelIQ);

                EventAdmin eventAdmin;
                if (channelCreated && (eventAdmin = getEventAdmin()) != null)
                {
                    eventAdmin.sendEvent(EventFactory.channelCreated(channel));
                }

                // XXX we might want to fire more precise events, like
                // sourceGroupsChanged or PayloadTypesChanged, etc.
                content.fireChannelChanged(channel);
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
                    // Expire an expired/non-existing SCTP connection.
                    if (expire == 0)
                    {
                        return IQUtils.createError(
                            conferenceIQ,
                            XMPPError.Condition.bad_request,
                            "SCTP connection expire request for empty ID");
                    }

                    if (endpointID == null)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.bad_request,
                                "No endpoint ID specified for "
                                    + "the new SCTP connection");
                    }

                    Endpoint endpoint
                        = conference.getOrCreateEndpoint(endpointID);
                    if (endpoint == null)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.interna_server_error,
                                "Failed to create new endpoint for ID: "
                                    + endpointID);
                    }
                    else
                    {
                        int sctpPort = sctpConnIq.getPort();

                        sctpConn
                            = content.createSctpConnection(
                                    endpoint,
                                    sctpPort,
                                    channelBundleId,
                                    sctpConnIq.isInitiator());
                        if (sctpConn == null)
                        {
                            return IQUtils.createError(
                                    conferenceIQ,
                                    XMPPError.Condition
                                        .interna_server_error,
                                    "Failed to create new SCTP connection");
                        }
                    }
                }
                else
                {
                    sctpConn = content.getSctpConnection(id);
                    // Expire an expired/non-existing SCTP connection.
                    if (sctpConn == null && expire == 0)
                    {
                        // Nothing to be done here
                        continue;
                    }
                    else if (sctpConn == null)
                    {
                        return IQUtils.createError(
                                conferenceIQ,
                                XMPPError.Condition.bad_request,
                                "No SCTP connection found for ID: " + id);
                    }

                    // expire
                    if (expire
                            != ColibriConferenceIQ.Channel.EXPIRE_NOT_SPECIFIED)
                    {
                        if (expire < 0)
                        {
                            return IQUtils.createError(
                                    conferenceIQ,
                                    XMPPError.Condition.bad_request,
                                    "Invalid 'expire' value: " + expire);
                        }

                        // Check if SCTP connection has expired.
                        if (expire == 0 && sctpConn.isExpired())
                            continue;

                        sctpConn.setExpire(expire);
                    }

                    // endpoint
                    if (endpointID != null)
                        sctpConn.setEndpoint(endpointID);
                }

                // initiator
                Boolean initiator = sctpConnIq.isInitiator();

                if (initiator != null)
                {
                    sctpConn.setInitiator(initiator);
                }
                else
                {
                    initiator = true;
                }

                // transport
                sctpConn.setTransport(sctpConnIq.getTransport());

                if (channelBundleId != null)
                {
                    TransportManager transportManager
                        = conference.getTransportManager(
                                channelBundleId,
                                true,
                                initiator);

                    transportManager.addChannel(sctpConn);
                }

                // response
                ColibriConferenceIQ.SctpConnection responseSctpIq
                    = new ColibriConferenceIQ.SctpConnection();

                sctpConn.describe(responseSctpIq);

                responseContentIQ.addSctpConnection(responseSctpIq);
            }
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
                transportManager.startConnectivityEstablishment(transportIq);
            }
        }

        // Update the endpoint information of Videobridge with the endpoint
        // information of the IQ.
        for (ColibriConferenceIQ.Endpoint colibriEndpoint
                : conferenceIQ.getEndpoints())
        {
            conference.updateEndpoint(colibriEndpoint);
        }

        conference.describeChannelBundles(responseConferenceIQ);

        responseConferenceIQ.setType(
                org.jivesoftware.smack.packet.IQ.Type.RESULT);

        return responseConferenceIQ;
    }

    /**
     * Handles <tt>HealthCheckIQ</tt> by performing health check on this
     * <tt>Videobridge</tt> instance.
     *
     * @param healthCheckIQ the <tt>HealthCheckIQ</tt> to be handled.
     * @return IQ with &quot;result&quot; type if the health check succeeded or
     * IQ with &quot;error&quot; type if something went wrong.
     * {@link XMPPError.Condition#interna_server_error} is returned when the
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
            Health.check(this);

            return IQ.createResultIQ(healthCheckIQ);
        }
        catch (Exception e)
        {
            return
                IQUtils.createError(
                        healthCheckIQ,
                        XMPPError.Condition.interna_server_error,
                        e.getMessage());
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
        String from = shutdownIQ.getFrom();
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
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
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
        throws Exception
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

        return config.getBoolean(Videobridge.XMPP_API_PNAME, false);
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
        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);

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
                    "Error parsing authorized sources reg expr: "
                        + shutdownSourcesRegexp, exc);
            }
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
                new DefaultPacketExtensionProvider<>(
                        IceUdpTransportPacketExtension.class));
        // Raw UDP <transport>
        providerManager.addExtensionProvider(
                RawUdpTransportPacketExtension.ELEMENT_NAME,
                RawUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        RawUdpTransportPacketExtension.class));

        PacketExtensionProvider candidatePacketExtensionProvider
            = new DefaultPacketExtensionProvider<>(
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
                new DefaultPacketExtensionProvider<>(
                        RtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        providerManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT_NAME,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        DtlsFingerprintPacketExtension.class));

        // PubSub
        providerManager.addIQProvider(
                PubSubElementType.PUBLISH.getElementName(),
                PubSubElementType.PUBLISH.getNamespace().getXmlns(),
                new PubSubProvider());

        // Health-check
        providerManager.addIQProvider(
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

            boolean enableLipSync
                = cfg.getBoolean(Endpoint.ENABLE_LIPSYNC_HACK_PNAME, true);
            System.setProperty(VideoChannel.ENABLE_LIPSYNC_HACK_PNAME,
                Boolean.toString(enableLipSync));
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
        new Thread()
        {
            public void run()
            {
                MappingCandidateHarvesters.initialize();
            }
        }.start();
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

                    for (Content content : conference.getContents())
                    {
                        if (content != null && !content.isExpired())
                        {
                            int contentChannelCount = content.getChannelCount();

                            channelCount += contentChannelCount;
                            if (MediaType.VIDEO.equals(content.getMediaType()))
                            {
                                streamCount
                                    += getContentStreamCount(
                                            content,
                                            contentChannelCount);
                            }
                        }
                    }
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
    private int getContentStreamCount(Content content, int contentChannelCount)
    {
        Channel[] channels = content.getChannels();
        int contentStreamCount = 0;
        if (channels != null && channels.length != 0)
        {
            for (Channel channel : channels)
            {
                if (channel != null
                        && !channel.isExpired()
                        && channel instanceof VideoChannel)
                {
                    VideoChannel videoChannel = (VideoChannel) channel;
                    int channelStreams = 1; //assume we're receiving a stream
                    int lastN = videoChannel.getLastN();
                    channelStreams +=
                            lastN == -1
                                    ? contentChannelCount - 1
                                    : Math.min(lastN, contentChannelCount - 1);

                    contentStreamCount += channelStreams;
                }
            }
        }
        return contentStreamCount;
    }

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
    }
}
