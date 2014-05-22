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
import org.json.simple.*;
import org.json.simple.parser.*;
import org.osgi.framework.*;

/**
 *
 * @author Lyubomir Marinov
 */
class HandlerImpl
    extends AbstractHandler
{
    private static final String CONFERENCES = "conferences";

    private static final String DEFAULT_COLIBRI_TARGET = "/colibri/";

    private static final String DEFAULT_JSON_TARGET = null;

    private static final String GET_HTTP_METHOD = "GET";

    private static final String JSON_CONTENT_TYPE = "application/json";

    private static final String JSON_CONTENT_TYPE_WITH_CHARSET
        = JSON_CONTENT_TYPE + ";charset=UTF-8";

    private static final String PATCH_HTTP_METHOD = "PATCH";

    private static final String POST_HTTP_METHOD = "POST";

    /**
     * The <tt>BundleContext</tt> within which this instance is initialized.
     */
    private final BundleContext bundleContext;

    private String colibriTarget;

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
            Conference conference = videobridge.getConference(target, null);

            if (conference == null)
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
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

    public BundleContext getBundleContext()
    {
        return bundleContext;
    }

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
                response.setContentType(JSON_CONTENT_TYPE_WITH_CHARSET);

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

    private void handleColibriJSON(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        if ((target != null) && target.startsWith(CONFERENCES))
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
    }

    private boolean isJSONContentType(String contentType)
    {
        return
            JSON_CONTENT_TYPE.equals(contentType)
                || JSON_CONTENT_TYPE_WITH_CHARSET.equals(contentType);
    }
}
