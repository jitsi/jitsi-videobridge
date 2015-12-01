/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.groovysh;

import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.osgi.framework.*;

/**
 * Created by gp on 20/11/14.
 */
public class GroovyShellState
{
    /**
     * The <tt>Logger</tt> used by the <tt>GroovyShellState</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(GroovyShellState.class);

    public GroovyShellState(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;

        this.initialize();
    }

    // Exposed variables.
    public final BundleContext bundleContext;
    public Videobridge videobridge;
    public Conference conference;
    public ConferenceSpeechActivity speechActivity;

    public void initialize()
    {
        ServiceReference<?>[] refs;

        try
        {
            refs = bundleContext.getServiceReferences(
                    "org.jitsi.videobridge.Videobridge", null);
        }
        catch (InvalidSyntaxException e)
        {
            logger.error(e.getMessage(), e);
            return;
        }

        if (refs == null || refs.length == 0)
        {
            return;
        }

        videobridge
                    = (Videobridge) bundleContext.getService(refs[0]);

        Conference[] conferences = videobridge.getConferences();
        if (conferences != null && conferences.length != 0)
        {
            conference = conferences[0];
        }
    }

    public void switchConference(int idx)
    {
        conference = videobridge.getConferences()[idx];
    }
}
