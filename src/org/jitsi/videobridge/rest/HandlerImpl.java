/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rest;

import java.io.*;
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.stats.*;
import org.json.simple.*;
import org.json.simple.parser.*;
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
 */
class HandlerImpl
    extends AbstractHandler
{
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
     * The default suffix/extension of the HTTP resources which provide access
     * to JSON representations of COLIBRI-related entities of
     * <tt>Videobridge</tt>.
     */
    private static final String DEFAULT_JSON_TARGET = null;

    /**
     * The HTTP resource which retrieves a JSON representation of the
     * <tt>DominantSpeakerIdentification</tt> of a <tt>Conference</tt> of
     * <tt>Videobridge</tt>.
     */
    private static final String DOMINANT_SPEAKER_IDENTIFICATION
        = "dominant-speaker-identification";

    /**
     * The HTTP GET method.
     */
    private static final String GET_HTTP_METHOD = "GET";

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
    private static final String PATCH_HTTP_METHOD = "PATCH";

    /**
     * The HTTP POST method.
     */
    private static final String POST_HTTP_METHOD = "POST";

    /**
     * The HTTP resource which list the JSON representation of the
     * <tt>VideobridgeStatistics</tt>s of <tt>Videobridge</tt>.
     */
    private static final String STATISTICS = "stats";

    /**
     * The <tt>BundleContext</tt> within which this instance is initialized.
     */
    private final BundleContext bundleContext;

    /**
     * The base HTTP resource of COLIBRI-related JSON representations of
     * <tt>Videobridge</tt>.
     */
    private String colibriTarget;

    /**
     * The suffix/extension of the HTTP resources which provide access to JSON
     * representations of COLIBRI-related entities of <tt>Videobridge</tt>.
     */
    private String jsonTarget;

    /**
     * Initializes a new <tt>HandlerImpl</tt> instance within a specific
     * <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> within which the new
     * instance is to be initialized
     */
    public HandlerImpl(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;

        colibriTarget = DEFAULT_COLIBRI_TARGET;
        if (!colibriTarget.endsWith("/"))
            colibriTarget += "/";
        jsonTarget = DEFAULT_JSON_TARGET;
        if ((jsonTarget != null) && !jsonTarget.startsWith("."))
            jsonTarget = "." + jsonTarget;
    }

    /**
     * Retrieves a JSON representation of a <tt>Conference</tt> with ID
     * <tt>target</tt> of (the associated) <tt>Videobridge</tt>.
     *
     * @param target the ID of the <tt>Conference</tt> of (the associated)
     * <tt>Videobridge</tt> to represent in JSON format
     * @param baseRequest
     * @param request
     * @param response
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

                conference.describeDeep(conferenceIQ);

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
     * @param baseRequest
     * @param request
     * @param response
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
            List<ColibriConferenceIQ> conferenceIQs
                = new ArrayList<ColibriConferenceIQ>();

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
     * @param baseRequest
     * @param request
     * @param response
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

        if (conferenceSpeechActivity != null)
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
     * Gets a JSON representation of the <tt>VideobridgeStatistics</tt> of (the
     * associated) <tt>Videobridge</tt>.
     *
     * @param baseRequest
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException
     */
    private void doGetStatisticsJSON(
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        BundleContext bundleContext = getBundleContext();

        if (bundleContext != null)
        {
            StatsManager statsManager
                = ServiceUtils.getService(bundleContext, StatsManager.class);

            if (statsManager != null)
            {
                Iterator<Statistics> i
                    = statsManager.getStatistics().iterator();
                Statistics statistics = null;

                if (i.hasNext())
                    statistics = i.next();

                JSONObject statisticsJSONObject
                    = JSONSerializer.serializeStatistics(statistics);
                Writer writer = response.getWriter();

                response.setStatus(HttpServletResponse.SC_OK);
                if (statisticsJSONObject == null)
                    writer.write("null");
                else
                    statisticsJSONObject.writeJSONString(writer);

                return;
            }
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    /**
     * Modifies a <tt>Conference</tt> with ID <tt>target</tt> in (the
     * associated) <tt>Videobridge</tt>.
     *
     * @param target the ID of the <tt>Conference</tt> to modify in (the
     * associated) <tt>Videobridge</tt>
     * @param baseRequest
     * @param request
     * @param response
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
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            else if (isJSONContentType(request.getContentType()))
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
                        status = HttpServletResponse.SC_BAD_REQUEST;
                    }
                }
                catch (ParseException pe)
                {
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
                        status = HttpServletResponse.SC_BAD_REQUEST;
                    }
                    else
                    {
                        ColibriConferenceIQ responseConferenceIQ = null;

                        try
                        {
                            responseConferenceIQ
                                = videobridge.handleColibriConferenceIQ(
                                        requestConferenceIQ,
                                        Videobridge.OPTION_ALLOW_NO_FOCUS);
                        }
                        catch (Exception e)
                        {
                            status
                                = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                        }
                        if (status == 0)
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
                if (status != 0)
                    response.setStatus(status);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            }
        }
    }

    /**
     * Creates a new <tt>Conference</tt> in (the associated)
     * <tt>Videobridge</tt>.
     *
     * @param baseRequest
     * @param request
     * @param response
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
        else if (isJSONContentType(request.getContentType()))
        {
            Object requestJSONObject = null;
            int status = 0;

            try
            {
                requestJSONObject = new JSONParser().parse(request.getReader());
                if ((requestJSONObject == null)
                        || !(requestJSONObject instanceof JSONObject))
                {
                    status = HttpServletResponse.SC_BAD_REQUEST;
                }
            }
            catch (ParseException pe)
            {
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
                        responseConferenceIQ
                            = videobridge.handleColibriConferenceIQ(
                                    requestConferenceIQ,
                                    Videobridge.OPTION_ALLOW_NO_FOCUS);
                    }
                    catch (Exception e)
                    {
                        status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    }
                    if (status == 0)
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
            if (status != 0)
                response.setStatus(status);
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
        }
    }

    /**
     * Gets the <tt>BundleContext</tt> in which this Jetty <tt>Handler</tt> has
     * been started.
     *
     * @return the <tt>BundleContext</tt> in which this Jetty <tt>Handler</tt>
     * has been started or <tt>null</tt> if this Jetty <tt>Handler</tt> has not
     * been started in a <tt>BundleContext</tt>
     */
    public BundleContext getBundleContext()
    {
        return bundleContext;
    }

    /**
     * Gets the <tt>Videobridge</tt> instance available to this Jetty
     * <tt>Handler</tt>.
     *
     * @return the <tt>Videobridge</tt> instance available to this Jetty
     * <tt>Handler</tt> or <tt>null</tt> if no <tt>Videobridge</tt> instance is
     * available to this Jetty <tt>Handler</tt>
     */
    public Videobridge getVideobridge()
    {
        BundleContext bundleContext = getBundleContext();
        Videobridge videobridge;

        if (bundleContext == null)
        {
            videobridge = null;
        }
        else
        {
            videobridge
                = ServiceUtils.getService(bundleContext, Videobridge.class);
        }
        return videobridge;
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
        // The target starts with "/colibri/".
        if ((target != null) && target.startsWith(colibriTarget))
        {
            target = target.substring(colibriTarget.length());

            // The target ends with ".json".
            int jsonTargetLength
                = (jsonTarget == null) ? 0 : jsonTarget.length();

            if ((jsonTargetLength == 0) || target.endsWith(jsonTarget))
            {
                target
                    = target.substring(0, target.length() - jsonTargetLength);

                // All responses to requests for resources under the base
                // /colibri/ are in JSON format.
                response.setContentType(JSON_CONTENT_TYPE_WITH_CHARSET);
                // Cross-origin resource sharing (CORS)
                response.setHeader("Access-Control-Allow-Origin", "*");

                handleColibriJSON(target, baseRequest, request, response);

                if (!baseRequest.isHandled())
                {
                    if (response.getStatus() == 0)
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    baseRequest.setHandled(true);
                }
            }
        }
    }

    /**
     * Handles an HTTP request for a COLIBRI-related resource (e.g.
     * <tt>Conference</tt>, <tt>Content</tt>, and <tt>Channel</tt>) represented
     * in JSON format.
     *
     * @param target
     * @param baseRequest
     * @param request
     * @param response
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
        else if (target.equals(STATISTICS))
        {
            String requestMethod = request.getMethod();

            if (GET_HTTP_METHOD.equals(requestMethod))
            {
                // Get the VideobridgeStatistics of Videobridge.
                doGetStatisticsJSON(baseRequest, request, response);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        }
    }

    /**
     * Determines whether a specific MIME type of HTTP content specifies a JSON
     * representation.
     *
     * @param contentType the MIME type of HTTP content to determine whether it
     * specifies a JSON representation
     * @return <tt>true</tt> if <tt>contentType</tt> stands for a MIME type of
     * HTTP content which specifies a JSON representation; otherwise,
     * <tt>false</tt>
     */
    private boolean isJSONContentType(String contentType)
    {
        return
            JSON_CONTENT_TYPE.equals(contentType)
                || JSON_CONTENT_TYPE_WITH_CHARSET.equals(contentType);
    }
}
