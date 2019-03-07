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
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.stack.*;
import org.jitsi.eventadmin.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.util.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.pubsub.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.transport.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.provider.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

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
 * @author Brian Baldino
 */
@SuppressWarnings("JavadocReference")
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
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Videobridge.class);

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
     * {@link Conference} IDs in order to minimize busy
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
     * The shim which handles Colibri-related logic for this
     * {@link Videobridge}.
     */
    private final VideobridgeShim shim = new VideobridgeShim(this);

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
            logger.info("create_conf," + conference.getLogPrefix()
                        + "logging=" + enableLogging
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

        // TODO(boris): Implement natively (i.e. loop over endpoints) or move
        // to shim.
        for (Conference conference : getConferences())
        {
            for (ContentShim contentShim : conference.getShim().getContents())
            {
                channelCount += contentShim.getChannelCount();
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
     * TODO: don't expose a weird array API...
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
            handleColibriConferenceIQ(conferenceIQ, defaultProcessingOptions);
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
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ,
                                        int options)
    {
        return shim.handleColibriConferenceIQ(conferenceIQ, options);
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
    public boolean accept(Jid focus, int options)
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
        ConfigurationService cfg = getConfigurationService();

        // The XMPP API is disabled by default.
        return cfg != null &&
            cfg.getBoolean(Videobridge.XMPP_API_PNAME, false);
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
     * conferences and trigger graceful shutdown (if separate pattern has not
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
    {
        this.bundleContext = bundleContext;

        UlimitCheck.printUlimits();

        ConfigurationService cfg = getConfigurationService();

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

        DefaultPacketExtensionProvider<CandidatePacketExtension>
            candidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(
                    CandidatePacketExtension.class);

        // ICE-UDP <candidate>
        ProviderManager.addExtensionProvider(
                CandidatePacketExtension.ELEMENT_NAME,
                IceUdpTransportPacketExtension.NAMESPACE,
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

        startIce4j(bundleContext, cfg);
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
    {
        try
        {
            if (health != null)
            {
                health.stop();
                health = null;
            }

            ConfigurationService cfg = getConfigurationService();
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
        Harvesters.closeStaticConfiguration();

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
                    //TODO(brian, boris): for now just assume every endpoint
                    // streams video and there is no last-n
                    int epCount = conference.getEndpointCount();
                    streamCount += epCount * epCount;

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
    }
}
