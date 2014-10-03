/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.osgi;

import java.util.*;

import org.ice4j.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.service.configuration.*;
import org.osgi.framework.*;

/**
 * Represents the entry point of the OSGi environment of the Jitsi Videobridge
 * application.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
public class OSGi
{
    /**
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Videobridge.
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    private static final String[][] BUNDLES
        = {
            {
                "net/java/sip/communicator/impl/libjitsi/LibJitsiActivator"
            },
            {
                "net/java/sip/communicator/util/UtilActivator",
                "net/java/sip/communicator/impl/fileaccess/FileAccessActivator"
            },
            {
                "net/java/sip/communicator/impl/configuration/ConfigurationActivator"
            },
            {
                "net/java/sip/communicator/impl/resources/ResourceManagementActivator"
            },
            {
                "net/java/sip/communicator/impl/netaddr/NetaddrActivator"
            },
            {
                "net/java/sip/communicator/impl/packetlogging/PacketLoggingActivator"
            },
            {
                "net/java/sip/communicator/service/gui/internal/GuiServiceActivator"
            },
            {
                "net/java/sip/communicator/service/protocol/media/ProtocolMediaActivator"
            },
            {
                "org/jitsi/videobridge/VideobridgeBundleActivator"
            },
            {
                /*
                 * The HTTP/JSON API of Videobridge is started after and in a
                 * start level separate from Videobridge because the HTTP/JSON
                 * API is useless if Videobridge fails to start.
                 */
                "org/jitsi/videobridge/rest/RESTBundleActivator",
                /*
                 * The statistics/health reports are a non-vital, optional,
                 * additional piece of functionality of the Videobridge.
                 * Consequently, they do not have to be started before the
                 * Videobridge. Besides, they employ OSGi and, hence, they
                 * should be capable of acting as a plug-in. They do not have to
                 * be started before the HTTP/JSON API because the HTTP/JSON API
                 * (1) exposes the vital, non-optional, non-additional pieces of
                 * functionality of the Videobridge and (2) it pulls, does not
                 * push.
                 */
                "org/jitsi/videobridge/stats/StatsManagerBundleActivator"
            },
            {
                /*
                 * Started last and in its own start level because its purpose
                 * is to let the application know that everything OSGi-related
                 * has been started.
                 */
                "org/jitsi/videobridge/osgi/OSGiBundleActivator"
            }
        };

    /**
     * The {@link OSGiLauncher} used to start/stop OSGi system.
     */
    private static OSGiLauncher launcher;

    static
    {
        /*
         * Before we start OSGi and, more specifically, the very Jitsi
         * Videobridge application, set the default values of the System
         * properties which affect the (optional) behavior of the application.
         */
        setSystemPropertyDefaults();
    }

    /**
     * Sets default values on <tt>System</tt> properties which affect the
     * (optional) behavior of the Jitsi Videobridge application and the
     * libraries that it utilizes. Because <tt>ConfigurationServiceImpl</tt>
     * will override <tt>System</tt> property values, the set default
     * <tt>System</tt> property values will not prevent the user from overriding
     * them.
     */
    private static void setSystemPropertyDefaults()
    {
        /*
         * XXX A default System property value specified bellow will eventually
         * be set only if the System property in question does not have a value
         * set yet.
         */

        Map<String,String> defaults = new HashMap<String,String>();
        String true_ = Boolean.toString(true);
        String false_ = Boolean.toString(false);

        /*
         * The design at the time of this writing considers the configuration
         * file read-only (in a read-only directory) and provides only manual
         * editing for it.
         */
        defaults.put(
                ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY,
                true_);

        // Jitsi Videobridge is a relay so it does not need to capture media.
        defaults.put(
                MediaServiceImpl.DISABLE_AUDIO_SUPPORT_PNAME,
                true_);
        defaults.put(
                MediaServiceImpl.DISABLE_VIDEO_SUPPORT_PNAME,
                true_);

        // It makes no sense for Jitsi Videobridge to pace its RTP output.
        defaults.put(
                DeviceConfiguration.PROP_VIDEO_RTP_PACING_THRESHOLD,
                Integer.toString(Integer.MAX_VALUE));

        /*
         * XXX Explicitly support JitMeet by default because is is the primary
         * use case of Jitsi Videobridge right now.
         */
        defaults.put(
                SsrcTransformEngine
                    .DROP_MUTED_AUDIO_SOURCE_IN_REVERSE_TRANSFORM,
                true_);
        defaults.put(SRTPCryptoContext.CHECK_REPLAY_PNAME, false_);

        // In the majority of use-cases the clients which connect to Jitsi
        // Videobridge are not in the same network, so we don't need to
        // advertise link-local addresses.
        defaults.put(StackProperties.DISABLE_LINK_LOCAL_ADDRESSES, true_);

        for (Map.Entry<String,String> e : defaults.entrySet())
        {
            String key = e.getKey();

            if (System.getProperty(key) == null)
                System.setProperty(key, e.getValue());
        }
    }

    /**
     * Starts the OSGi implementation and the Jitsi Videobridge bundles.
     *
     * @param bundleActivator
     */
    public static synchronized void start(BundleActivator bundleActivator)
    {
        if (launcher == null)
        {
            launcher = new OSGiLauncher(BUNDLES);
        }

        launcher.start(bundleActivator);
    }

    /**
     * Stops the Jitsi Videobridge bundles and the OSGi implementation.
     *
     * @param bundleActivator
     */
    public static synchronized void stop(BundleActivator bundleActivator)
    {
        if (launcher != null)
        {
            launcher.stop(bundleActivator);
        }
    }
}
