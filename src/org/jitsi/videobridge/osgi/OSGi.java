/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.osgi;

import java.util.*;

import net.java.sip.communicator.impl.osgi.framework.launch.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.service.configuration.*;
import org.osgi.framework.*;
import org.osgi.framework.launch.*;
import org.osgi.framework.startlevel.*;

/**
 * Represents the entry point of the OSGi environment of the Jitsi Videobridge
 * application.
 *
 * @author Lyubomir Marinov
 */
public class OSGi
{
    /**
     * The <tt>BundleContextHolder</tt> which will allow non-OSGi bundles to
     * track the availability of an OSGi <tt>BundleContext</tt>.
     */
    private static final BundleContextHolderImpl bundleContextHolder
        = new BundleContextHolderImpl();

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
                "net/java/sip/communicator/util/dns/DnsUtilActivator"
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
                "org/jitsi/videobridge/stats/StatsManagerBundleActivator"
            },
            {
                "org/jitsi/videobridge/VideobridgeBundleActivator"
            },
            {
                "org/jitsi/videobridge/rest/RESTBundleActivator"
            },
            {
                "org/jitsi/videobridge/osgi/OSGiBundleActivator"
            }
        };

    /**
     * The <tt>org.osgi.framework.launch.Framework</tt> instance which
     * represents the launched OSGi instance.
     */
    private static Framework framework;

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
     * Gets the <tt>Object</tt> used by the <tt>OSGi</tt> class to synchronize
     * the access to its methods.
     *
     * @return the <tt>Object</tt> used by the <tt>OSGi</tt> class to synchronize
     * the access to its methods
     */
    private static Object getSyncRoot()
    {
        return OSGi.class;
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

        for (Map.Entry<String,String> e : defaults.entrySet())
        {
            String key = e.getKey();

            if (System.getProperty(key) == null)
                System.setProperty(key, e.getValue());
        }
    }

    /**
     * Starts the OSGi implementation and the Jitsi Videobridge bundles.
     */
    private static void start()
    {
        /*
         * The documentation of AbstractComponent#start() says that it gets
         * called once for each host that this Component connects to and that
         * extending classes should take care to avoid double initialization.
         */
        if (OSGi.framework != null)
            return;

        FrameworkFactory frameworkFactory = new FrameworkFactoryImpl();
        Map<String, String> configuration = new HashMap<String, String>();

        configuration.put(
                Constants.FRAMEWORK_BEGINNING_STARTLEVEL,
                Integer.toString(BUNDLES.length));

        Framework framework = frameworkFactory.newFramework(configuration);
        boolean started = false;

        try
        {
            framework.init();

            BundleContext bundleContext = framework.getBundleContext();

            for (int startLevelMinus1 = 0;
                    startLevelMinus1 < BUNDLES.length;
                    startLevelMinus1++)
            {
                int startLevel = startLevelMinus1 + 1;

                for (String location : BUNDLES[startLevelMinus1])
                {
                    Bundle bundle = bundleContext.installBundle(location);

                    if (bundle != null)
                    {
                        BundleStartLevel bundleStartLevel
                            = bundle.adapt(BundleStartLevel.class);

                        if (bundleStartLevel != null)
                            bundleStartLevel.setStartLevel(startLevel);
                    }
                }
            }

            OSGi.framework = framework;

            framework.start();
            started = true;
        }
        catch (BundleException be)
        {
            throw new RuntimeException(be);
        }
        finally
        {
            if (!started && (OSGi.framework == framework))
                OSGi.framework = null;
        }
    }

    /**
     * Starts the OSGi implementation and the Jitsi Videobridge bundles.
     *
     * @param bundleActivator
     */
    public static void start(BundleActivator bundleActivator)
    {
        synchronized (getSyncRoot())
        {
            bundleContextHolder.addBundleActivator(bundleActivator);
            if (bundleContextHolder.getBundleActivatorCount() > 0)
                start();
        }
    }

    /**
     * Starts the <tt>OSGi</tt> class in a specific <tt>BundleContext</tt>. The
     * <tt>OSGi</tt> class notifies any registered non-OSGi bundles that a
     * <tt>BundleContext</tt> is available.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>OSGi</tt> class is starting
     */
    static void start(BundleContext bundleContext)
        throws Exception
    {
        bundleContextHolder.start(bundleContext);
    }

    /**
     * Stops the Jitsi Videobridge bundles and the OSGi implementation.
     */
    private static void stop()
    {
        if (framework != null)
        {
            boolean waitForStop = false;

            try
            {
                framework.stop();
                waitForStop = true;
            }
            catch (BundleException be)
            {
                throw new RuntimeException(be);
            }

            if (waitForStop)
            {
                /*
                 * The Framework#stop() method has been successfully invoked.
                 * However, it returns immediately and the very execution occurs
                 * asynchronously. Wait for the asynchronous execution to
                 * complete.
                 */
                waitForStop();
            }

            framework = null;
        }
    }

    /**
     * Stops the Jitsi Videobridge bundles and the OSGi implementation.
     *
     * @param bundleActivator
     */
    public static void stop(BundleActivator bundleActivator)
    {
        synchronized (getSyncRoot())
        {
            bundleContextHolder.removeBundleActivator(bundleActivator);
            if (bundleContextHolder.getBundleActivatorCount() <= 0)
                stop();
        }
    }

    /**
     * Stops the <tt>OSGi</tt> class in a specific <tt>BundleContext</tt>. The
     * <tt>OSGi</tt> class notifies any registered non-OSGi bundles that the
     * <tt>BundleContext</tt> is no longer available.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>OSGi</tt> class is stopping
     */
    static void stop(BundleContext bundleContext)
        throws Exception
    {
        bundleContextHolder.stop(bundleContext);
    }

    /**
     * Waits for {@link #framework} to stop if it has not stopped yet.
     */
    private static void waitForStop()
    {
        boolean interrupted = false;

        try
        {
            while (framework != null)
            {
                int state = framework.getState();

                if ((state == Bundle.ACTIVE)
                        || (state == Bundle.STARTING)
                        || (state == Bundle.STOPPING))
                {
                    try
                    {
                        Thread.sleep(20);
                    }
                    catch (InterruptedException ie)
                    {
                        interrupted = true;
                    }
                    continue;
                }
                else
                {
                    break;
                }
            }
        }
        finally
        {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
