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

import org.ice4j.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.meet.*;
import org.jitsi.stats.media.*;

import java.util.*;

/**
 * OSGi bundles description for the Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author George Politis
 */
public class BundleConfig
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
            "org/jitsi/videobridge/osgi/ConfigurationActivator"
        },
        {
            "org/jitsi/videobridge/rest/RESTBundleActivator",
        },
        {
            "org/jitsi/videobridge/websocket/WebSocketBundleActivator"
        },
        {
            "org/jitsi/videobridge/VideobridgeBundleActivator"
        },
        {
            "org/jitsi/videobridge/health/Health"
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

        // Sends "consent freshness" check every 3 seconds
        defaults.put(StackProperties.CONSENT_FRESHNESS_INTERVAL, "3000");
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

        // make sure we use the properties files for configuration
        defaults.put(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            true_);

        // callstats-java-sdk
        Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults);

        return defaults;
    }
}
