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
import org.ice4j.ice.harvest.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.meet.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.stats.media.*;
import org.jitsi.videobridge.xmpp.*;

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
            "org/jitsi/videobridge/version/VersionActivator"
        },
        {
            // The HTTP/JSON API of Videobridge is started before Videobridge
            // because Jetty needs to bind to its port before the ice4j
            // TCP harvester (started as part of Videobridge) does.
            "org/jitsi/videobridge/rest/RESTBundleActivator",
            "org/jitsi/videobridge/rest/PublicRESTBundleActivator",
            "org/jitsi/videobridge/rest/PublicClearPortRedirectBundleActivator",
            "org/jitsi/videobridge/stats/StatsManagerBundleActivator",
            "org/jitsi/videobridge/EndpointConnectionStatus"
        },
        {
            "org/jitsi/videobridge/VideobridgeBundleActivator"
        },
        {
            "org/jitsi/videobridge/xmpp/ClientConnectionImpl"
        },
        {
            "org/jitsi/videobridge/octo/OctoRelayService"
        }
    };

    @Override
    protected String[][] getBundlesImpl()
    {
        return BUNDLES;
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

        // Configure the receive buffer size for the sockets used for the
        // single-port mode to be 10MB.
        defaults.put(AbstractUdpListener.SO_RCVBUF_PNAME, "10485760");

        // Configure the starting send bitrate to be 2.5Mbps.
        defaults.put(BandwidthEstimatorImpl.START_BITRATE_BPS_PNAME, "2500000");

        // Enable VP8 temporal scalability filtering by default.
        defaults.put(MediaStreamTrackFactory.ENABLE_SVC_PNAME, true_);

        // Enable AST RBE by default.
        defaults.put(RemoteBitrateEstimatorWrapper.ENABLE_AST_RBE_PNAME, true_);

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

        // make sure we use the properties files for configuration
        defaults.put(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            true_);

        // callstats-java-sdk
        Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults);

        return defaults;
    }
}
