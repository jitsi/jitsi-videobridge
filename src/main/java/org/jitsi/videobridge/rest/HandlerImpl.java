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
     * The HTTP resource which retrieves a JSON representation of the
     * <tt>DominantSpeakerIdentification</tt> of a <tt>Conference</tt> of
     * <tt>Videobridge</tt>.
     */
    private static final String DOMINANT_SPEAKER_IDENTIFICATION
        = "dominant-speaker-identification";

    /**
     * The logger instance used by REST handler.
     */
    private static final Logger logger = new LoggerImpl(HandlerImpl.class.getName());

    /**
     * The HTTP resource which is used to trigger graceful shutdown.
     */
    private static final String SHUTDOWN = "shutdown";

    /**
     * The HTTP resource which lists the JSON representation of the
     * <tt>VideobridgeStatistics</tt>s of <tt>Videobridge</tt>.
     */
    static final String STATISTICS = "stats";

    /**
     * The target for the colibri debug interface.
     */
    static final String DEBUG = "debug";

    /**
     * The HTTP resource which allows control of {@link ClientConnectionImpl}
     * (i.e. adding or removing MUC clients).
     */
    private static final String MUC_CLIENT = "muc-client";

    static
    {
        String colibriTarget = DEFAULT_COLIBRI_TARGET;

        if (!colibriTarget.endsWith("/"))
            colibriTarget += "/";

        COLIBRI_TARGET = colibriTarget;
    }

    /**
     * Indicates if graceful shutdown mode is enabled. If not then
     * SC_SERVICE_UNAVAILABLE status will be returned for {@link #SHUTDOWN}
     * requests.
     */
    private final boolean shutdownEnabled;

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
     * The handler for debug requests.
     */
    private final DebugRequestHandler debugRequestHandler
            = new DebugRequestHandler(this);

    /**
     * Initializes a new {@code HandlerImpl} instance within a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} within which the new
     * instance is to be initialized
     * @param enableShutdown {@code true} if graceful shutdown is to be
     * enabled; otherwise, {@code false}
     * @param enableColibri {@code true} if /colibri/* endpoints are to be
     * enabled; otherwise, {@code false}
     */
    public HandlerImpl(BundleContext bundleContext, boolean enableShutdown,
        boolean enableColibri)
    {
        super(bundleContext);

        shutdownEnabled = enableShutdown;

        if (shutdownEnabled)
            logger.info("Graceful shutdown over REST is enabled");

        colibriEnabled = enableColibri;

        if (colibriEnabled)
            logger.info("Colibri REST endpoints are enabled");
    }

    /**
     * Retrieves a JSON representation of a <tt>Conference</tt> with ID
     * <tt>target</tt> of (the associated) <tt>Videobridge</tt>.
     *
     * @param target the ID of the <tt>Conference</tt> of (the associated)
     * <tt>Videobridge</tt> to represent in JSON format
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void doGetConferenceJSON(
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
            // We allow requests for certain sub-resources of a Conference
            // though such as DominantSpeakerIdentification.
            int conferenceIDEndIndex = target.indexOf('/');
            String conferenceID = target;

            if ((conferenceIDEndIndex > 0)
                    && (conferenceIDEndIndex < target.length() - 1))
            {
                target = target.substring(conferenceIDEndIndex + 1);
                if (DOMINANT_SPEAKER_IDENTIFICATION.equals(target))
                {
                    conferenceID
                        = conferenceID.substring(0, conferenceIDEndIndex);
                }
            }

            Conference conference
                = videobridge.getConference(conferenceID, null);

            if (conference == null)
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            else if (DOMINANT_SPEAKER_IDENTIFICATION.equals(target))
            {
                doGetDominantSpeakerIdentificationJSON(
                        conference,
                        baseRequest,
                        request,
                        response);
            }
            else
            {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

                conference.getShim().describeDeep(conferenceIQ);

                JSONObject conferenceJSONObject
                    = JSONSerializer.serializeConference(conferenceIQ);

                if (conferenceJSONObject == null)
                {
                    response.setStatus(
                            HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
                else
                {
                    response.setStatus(HttpServletResponse.SC_OK);
                    conferenceJSONObject.writeJSONString(response.getWriter());
                }
            }
        }
    }

    /**
     * Lists the <tt>Conference</tt>s of (the associated) <tt>Videobridge</tt>.
     *
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void doGetConferencesJSON(
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
            Conference[] conferences = videobridge.getConferences();
            List<ColibriConferenceIQ> conferenceIQs = new ArrayList<>();

            for (Conference conference : conferences)
            {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

                conferenceIQ.setID(conference.getID());
                conferenceIQs.add(conferenceIQ);
            }

            JSONArray conferencesJSONArray
                = JSONSerializer.serializeConferences(conferenceIQs);

            if (conferencesJSONArray == null)
                conferencesJSONArray = new JSONArray();

            response.setStatus(HttpServletResponse.SC_OK);
            conferencesJSONArray.writeJSONString(response.getWriter());
        }
    }

    /**
     * Retrieves a JSON representation of the
     * <tt>DominantSpeakerIdentification</tt> of a specific <tt>Conference</tt>.
     *
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    private void doGetDominantSpeakerIdentificationJSON(
            Conference conference,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        ConferenceSpeechActivity conferenceSpeechActivity
            = conference.getSpeechActivity();

        if (conferenceSpeechActivity == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else
        {
            JSONObject jsonObject
                = conferenceSpeechActivity
                    .doGetDominantSpeakerIdentificationJSON();

            if (jsonObject != null)
            {
                response.setStatus(HttpServletResponse.SC_OK);
                jsonObject.writeJSONString(response.getWriter());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doGetHealthJSON(
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        beginResponse(/* target */ null, baseRequest, request, response);

        Videobridge videobridge = getVideobridge();

        if (videobridge == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else
        {
            getHealthJSON(videobridge, baseRequest, request, response);
        }

        endResponse(/* target */ null, baseRequest, request, response);
    }

    /**
     * Gets a JSON representation of the health (status) of a specific
     * {@link Videobridge}.
     *
     * @param videobridge the {@code Videobridge} to get the health (status) of
     * in the form of a JSON representation
     * @param baseRequest the original unwrapped {@link Request} object
     * @param request the request either as the {@code Request} object or a
     * wrapper of that request
     * @param response the response either as the {@code Response} object or a
     * wrapper of that response
     * @throws IOException
     * @throws ServletException
     */
    public static void getHealthJSON(
        Videobridge videobridge,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException,
               ServletException
    {
        int status;
        String reason = null;

        try
        {
            // Check if the videobridge is functional
            videobridge.healthCheck();
            status = HttpServletResponse.SC_OK;
        }
        catch (Exception ex)
        {
            if (ex instanceof IOException)
                throw (IOException) ex;
            else if (ex instanceof ServletException)
                throw (ServletException) ex;
            else
            {
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                reason = ex.getMessage();
            }
        }

        if (reason != null)
        {
            response.getOutputStream().println(reason);
        }
        response.setStatus(status);
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
     * Handles a request for a shutdown
     */
    private void doPostShutdownJSON(Request baseRequest,
                                    HttpServletRequest request,
                                    HttpServletResponse response)
        throws IOException
    {
        Videobridge videobridge = getVideobridge();

        if (videobridge == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        if (!RESTUtil.isJSONContentType(request.getContentType()))
        {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            return;
        }

        Object requestJSONObject;
        int status;

        try
        {
            requestJSONObject = new JSONParser().parse(request.getReader());
            if ((requestJSONObject == null)
                || !(requestJSONObject instanceof JSONObject))
            {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
        }
        catch (ParseException pe)
        {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        ShutdownIQ requestShutdownIQ
            = JSONDeserializer.deserializeShutdownIQ(
                    (JSONObject) requestJSONObject);

        if ((requestShutdownIQ == null))
        {
            status = HttpServletResponse.SC_BAD_REQUEST;
        }
        else
        {
            // Fill source address
            String ipAddress = request.getHeader("X-FORWARDED-FOR");
            if (ipAddress == null)
            {
                ipAddress = request.getRemoteAddr();
            }

            requestShutdownIQ.setFrom(JidCreate.from(ipAddress));

            try
            {
                IQ responseIQ
                    = videobridge.handleShutdownIQ(
                            requestShutdownIQ);

                if (IQ.Type.result.equals(responseIQ.getType()))
                {
                    status = HttpServletResponse.SC_OK;
                }
                else
                {
                    status = getHttpStatusCodeForResultIq(responseIQ);
                }
            }
            catch (Exception e)
            {
                logger.error(
                    "Error while trying to handle shutdown request", e);
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            }
        }
        response.setStatus(status);
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

        if (target == null)
        {
            // TODO Auto-generated method stub
        }
        else if (target.startsWith(CONFERENCES))
        {
            target = target.substring(CONFERENCES.length());
            if (target.startsWith("/"))
                target = target.substring(1);

            String requestMethod = request.getMethod();

            if ("".equals(target))
            {
                if (GET_HTTP_METHOD.equals(requestMethod))
                {
                    // List the Conferences of Videobridge.
                    doGetConferencesJSON(baseRequest, request, response);
                }
                else if (POST_HTTP_METHOD.equals(requestMethod))
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
                // The target at this point of the execution is reduced to a
                // String which starts with a Conference ID.
                if (GET_HTTP_METHOD.equals(requestMethod))
                {
                    // Retrieve a representation of a Conference of Videobridge.
                    doGetConferenceJSON(
                        target,
                        baseRequest,
                        request,
                        response);
                }
                else if (PATCH_HTTP_METHOD.equals(requestMethod))
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
        else if (target.startsWith(DEBUG))
        {
            debugRequestHandler.handleDebugRequest(target, request, response);
        }
        else if (target.equals(SHUTDOWN))
        {
            if (!shutdownEnabled)
            {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }

            if (POST_HTTP_METHOD.equals(request.getMethod()))
            {
                doPostShutdownJSON(baseRequest, request, response);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        }
        else if (target.startsWith(MUC_CLIENT + "/"))
        {
            doHandleMucClientRequest(
                target.substring((MUC_CLIENT + "/").length()),
                request,
                response);
        }
    }

    /**
     * Handles a request to /colibri/muc-client/.
     * @param target the target URL with the part before "muc-client/" stripped.
     * @param request the request.
     * @param response the response being prepared.
     */
    private void doHandleMucClientRequest(
        String target,
        HttpServletRequest request,
        HttpServletResponse response)
    {
        if (!POST_HTTP_METHOD.equals(request.getMethod()))
        {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        if (!RESTUtil.isJSONContentType(request.getContentType()))
        {
            response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }

        JSONObject requestJSONObject;
        try
        {
            Object o = new JSONParser().parse(request.getReader());
            if (o instanceof JSONObject)
            {
                requestJSONObject = (JSONObject) o;
            }
            else
            {
                requestJSONObject = null;
            }
        }
        catch (Exception e)
        {
            requestJSONObject = null;
        }

        if (requestJSONObject == null)
        {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        ClientConnectionImpl clientConnectionImpl
            = getService(ClientConnectionImpl.class);
        if (clientConnectionImpl == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        if ("add".equals(target))
        {
            if (clientConnectionImpl.addMucClient(requestJSONObject))
            {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
        else if ("remove".equals(target))
        {
            if (clientConnectionImpl.removeMucClient(requestJSONObject))
            {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
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
        super.handleJSON(target, baseRequest, request, response);

        if (baseRequest.isHandled())
            return; // The super implementation has handled the request.

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
        else
        {
            // Initially, we had VERSION_TARGET equal to /version. But such an
            // HTTP resource could be rewritten by Meet. In order to decrease
            // the risk of rewriting, we moved the VERSION_TARGET to
            // /about/version. For the sake of compatibility though, we are
            // preserving /version.
            String versionTarget = "/version";

            if (versionTarget.equals(target))
            {
                target = target.substring(versionTarget.length());

                handleVersionJSON(target, baseRequest, request, response);
            }
        }
    }
}
