package org.jitsi.videobridge.rest;

import org.jitsi.osgi.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;
import org.osgi.framework.*;

import javax.servlet.http.*;
import java.io.*;

/**
 * Handles requests for "/colibri/debug*".
 *
 * @author Boris Grozev
 */
public class DebugRequestHandler
{
    /**
     * The logger instance used by {@link DebugRequestHandler}.
     */
    private static final Logger logger
            = Logger.getLogger(DebugRequestHandler.class);

    /**
     * The {@link HandlerImpl}.
     */
    private final HandlerImpl handlerImpl;

    /**
     * Initializes a new {@link StatisticsRequestHandler} instance.
     * @param handlerImpl
     */
    DebugRequestHandler(HandlerImpl handlerImpl)
    {
        this.handlerImpl = handlerImpl;
    }

    /**
     * Handles a specific request.
     * @param target the target with "/colibri/" removed
     * @param request
     * @param response
     * @throws IOException
     */
    void handleDebugRequest(
            String target,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException
    {
        if (!"GET".equals(request.getMethod()))
        {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        // The format is:
        // /debug[/conferenceId[/endpointId]]
        JSONObject json;
        String conferenceId;
        String endpointId = null;
        if (target.equals(HandlerImpl.DEBUG))
        {
            conferenceId = null;
            endpointId = null;
        }
        else if (target.startsWith(HandlerImpl.DEBUG + "/"))
        {
            conferenceId = target = target.substring(HandlerImpl.DEBUG.length() + 1);
            if (target.indexOf('/') > 0)
            {
                conferenceId = target.substring(0, target.indexOf('/'));
                endpointId = target.substring(target.indexOf('/') + 1);
            }
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        json = getVideobridge().getDebugState(conferenceId, endpointId);

        response.setStatus(HttpServletResponse.SC_OK);
        json.writeJSONString(response.getWriter());
    }

    /**
     * Gets the {@link Videobridge} instance.
     */
    private Videobridge getVideobridge()
    {
        BundleContext bundleContext = handlerImpl.getBundleContext();

        if (bundleContext != null)
        {
            return ServiceUtils2.getService(bundleContext, Videobridge.class);
        }
        return null;
    }
}
