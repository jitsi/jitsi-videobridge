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

import java.io.*;
import java.util.*;
import org.ice4j.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.meet.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * OSGi bundles description for the Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author George Politis
 */
public class JvbBundleConfig
    extends OSGiBundleConfig
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
            "org/jitsi/eventadmin/Activator"
        },
        {
            "org/jitsi/service/libjitsi/LibJitsiActivator"
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
            "org/jitsi/videobridge/eventadmin/callstats/Activator"
        },
        {
            "org/jitsi/videobridge/VideobridgeBundleActivator"
        },
        {
            "org/jitsi/videobridge/version/VersionActivator"
        },
        {
            // The HTTP/JSON API of Videobridge is started after and in a start
            // level separate from Videobridge because the HTTP/JSON API is
            // useless if Videobridge fails to start.
            "org/jitsi/videobridge/rest/RESTBundleActivator",
            // The statistics/health reports are a non-vital, optional,
            // additional piece of functionality of the Videobridge.
            // Consequently, they do not have to be started before the
            // Videobridge. Besides, they employ OSGi and, hence, they should be
            // capable of acting as a plug-in. They do not have to be started
            // before the HTTP/JSON API because the HTTP/JSON API (1) exposes
            // the vital, non-optional, non-additional pieces of functionality
            // of the Videobridge and (2) it pulls, does not push.
            "org/jitsi/videobridge/stats/StatsManagerBundleActivator",
            "org/jitsi/videobridge/EndpointConnectionStatus"
        }
    };

    @Override
    protected String[][] getBundlesImpl()
    {
        return BUNDLES;
    }

    /**
     * Sets the default {@code System} properties on which the
     * callstats-java-sdk library depends.
     *
     * @param defaults the {@code Map} in which the default {@code System}
     * properties on which the callstats-java-sdk library depends are to be
     * defined
     */
    private void getCallStatsJavaSDKSystemPropertyDefaults(
            Map<String, String> defaults)
    {
        getCallStatsJavaSDKSystemPropertyDefaults(
                "log4j2.xml",
                defaults,
                "log4j.configurationFile");
        getCallStatsJavaSDKSystemPropertyDefaults(
                "callstats-java-sdk.properties",
                defaults,
                "callstats.configurationFile");
    }

    /**
     * Sets the default {@code System} properties on which the
     * callstats-java-sdk library depends.
     *
     * @param fileName
     * @param defaults the {@code Map} in which the default {@code System}
     * properties on which the callstats-java-sdk library depends are to be
     * defined
     * @param propertyName
     */
    private void getCallStatsJavaSDKSystemPropertyDefaults(
            String fileName,
            Map<String, String> defaults,
            String propertyName)
    {
        // There are multiple locations in which we may have put the log4j2.xml
        // file. The callstats-java-sdk library defaults to config/log4j2.xml in
        // the current directory. And that is where we keep the file in our
        // source tree so that works when running from source. Unfortunately,
        // such a location may not work for us when we run from the .deb
        // package.

        List<File> files = new ArrayList<>();

        // Look for log4j2.xml in known locations under the current working
        // directory.
        files.add(new File("config", fileName));
        files.add(new File(fileName));

        // Additionally, look for log4j2.xml in the same known locations under
        // SC_HOME_DIR_LOCATION/SC_HOME_DIR_NAME because that is a directory
        // known to Jitsi-derived projects.
        String scHomeDirName
            = System.getProperty(
                    ConfigurationService.PNAME_SC_HOME_DIR_NAME);

        if (!StringUtils.isNullOrEmpty(scHomeDirName))
        {
            String scHomeDirLocation
                = System.getProperty(
                        ConfigurationService.PNAME_SC_HOME_DIR_LOCATION);

            if (!StringUtils.isNullOrEmpty(scHomeDirLocation))
            {
                File dir = new File(scHomeDirLocation, scHomeDirName);

                if (dir.isDirectory())
                {
                    for (int i = 0, end = files.size(); i < end; ++i)
                        files.add(new File(dir, files.get(i).getPath()));
                }
            }
        }

        // Pick the first existing log4j2.xml from the candidates defined above.
        for (File file : files)
        {
            if (file.exists())
            {
                defaults.put(propertyName, file.getAbsolutePath());
                break;
            }
        }
    }

    @Override
    public Map<String, String> getSystemPropertyDefaults()
    {
        // "super" is setting defaults common to all components
        Map<String, String> defaults = super.getSystemPropertyDefaults();

        String true_ = Boolean.toString(true);
        String false_ = Boolean.toString(false);

        // It makes no sense for Jitsi Videobridge to pace its RTP output.
        defaults.put(
                DeviceConfiguration.PROP_VIDEO_RTP_PACING_THRESHOLD,
                Integer.toString(Integer.MAX_VALUE));

        // XXX Explicitly support Jitsi Meet by default because is is the
        // primary use case of Jitsi Videobridge right now.
        defaults.put(
                SsrcTransformEngine
                    .DROP_MUTED_AUDIO_SOURCE_IN_REVERSE_TRANSFORM,
                true_);
        defaults.put(SRTPCryptoContext.CHECK_REPLAY_PNAME, false_);

        // Sends "consent freshness" check every 3 seconds
        defaults.put(
                StackProperties.CONSENT_FRESHNESS_INTERVAL, "3000");
        // Retry every 500ms by setting original and max wait intervals
        defaults.put(
                StackProperties.CONSENT_FRESHNESS_ORIGINAL_WAIT_INTERVAL,
                "500");
        defaults.put(
                StackProperties.CONSENT_FRESHNESS_MAX_WAIT_INTERVAL, "500");
        // Retry max 5 times which will take up to 2500ms, that is before
        // the next "consent freshness" transaction starts
        defaults.put(
                StackProperties.CONSENT_FRESHNESS_MAX_RETRANSMISSIONS, "5");

        // In the majority of use-cases the clients which connect to Jitsi
        // Videobridge are not in the same network, so we don't need to
        // advertise link-local addresses.
        defaults.put(StackProperties.DISABLE_LINK_LOCAL_ADDRESSES, true_);

        // If DTMF handling is enabled, DTMF packets will be read and swallowed.
        // We want them forwarded as normal packets.
        defaults.put(AudioMediaStream.DISABLE_DTMF_HANDLING_PNAME, true_);

        // Enable retransmission requests for video streams.
        defaults.put(VideoMediaStream.REQUEST_RETRANSMISSIONS_PNAME, true_);

        // Disable packet logging.
        defaults.put(
                PacketLoggingConfiguration.PACKET_LOGGING_ENABLED_PROPERTY_NAME,
                false_);

        // This causes RTP/RTCP packets received before the DTLS agent is ready
        // to decrypt them to be dropped. Without it, these packets are passed
        // on without decryption and this leads to:
        // 1. Garbage being sent to the endpoints (or at least something they
        //    cannot decrypt).
        // 2. Failed attempts to parse encrypted RTCP packets (in a compound
        //    packet, the headers of all but the first packet are encrypted).

        // This is currently disabled, because it makes DTLS mandatory, and
        // thus breaks communication with jigasi and jitsi.
        //defaults.put(
        //        "org.jitsi.impl.neomedia.transform.dtls.DtlsPacketTransformer"
        //            + ".dropUnencryptedPkts",
        //        true_);

        // callstats-java-sdk
        getCallStatsJavaSDKSystemPropertyDefaults(defaults);

        return defaults;
    }
}
