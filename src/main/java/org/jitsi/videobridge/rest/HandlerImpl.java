/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.*;
import org.jitsi.rest.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.json.simple.parser.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;

/**
 * Implements a Jetty <tt>Handler</tt> which is to provide the HTTP interface of
 * the JSON public API of <tt>Videobridge</tt>.
 * <p>
 * The REST API of Jitsi Videobridge serves resources with
 * <tt>Content-Type: application/json</tt> under the base target
 * <tt>/colibri</tt>:
 * <table>
 *   <thead>
 *     <tr>
 *       <th>HTTP Method</th>
 *       <th>Resource</th>
 *       <th>Response</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>GET</td>
 *       <td>/colibri/conferences</td>
 *       <td>
 *         200 OK with a JSON array/list of JSON objects which represent
 *         conferences with <tt>id</tt> only. For example:
 * <code>
 * [
 *   { &quot;id&quot; : &quot;a1b2c3&quot; },
 *   { &quot;id&quot; : &quot;d4e5f6&quot; }
 * ]
 * </code>
 *       </td>
 *     </tr>
 *     <tr>
 *       <td>POST</td>
 *       <td>/colibri/conferences</td>
 *       <td>
 *         <p>
 *         200 OK with a JSON object which represents the created conference if
 *         the request was with <tt>Content-Type: application/json</tt> and was
 *         a JSON object which represented a conference without <tt>id</tt> and,
 *         optionally, with contents and channels without <tt>id</tt>s. For
 *         example, a request could look like:
 *         </p>
 * <code>
 * {
 *   &quot;contents&quot; :
 *     [
 *       {
 *         &quot;name&quot; : &quot;audio&quot;,
 *         &quot;channels&quot; : [ { &quot;expire&quot; : 60 } ]
 *       },
 *       {
 *         &quot;name&quot; : &quot;video&quot;,
 *         &quot;channels&quot; : [ { &quot;expire&quot; : 60 } ]
 *       }
 *     ]
 * }
 * </code>
 *         <p>
 *         The respective response could look like:
 *         </p>
 * <code>
 * {
 *   &quot;id&quot; : &quot;conference1&quot;,
 *   &quot;contents&quot; :
 *     [
 *       {
 *         &quot;name&quot; : &quot;audio&quot;,
 *         &quot;channels&quot; :
 *           [
 *             { &quot;id&quot; : &quot;channelA&quot; },
 *             { &quot;expire&quot; : 60 },
 *             { &quot;rtp-level-relay-type&quot; : &quot;translator&quot; }
 *           ]
 *       },
 *       {
 *         &quot;name&quot; : &quot;video&quot;,
 *         &quot;channels&quot; :
 *           [
 *             { &quot;id&quot; : &quot;channelV&quot; },
 *             { &quot;expire&quot; : 60 },
 *             { &quot;rtp-level-relay-type&quot; : &quot;translator&quot; }
 *           ]
 *       }
 *     ]
 * }
 * </code>
 *       </td>
 *     </tr>
 *     <tr>
 *       <td>GET</td>
 *       <td>/colibri/conferences/{id}</td>
 *       <td>
 *         200 OK with a JSON object which represents the conference with the
 *         specified <tt>id</tt>. For example:
 * <code>
 * {
 *   &quot;id&quot; : &quot;{id}&quot;,
 *   &quot;contents&quot; :
 *     [
 *       {
 *         &quot;name&quot; : &quot;audio&quot;,
 *         &quot;channels&quot; :
 *           [
 *             { &quot;id&quot; : &quot;channelA&quot; },
 *             { &quot;expire&quot; : 60 },
 *             { &quot;rtp-level-relay-type&quot; : &quot;translator&quot; }
 *           ]
 *       },
 *       {
 *         &quot;name&quot; : &quot;video&quot;,
 *         &quot;channels&quot; :
 *           [
 *             { &quot;id&quot; : &quot;channelV&quot; },
 *             { &quot;expire&quot; : 60 },
 *             { &quot;rtp-level-relay-type&quot; : &quot;translator&quot; }
 *           ]
 *       }
 *     ]
 * }
 * </code>
 *       </td>
 *     </tr>
 *     <tr>
 *       <td>PATCH</td>
 *       <td>/colibri/conferences/{id}</td>
 *       <td>
 *         <p>
 *         200 OK with a JSON object which represents the modified conference if
 *         the request was with <tt>Content-Type: application/json</tt> and was
 *         a JSON object which represented a conference without <tt>id</tt> or
 *         with the specified <tt>id</tt> and, optionally, with contents and
 *         channels with or without <tt>id</tt>s.
 *         </p>
 *       </td>
 *     </tr>
 *   </tbody>
 * </table>
 * </p>
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
class HandlerImpl
    extends AbstractJSONHandler
{
    /**
     * The base HTTP resource of COLIBRI-related JSON representations of
     * {@code Videobridge}.
     */
    static final String COLIBRI_TARGET;

    /**
     * The HTTP resource which lists the JSON representation of the
     * <tt>Conference</tt>s of <tt>Videobridge</tt>.
     */
    private static final String CONFERENCES = "conferences";

    /**
     * The default base HTTP resource of COLIBRI-related JSON representations of
     * <tt>Videobridge</tt>.
     */
    private static final String DEFAULT_COLIBRI_TARGET = "/colibri/";

    /**
     * The logger instance used by REST handler.
     */
    private static final Logger logger = new LoggerImpl(HandlerImpl.class.getName());

    /**
     * The HTTP resource which lists the JSON representation of the
     * <tt>VideobridgeStatistics</tt>s of <tt>Videobridge</tt>.
     */
    static final String STATISTICS = "stats";

    static
    {
        String colibriTarget = DEFAULT_COLIBRI_TARGET;

        if (!colibriTarget.endsWith("/"))
            colibriTarget += "/";

        COLIBRI_TARGET = colibriTarget;
    }

    /**
     * Indicates if /colibri/* REST endpoints are enabled. If not then
     * SC_SERVICE_UNAVAILABLE status will be returned for
     * {@link #COLIBRI_TARGET} requests.
     */
    private final boolean colibriEnabled;

    /**
     * The handler for statistics requests.
     */
    private final StatisticsRequestHandler statisticsRequestHandler
            = new StatisticsRequestHandler(this);

    /**
     * Initializes a new {@code HandlerImpl} instance within a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} within which the new
     * instance is to be initialized
     * @param enableShutdown {@code true} if graceful shutdown is to be
     * enabled; otherwise, {@code false}.  This field is now deprecated, as this
     * logic is handled by {@link org.jitsi.videobridge.rest.shutdown.ShutdownApp}.
     * @param enableColibri {@code true} if /colibri/* endpoints are to be
     * enabled; otherwise, {@code false}
     */
    public HandlerImpl(
            BundleContext bundleContext,
            @Deprecated boolean enableShutdown,
            boolean enableColibri)
    {
        super(bundleContext);

        colibriEnabled = enableColibri;

        if (colibriEnabled)
            logger.info("Colibri REST endpoints are enabled");
    }

    /**
     * Modifies a <tt>Conference</tt> with ID <tt>target</tt> in (the
     * associated) <tt>Videobridge</tt>.
     *
     * @param target the ID of the <tt>Conference</tt> to modify in (the
     * associated) <tt>Videobridge</tt>
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void doPatchConferenceJSON(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        Videobridge videobridge = getVideobridge();

        if (videobridge == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else
        {
            Conference conference = videobridge.getConference(target, null);

            if (conference == null)
            {
                String message = String.format("Failed to patch" +
                        " conference: %s, conference not found", target);
                logger.error(message);
                response.getOutputStream().println(message);

                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            else if (RESTUtil.isJSONContentType(request.getContentType()))
            {
                Object requestJSONObject = null;
                int status = 0;

                try
                {
                    requestJSONObject
                        = new JSONParser().parse(request.getReader());
                    if ((requestJSONObject == null)
                            || !(requestJSONObject instanceof JSONObject))
                    {
                        String message = String.format("Failed to patch" +
                                        " conference: %s, could not parse" +
                                        " JSON", target);
                        logger.error(message);
                        response.getOutputStream().println(message);

                        status = HttpServletResponse.SC_BAD_REQUEST;
                    }
                }
                catch (ParseException pe)
                {
                    String message = String.format("Failed to patch" +
                                    " conference: %s, could not parse" +
                            " JSON message: %s", target,
                            pe.getMessage());
                    logger.error(message);
                    response.getOutputStream().println(message);

                    status = HttpServletResponse.SC_BAD_REQUEST;
                }
                if (status == 0)
                {
                    ColibriConferenceIQ requestConferenceIQ
                        = JSONDeserializer.deserializeConference(
                                (JSONObject) requestJSONObject);

                    if ((requestConferenceIQ == null)
                            || ((requestConferenceIQ.getID() != null)
                                    && !requestConferenceIQ.getID().equals(
                                            conference.getID())))
                    {
                        String message = String.format("Failed to patch" +
                                        " conference: %s, conference JSON" +
                                " has invalid conference id", target);
                        logger.error(message);
                        response.getOutputStream().println(message);

                        status = HttpServletResponse.SC_BAD_REQUEST;
                    }
                    else
                    {
                        ColibriConferenceIQ responseConferenceIQ = null;

                        try
                        {
                            IQ responseIQ
                                    = videobridge.handleColibriConferenceIQ(
                                    requestConferenceIQ,
                                    Videobridge.OPTION_ALLOW_NO_FOCUS);

                            if (responseIQ instanceof ColibriConferenceIQ)
                            {
                                responseConferenceIQ
                                    = (ColibriConferenceIQ) responseIQ;
                            }
                            else
                            {
                                status = getHttpStatusCodeForResultIq(responseIQ);
                            }
                            if(responseIQ.getError() != null)
                            {
                                String message = String.format("Failed to patch" +
                                        " conference: %s, message: %s", target,
                                        responseIQ.getError().getDescriptiveText());
                                logger.error(message);
                                response.getOutputStream().println(message);
                            }

                        }
                        catch (Exception e)
                        {
                            String message = String.format("Failed to patch" +
                                            " conference: %s, message: %s", target,
                                    e.getMessage());
                            logger.error(message);
                            response.getOutputStream().println(message);

                            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                        }
                        if (status == 0 && responseConferenceIQ != null)
                        {
                            JSONObject responseJSONObject
                                = JSONSerializer.serializeConference(
                                        responseConferenceIQ);

                            if (responseJSONObject == null)
                                responseJSONObject = new JSONObject();

                            response.setStatus(HttpServletResponse.SC_OK);
                            responseJSONObject.writeJSONString(
                                    response.getWriter());
                        }
                    }
                }
                else
                {
                    response.setStatus(status);
                }
            }
            else
            {
                String message = String.format("Failed to patch" +
                                " conference: %s, invalid content type, must be %s",
                        target, RESTUtil.JSON_CONTENT_TYPE);
                logger.error(message);
                response.getOutputStream().println(message);

                response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            }
        }
    }

    /**
     * Creates a new <tt>Conference</tt> in (the associated)
     * <tt>Videobridge</tt>.
     *
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void doPostConferencesJSON(
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        Videobridge videobridge = getVideobridge();

        if (videobridge == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else if (RESTUtil.isJSONContentType(request.getContentType()))
        {
            Object requestJSONObject = null;
            int status = 0;

            try
            {
                requestJSONObject = new JSONParser().parse(request.getReader());
                if ((requestJSONObject == null)
                        || !(requestJSONObject instanceof JSONObject))
                {
                    String message = "Failed to create conference, could" +
                            " not parse JSON";
                    logger.error(message);
                    response.getOutputStream().println(message);

                    status = HttpServletResponse.SC_BAD_REQUEST;
                }
            }
            catch (ParseException pe)
            {
                String message = String.format("Failed to create conference," +
                        " could not parse JSON, message: %s", pe.getMessage());
                logger.error(message);
                response.getOutputStream().println(message);

                status = HttpServletResponse.SC_BAD_REQUEST;
            }
            if (status == 0)
            {
                ColibriConferenceIQ requestConferenceIQ
                    = JSONDeserializer.deserializeConference(
                            (JSONObject) requestJSONObject);

                if ((requestConferenceIQ == null)
                        || (requestConferenceIQ.getID() != null))
                {
                    status = HttpServletResponse.SC_BAD_REQUEST;
                }
                else
                {
                    ColibriConferenceIQ responseConferenceIQ = null;

                    try
                    {
                        IQ responseIQ
                                = videobridge.handleColibriConferenceIQ(
                                requestConferenceIQ,
                                Videobridge.OPTION_ALLOW_NO_FOCUS);

                        if (responseIQ instanceof ColibriConferenceIQ)
                        {
                            responseConferenceIQ
                                = (ColibriConferenceIQ) responseIQ;
                        }
                        else
                        {
                            status = getHttpStatusCodeForResultIq(responseIQ);
                        }
                        if(responseIQ.getError() != null)
                        {
                            String message = String.format("Failed to create " +
                                    "conference, message: %s",responseIQ
                                    .getError().getDescriptiveText());
                            logger.error(message);
                            response.getOutputStream().println(message);

                        }
                    }
                    catch (Exception e)
                    {
                        status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    }
                    if (status == 0 && responseConferenceIQ != null)
                    {
                        JSONObject responseJSONObject
                            = JSONSerializer.serializeConference(
                                    responseConferenceIQ);

                        if (responseJSONObject == null)
                            responseJSONObject = new JSONObject();

                        response.setStatus(HttpServletResponse.SC_OK);
                        responseJSONObject.writeJSONString(
                                response.getWriter());
                    }
                }
            }
            else
            {
                response.setStatus(status);
            }
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
        }
    }

    /**
     * Gets the {@code Videobridge} instance available to this Jetty
     * {@code Handler}.
     *
     * @return the {@code Videobridge} instance available to this Jetty
     * {@code Handler} or {@code null} if no {@code Videobridge} instance is
     * available to this Jetty {@code Handler}
     */
    public Videobridge getVideobridge()
    {
        return getService(Videobridge.class);
    }

    /**
     * Handles an HTTP request for a COLIBRI-related resource (e.g.
     * <tt>Conference</tt>, <tt>Content</tt>, and <tt>Channel</tt>) represented
     * in JSON format.
     *
     * @param target the target of the request
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void handleColibriJSON(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        if (!colibriEnabled)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        String requestMethod = request.getMethod();
        if (target == null)
        {
            // TODO Auto-generated method stub
        }
        else if (target.startsWith(CONFERENCES) &&
            (POST_HTTP_METHOD.equals(requestMethod) || PATCH_HTTP_METHOD.equals(requestMethod)))
        {
            target = target.substring(CONFERENCES.length());
            if (target.startsWith("/"))
                target = target.substring(1);


            if ("".equals(target))
            {
                if (POST_HTTP_METHOD.equals(requestMethod))
                {
                    // Create a new Conference in Videobridge.
                    doPostConferencesJSON(baseRequest, request, response);
                }
                else
                {
                    response.setStatus(
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            }
            else
            {
                if (PATCH_HTTP_METHOD.equals(requestMethod))
                {
                    // Modify a Conference of Videobridge.
                    doPatchConferenceJSON(
                        target,
                        baseRequest,
                        request,
                        response);
                }
                else
                {
                    response.setStatus(
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            }
        }
        else if (target.startsWith(STATISTICS))
        {
            statisticsRequestHandler.handleStatsRequest(
                    target, request, response);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void handleJSON(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        // NOTE(brian): we intentionally no longer call the parent handleJSON here, as
        // all of the endpoints it was handling (health and version) are now replaced
        // by logic here in the bridge

        // The target starts with "/colibri/".
        if (target.startsWith(COLIBRI_TARGET))
        {
            target = target.substring(COLIBRI_TARGET.length());

            // FIXME In order to not invoke beginResponse() and endResponse() in
            // each and every one of the methods to which handleColibriJSON()
            // delegates/forwards, we will invoke them here. However, we do not
            // know whether handleColibriJSON() will actually handle the
            // request. As a workaround we will mark the response with a status
            // code that we know handleColibriJSON() does not utilize (at the
            // time of this writing) and we will later recognize whether
            // handleColibriJSON() has handled the request by checking whether
            // the response is still marked with the unused status code.
            int oldResponseStatus = response.getStatus();

            response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);

            // All responses to requests for resources under the base /colibri/
            // are in JSON format.
            beginResponse(target, baseRequest, request, response);
            handleColibriJSON(target, baseRequest, request, response);

            int newResponseStatus = response.getStatus();

            if (newResponseStatus == HttpServletResponse.SC_NOT_IMPLEMENTED)
            {
                // Restore the status code which was in place before we replaced
                // it with our workaround.
                response.setStatus(oldResponseStatus);
            }
            else
            {
                // It looks like handleColibriJSON() indeed handled the request.
                endResponse(target, baseRequest, request, response);
            }
        }
    }
}
