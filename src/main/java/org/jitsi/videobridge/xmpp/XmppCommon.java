/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import org.jitsi.osgi.*;
import org.jitsi.service.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.iqversion.packet.Version;
import org.osgi.framework.*;

/**
 * Implements logic for handling incoming IQs represented as Smack {@link IQ}
 * instances. The logic is meant to be reused between {@link ComponentImpl}
 * and other implementations (e.g. one based on an XMPP user connection).
 *
 * @author Boris Grozev
 */
class XmppCommon
{
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

    void start(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;
    }

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
    private Videobridge getVideobridge()
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
    IQ handleIQ(IQ iq)
        throws Exception
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

    private IQ handleIQRequest(IQ request)
        throws Exception
    {
        // Requests can be categorized in pieces of Videobridge functionality
        // based on the org.jivesoftware.smack.packet.IQ runtime type (of their
        // child element) and forwarded to specialized Videobridge methods for
        // convenience.
        if (request instanceof Version)
        {
            return handleVersionIQ((Version) request);
        }

        Videobridge videobridge = getVideobridge();
        if (videobridge == null)
        {
            return IQUtils.createError(
                request,
                XMPPError.Condition.internal_server_error,
                "No Videobridge service is running");
        }

        IQ response;

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
    private IQ handleVersionIQ(Version versionRequest)
    {
        VersionService versionService = getVersionService();
        if (versionService == null)
        {
            return IQ.createErrorResponse(
                versionRequest,
                XMPPError.getBuilder(XMPPError.Condition.service_unavailable));
        }

        org.jitsi.service.version.Version
            currentVersion = versionService.getCurrentVersion();

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

    private void handleIQResponse(IQ response)
        throws Exception
    {
        Videobridge videobridge = getVideobridge();

        if (videobridge != null)
        {
            videobridge.handleIQResponse(response);
        }
    }
}
