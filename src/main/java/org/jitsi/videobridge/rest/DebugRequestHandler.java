package org.jitsi.videobridge.rest;

import org.jitsi.nlj.transform.node.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;
import org.osgi.framework.*;

import javax.servlet.http.*;
import java.io.*;

import static org.jitsi.videobridge.rest.HandlerImpl.DEBUG;

/**
 * Handles requests for "/colibri/debug*".
 *
 * Note that using this interface MAY disrupt running conferences or even
 * cause a deadlock. It is really meant only for debugging, which is why it is
 * disabled by default. Use at your own risk.
 *
 * Enable it by POSTing to "/colibri/debug/enable".
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
     * We specifically disable the debug interface by default to prevent it
     * being called by mistake.
     */
    private static boolean enabled = false;

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
        if ("POST".equals(request.getMethod()))
        {
            if (target.equals(DEBUG + "/enable"))
            {
                logger.info("Enabling the debug REST interface.");
                enabled = true;
            }
            else if (target.equals(DEBUG + "/disable"))
            {
                logger.info("Disabling the debug REST interface.");
                enabled = false;
            }
            else if (target.equals(DEBUG + "/enable-payload-verification"))
            {
                logger.info("Enabling payload verification.");
                Node.Companion.enablePayloadVerification(true);
            }
            else if (target.equals(DEBUG + "/disable-payload-verification"))
            {
                logger.info("Disabling payload verification.");
                Node.Companion.enablePayloadVerification(false);
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (!"GET".equals(request.getMethod()))
        {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        if (!enabled)
        {
            logger.info("Will not execute a debug request, disabled.");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // The format is:
        // /debug[/conferenceId[/endpointId]]
        JSONObject json;
        String conferenceId;
        String endpointId = null;
        if (target.equals(DEBUG))
        {
            conferenceId = null;
            endpointId = null;
        }
        else if (target.startsWith(DEBUG + "/"))
        {
            conferenceId = target = target.substring(DEBUG.length() + 1);
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

        logger.warn(
            "Executing a debug request! conferenceId="
                    + conferenceId + " endpointId=" + endpointId);
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
