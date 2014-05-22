/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rest;

import java.lang.reflect.*;

import net.java.sip.communicator.util.*;

import org.eclipse.jetty.server.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

/**
 *
 * @author Lyubomir Marinov
 */
public class RESTBundleActivator
    implements BundleActivator
{
    /**
     * The <tt>Logger</tt> used by the <tt>RESTBundleActivator</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RESTBundleActivator.class);

    private Server server;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    bundleContext,
                    ConfigurationService.class);
        boolean start;

        if (cfg == null)
            start = Boolean.getBoolean(Videobridge.REST_API_PNAME);
        else
            start = cfg.getBoolean(Videobridge.REST_API_PNAME, false);
        if (!start)
            return;

        try
        {
            Server server = new Server(8080);

            server.setHandler(new HandlerImpl(bundleContext));
            server.start();

            this.server = server;
        }
        catch (Throwable t)
        {
            logger.error(
                    "Failed to start the REST API of Jitsi Videobridge.",
                    t);
            if (t instanceof Error)
                throw (Error) t;
            else if (t instanceof Exception)
                throw (Exception) t;
            else
                throw new UndeclaredThrowableException(t);
        }
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }
}
