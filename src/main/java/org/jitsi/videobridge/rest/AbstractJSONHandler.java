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
package org.jitsi.videobridge.rest;

import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;
import net.java.sip.communicator.util.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

/**
 * Implements an abstract Jetty {@code Handler} which provides content in JSON
 * format.
 *
 * @author Lyubomir Marinov
 */
public abstract class AbstractJSONHandler
    extends AbstractHandler
{
    /**
     * The default suffix/extension of the HTTP resources which provide access
     * to JSON representations.
     */
    private static final String DEFAULT_JSON_TARGET = null;

    /**
     * The HTTP GET method.
     */
    protected static final String GET_HTTP_METHOD = "GET";

    /**
     * The MIME type of HTTP content in JSON format.
     */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /**
     * The MIME type of HTTP content in JSON format with a charset.
     */
    private static final String JSON_CONTENT_TYPE_WITH_CHARSET
        = JSON_CONTENT_TYPE + ";charset=UTF-8";

    /**
     * The HTTP PATCH method.
     */
    protected static final String PATCH_HTTP_METHOD = "PATCH";

    /**
     * The HTTP POST method.
     */
    protected static final String POST_HTTP_METHOD = "POST";

    /**
     * Analyzes response IQ returned by {@link Videobridge}'s {@code handle}
     * method(s) and translates XMPP error into HTTP status code.
     *
     * @param responseIQ the IQ that is not {@link ColibriConferenceIQ} from
     * which XMPP error will be extracted.
     * @return HTTP status code
     */
    protected static int getHttpStatusCodeForResultIq(IQ responseIQ)
    {
        String condition = responseIQ.getError().getCondition();

        if (XMPPError.Condition.not_authorized.toString().equals(condition))
        {
            return HttpServletResponse.SC_UNAUTHORIZED;
        }
        else if (XMPPError.Condition.service_unavailable.toString().equals(
                condition))
        {
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        }
        else
        {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
    }

    /**
     * The {@code BundleContext} within which this instance is initialized.
     */
    protected final BundleContext bundleContext;

    /**
     * The suffix/extension of the HTTP resources which provide access to JSON
     * representations.
     */
    private final String jsonTarget;

    /**
     * Initializes a new {@code AbstractJSONHandler} instance within a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} within which the new
     * instance is to be initialized
     */
    protected AbstractJSONHandler(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;

        // jsonTarget
        String jsonTarget = DEFAULT_JSON_TARGET;

        if (jsonTarget != null && !jsonTarget.startsWith("."))
            jsonTarget = "." + jsonTarget;
        this.jsonTarget = jsonTarget;
    }

    /**
     * Begins an {@link HttpServletResponse} the handling of which appears to
     * have chances of success.
     *
     * @param target the target of the request
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     */
    protected void beginResponse(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
    {
        beginResponse(
                target,
                baseRequest,
                request,
                response,
                JSON_CONTENT_TYPE_WITH_CHARSET);
    }

    /**
     * Begins an {@link HttpServletResponse} the handling of which appears to
     * have chances of success.
     *
     * @param target the target of the request
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @param contentType the MIME type of the content to be set on
     * {@code response}
     */
    protected void beginResponse(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response,
            String contentType)
    {
        response.setContentType(contentType);
        // Cross-origin resource sharing (CORS)
        response.setHeader("Access-Control-Allow-Origin", "*");
    }

    /**
     * Ends an {@link HttpServletResponse}.
     *
     * @param target the target of the request
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     */
    protected void endResponse(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
    {
        if (!baseRequest.isHandled())
        {
            if (response.getStatus() == 0)
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            baseRequest.setHandled(true);
        }
    }

    /**
     * Gets the {@code BundleContext} in which this Jetty {@code Handler} has
     * been started.
     *
     * @return the {@code BundleContext} in which this Jetty {@code Handler}
     * has been started or {@code null} if this Jetty {@code Handler} has not
     * been started in a {@code BundleContext}
     */
    public BundleContext getBundleContext()
    {
        return bundleContext;
    }

    /**
     * Gets the OSGi service instance of a specific {@code Class} available to
     * this Jetty {@code Handler}.
     *
     * @param <T> the type of the OSGi service to retrieve
     * @param serviceClass the {@code Class} of the OSGi service to retrieve
     * @return the OSGi service instance of the specified {@code serviceClass}
     * available to this Jetty {@code Handler} or {@code null} if no such
     * service instance is available
     */
    public <T> T getService(Class<T> serviceClass)
    {
        BundleContext bundleContext = getBundleContext();
        T service
            = (bundleContext == null)
                ? null
                : ServiceUtils.getService(bundleContext, serviceClass);

        return service;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        if (target != null)
        {
            // The target ends with ".json".
            int jsonTargetLength
                = (jsonTarget == null) ? 0 : jsonTarget.length();

            if (jsonTargetLength == 0 || target.endsWith(jsonTarget))
            {
                target
                    = target.substring(0, target.length() - jsonTargetLength);

                handleJSON(target, baseRequest, request, response);
            }
        }
    }

    /**
     * Handles a specific HTTP request for JSON content.
     *
     * @param target
     * @param baseRequest
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException 
     */
    protected abstract void handleJSON(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException;

    /**
     * Determines whether a specific MIME type of HTTP content specifies a JSON
     * representation.
     *
     * @param contentType the MIME type of HTTP content to determine whether it
     * specifies a JSON representation
     * @return {@code true} if {@code contentType} stands for a MIME type of
     * HTTP content which specifies a JSON representation; otherwise,
     * {@code false}
     */
    protected boolean isJSONContentType(String contentType)
    {
        return
            JSON_CONTENT_TYPE.equals(contentType)
                || JSON_CONTENT_TYPE_WITH_CHARSET.equals(contentType);
    }
}
