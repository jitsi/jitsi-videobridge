/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.osgi;

import java.util.*;

import org.ice4j.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.osgi.framework.*;
import java.io.*;

/**
 * Represents the entry point of the OSGi environment of the Jitsi Videobridge
 * application.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author George Politis
 */
public class OSGi
{
    /**
     * The default filename of the bundles launch sequence file. This class
     * expects to find that file in SC_HOME_DIR_LOCATION/SC_HOME_DIR_NAME.
     */
    private static final String BUNDLES_FILE = "bundles.txt";

    /**
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Videobridge.
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    private static final String[][] BUNDLES
        = {
            {
                "org/jitsi/videobridge/eventadmin/Activator"
            },
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
                "org/jitsi/videobridge/influxdb/Activator"
            },
            {
                "org/jitsi/videobridge/metrics/MetricLoggingActivator"
            },
            {
                "org/jitsi/videobridge/VideobridgeBundleActivator"
            },
            {
                "org/jitsi/videobridge/version/VersionActivator"
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

        // If DTMF handling is enabled, DTMF packets will be read and swallowed.
        // We want them forwarded as normal packets.
        defaults.put(AudioMediaStream.DISABLE_DTMF_HANDLING_PNAME, true_);

        // This causes RTP/RTCP packets received before the DTLS agent is ready
        // to decrypt them to be dropped. Without it, these packets are passed
        // on without decryption and this leads to:
        // 1. Garbage being sent to the endpoints (or at least something they
        //      cannot decrypt).
        // 2. Failed attempts to parse encrypted RTCP packets (in a compound
        //      packet, the headers of all but the first packet are encrypted).

        // This is currently disabled, because it makes DTLS mandatory, and
        // thus breaks communication with jigasi and jitsi.
        //defaults.put("org.jitsi.impl.neomedia.transform.dtls."
        //                     + "DtlsPacketTransformer.dropUnencryptedPkts",
        //             true_);

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
            launcher = new OSGiLauncher(getBundles());
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

    /**
     * Gets the list of the OSGi bundles to launch. It either loads that list
     * from SC_HOME_DIR_LOCATION/SC_HOME_DIR_NAME/BUNDLES_FILE, or, if that file
     * doesn't exist, from the <tt>BUNDLES</tt> variable.
     *
     * @return the list of OSGi bundles to launch.
     */
    private static String[][] getBundles()
    {
        File file = ConfigUtils
                .getAbsoluteFile(BUNDLES_FILE, null);

        if (file == null || !file.exists())
        {
            return BUNDLES;
        }

        List<String[]> lines = new ArrayList<String[]>();

            Scanner input = null;
        try
        {
            input = new Scanner(file);

            while(input.hasNextLine())
            {
                String line = input.nextLine();
                if (!StringUtils.isNullOrEmpty(line))
                {
                    lines.add(new String[] { line.trim() });
                }
            }            
        }
        catch (FileNotFoundException e)
        {
            return BUNDLES;
        }
        finally 
        {
        	if (input != null) 
        	{
        		input.close();
        	}
        }
        
        String[][] bundles = lines.isEmpty()
                ? BUNDLES : lines.toArray(new String[lines.size()][]);

        return bundles;
    }
}
