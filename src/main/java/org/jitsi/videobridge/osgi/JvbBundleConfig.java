/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.meet.*;
import org.jitsi.stats.media.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.*;

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
            "org/jitsi/videobridge/osgi/ConfigurationActivator"
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
        },
        {
            "org/jitsi/videobridge/VideobridgeBundleActivator"
        },
        {
            "org/jitsi/videobridge/xmpp/ClientConnectionImpl"
        },
        {
            "org/jitsi/videobridge/octo/OctoRelayService"
        },
        {
            "org/jitsi/videobridge/EndpointConnectionStatus"
        }
    };

    @Override
    protected String[][] getBundlesImpl()
    {
        return BUNDLES;
    }
    /**
     * The property name of the setting that enables/disables VP8 picture id
     * rewriting.
     */
    //TODO(brian): moved this here when removing SimulcastController, find a home for it.
    public static final String ENABLE_VP8_PICID_REWRITING_PNAME
            = "org.jitsi.videobridge.ENABLE_VP8_PICID_REWRITING";

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

        // Configure the receive buffer size for the sockets used for the
        // single-port mode to be 10MB.
        defaults.put(AbstractUdpListener.SO_RCVBUF_PNAME, "10485760");

        // Configure the starting send bitrate to be 2.5Mbps.
        defaults.put(BandwidthEstimatorImpl.START_BITRATE_BPS_PNAME, "2500000");

        // Enable VP8 temporal scalability filtering by default.
        defaults.put(MediaStreamTrackFactory.ENABLE_SVC_PNAME, true_);

        // Enable AST RBE by default.
        defaults.put(RemoteBitrateEstimatorWrapper.ENABLE_AST_RBE_PNAME, true_);

        // make sure we use the properties files for configuration
        defaults.put(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            true_);

        // callstats-java-sdk
        Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults);

        return defaults;
    }
}
