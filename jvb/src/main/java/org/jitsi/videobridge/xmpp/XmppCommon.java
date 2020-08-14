/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.nlj.stats.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.version.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.iqversion.packet.Version;
import org.json.simple.*;
import org.osgi.framework.*;

import static org.jitsi.videobridge.version.JvbVersionServiceSupplierKt.jvbVersionServiceSingleton;

/**
 * Implements logic for handling incoming IQs represented as Smack {@link IQ}
 * instances. This is used in both {@link ComponentImpl} (which receives IQs
 * from an XMPP component connection) and {@link ClientConnectionImpl} (which
 * receives IQs from an XMPP client connection).
 *
 * @author Boris Grozev
 */
public class XmppCommon
{
    /**
     * The {@link Logger} used by the {@link XmppCommon} class and its
     * instances for logging output.
     */
    private static final Logger logger
        =  new LoggerImpl(XmppCommon.class.getName());

    private static final long[] thresholds
            = new long[] { 5, 50, 100, 1000 };

    private static final DelayStats colibriDelayStats = new DelayStats(thresholds);
    private static final DelayStats healthDelayStats = new DelayStats(thresholds);
    private static final DelayStats versionDelayStats = new DelayStats(thresholds);
    private static final DelayStats responseDelayStats = new DelayStats(thresholds);

    public static JSONObject getStatsJson()
    {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("colibri", colibriDelayStats.toJson());
        jsonObject.put("health", healthDelayStats.toJson());
        jsonObject.put("version", versionDelayStats.toJson());
        jsonObject.put("response", responseDelayStats.toJson());

        return jsonObject;
    }

    static final String[] FEATURES
        = new String[]
        {
            ColibriConferenceIQ.NAMESPACE,
            HealthCheckIQ.NAMESPACE,
            "urn:xmpp:jingle:apps:dtls:0",
            "urn:xmpp:jingle:transports:ice-udp:1",
            Version.NAMESPACE
    };

    /**
     * The <tt>BundleContext</tt> in which this instance has been started as an
     * OSGi bundle.
     */
    private BundleContext bundleContext;

    /**
     * Gets the OSGi <tt>BundleContext</tt> in which this Jabber component is
     * executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which this Jabber component is
     * executing
     */
    BundleContext getBundleContext()
    {
        return bundleContext;
    }

    /**
     * Starts this {@link XmppCommon} in a specific OSGi bundle context.
     */
    void start(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;
    }

    /**
     * Stops this {@link XmppCommon}.
     */
    void stop(BundleContext bundleContext)
    {
        this.bundleContext = null;
    }

    /**
     * Returns the {@link Videobridge} instance that is managing conferences
     * for this component. Returns <tt>null</tt> if no instance is running.
     *
     * @return the videobridge instance, <tt>null</tt> when none is running.
     */
    Videobridge getVideobridge()
    {
        BundleContext bundleContext = getBundleContext();
        return bundleContext != null
            ? ServiceUtils2.getService(bundleContext, Videobridge.class)
            : null;
    }

    /**
     * Returns the <tt>VersionService</tt> used by this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>VersionService</tt> used by this
     * <tt>Videobridge</tt>.
     */
    private VersionService getVersionService()
    {
        BundleContext bundleContext = getBundleContext();
        return bundleContext != null
            ? ServiceUtils2.getService(bundleContext, VersionService.class)
            : null;
    }

    /**
     * Processes an IQ received from one of the XMPP stacks.
     */
    IQ handleIQ(IQ requestIQ)
    {
        if (requestIQ != null)
        {
            logger.debug(() -> "RECV: " + requestIQ.toXML());
        }

        IQ replyIQ = handleIQInternal(requestIQ);

        if (replyIQ != null)
        {
            logger.debug(() -> "SENT: " + replyIQ.toXML());
        }

        return replyIQ;
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
     */
    private IQ handleIQInternal(IQ iq)
    {
        IQ responseIQ = null;

        if (iq != null)
        {
            IQ.Type type = iq.getType();

            if (IQ.Type.get.equals(type) || IQ.Type.set.equals(type))
            {
                responseIQ = handleIQRequest(iq);
                if (responseIQ != null)
                {
                    responseIQ.setFrom(iq.getTo());
                    responseIQ.setStanzaId(iq.getStanzaId());
                    responseIQ.setTo(iq.getFrom());
                }
            }
            else if (IQ.Type.error.equals(type) || IQ.Type.result.equals(type))
            {
                handleIQResponse(iq);
            }
        }
        return responseIQ;
    }

    /**
     * Handles an IQ request (of type 'get' or 'set').
     */
    private IQ handleIQRequest(IQ request)
    {
        Videobridge videobridge = getVideobridge();
        if (videobridge == null)
        {
            return IQUtils.createError(
                request,
                XMPPError.Condition.internal_server_error,
                "No Videobridge service is running");
        }

        IQ response;
        long start = System.currentTimeMillis();
        DelayStats delayStats = null;

        try
        {
            // Requests can be categorized in pieces of Videobridge functionality
            // based on the org.jivesoftware.smack.packet.IQ runtime type (of their
            // child element) and forwarded to specialized Videobridge methods for
            // convenience.
            if (request instanceof Version)
            {
                delayStats = versionDelayStats;
                response = handleVersionIQ((Version) request);
            }
            else if (request instanceof ColibriConferenceIQ)
            {
                delayStats = colibriDelayStats;
                response
                    = videobridge.handleColibriConferenceIQ(
                    (ColibriConferenceIQ) request);
            }
            else if (request instanceof HealthCheckIQ)
            {
                delayStats = healthDelayStats;
                response = videobridge.handleHealthCheckIQ((HealthCheckIQ) request);
            }
            else
            {
                logger.error("Unsupported IQ request " + request.getChildElementName() + " received");
                response = IQUtils.createError(
                    request,
                    XMPPError.Condition.service_unavailable,
                    "Unsupported IQ request " + request.getChildElementName());
            }
        }
        catch (Exception e)
        {
            logger.error("Exception handling IQ request", e);
            response = IQUtils.createError(
                request,
                XMPPError.Condition.internal_server_error,
                e.getMessage()
            );
        }

        if (delayStats != null)
        {
            long delay = System.currentTimeMillis() - start;
            if (delay > 100)
            {
                logger.warn("Took " + delay + " ms to handle IQ: " + request.toXML());
            }
            delayStats.addDelay(delay);
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
    private IQ handleVersionIQ(Version versionRequest)
    {
        VersionService versionService = jvbVersionServiceSingleton.get();

        org.jitsi.utils.version.Version currentVersion
                = versionService.getCurrentVersion();

        if (currentVersion == null)
        {
            return IQ.createErrorResponse(
                versionRequest,
                XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        }

        // send packet
        Version versionResult =
            new Version(
                currentVersion.getApplicationName(),
                currentVersion.toString(),
                System.getProperty("os.name"));

        // to, from and packetId are set by the caller.
        // versionResult.setTo(versionRequest.getFrom());
        // versionResult.setFrom(versionRequest.getTo());
        // versionResult.setPacketID(versionRequest.getPacketID());
        versionResult.setType(IQ.Type.result);

        return versionResult;
    }

    /**
     * Handles a response IQ (of type 'result' or 'error').
     */
    private void handleIQResponse(IQ response)
    {
        // No-op
    }
}
