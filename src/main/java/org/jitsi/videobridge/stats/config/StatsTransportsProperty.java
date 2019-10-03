/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.stats.config;

import com.typesafe.config.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.util.config.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.stream.*;

import static org.jitsi.videobridge.stats.StatsManagerBundleActivator.*;

/**
 * Extract the stats transports from the config file, whichever style it may be
 * (legacy or new).
 *
 * This property involves a complex transformation because there are 2 aspects of it:
 * 1) The config format between the new config and the legacy differs in a non-trivial way
 * 2) Even once the config is parsed, there is a lot of boiler plate to create a
 * {@link StatsTransport} object from the parsed values.
 *
 * We implement this by writing 2 complex retrievers: one for the legacy config
 * and one for the new, both of which handle all the transformations necessary
 * to acquire the end result: a {@code List<StatsTransport>}.
 *
 */
public class StatsTransportsProperty
{
    protected static final String legacyPropKey = "org.jitsi.videobridge.STATISTICS_TRANSPORT";
    protected static final String propKey = "videobridge.stats.transports";
    protected static final Logger logger = new LoggerImpl(StatsTransportsProperty.class.getName());

    static ConfigProperty<List<StatsTransport>> createInstance()
    {
        List<ConfigValueRetriever<List<StatsTransport>>> retrievers = new ArrayList<>();
        // Create the retrievers when the instance is created, so they read the config
        // at property creation time
        retrievers.add(createLegacyConfigRetriever());
        retrievers.add(createNewConfigRetriever());

        return new ReadOnceProperty<>(retrievers, Collections.emptyList());
    }

    /**
     * Creates a retriever which pulls data from a new config object and returns a configuration
     * value of type {@code List<StatsTransport>}
     * @return
     */
    static ConfigValueRetriever<List<StatsTransport>> createNewConfigRetriever()
    {
        return new TypeTransformingConfigValueRetriever.Builder<List<? extends Config>, List<StatsTransport>>()
            .property(propKey)
            .fromConfig(JvbConfig.getConfig())
            .usingGetter(Config::getConfigList)
            .withTransformer(configs -> configs.stream()
                .map(StatsTransportFactory::create)
                .collect(Collectors.toList()))
            .build();
    }

    /**
     * Creates a retriever which pulls data from a legacy config object and returns a configuration
     * value of type {@code List<StatsTransport>}
     * @return
     */
    static ConfigValueRetriever<List<StatsTransport>> createLegacyConfigRetriever()
    {
        return new TypeTransformingConfigValueRetriever.Builder<String, List<StatsTransport>>()
            .property(legacyPropKey)
            .fromConfig(JvbConfig.getLegacyConfig())
            .usingGetter(Config::getString)
            .withTransformer(transportNames -> createStatsTransportsFromOldConfig(transportNames, JvbConfig.getLegacyConfig()))
            .build();
    }

    // We have to take in the legacyConfig here as well to get the other pubsub properties

    /**
     * A helper function to take a {@code String} with comma-delimited stats transport names
     * and return a list of {@code List<StatsTransport>}
     * @param transportNames
     * @param legacyConfig the top-level legacy config object.  We need this because there are
     *                     other properties at the top-level scope needed when creating a
     *                     {@link PubSubStatsTransport} instance
     * @return
     */
    static List<StatsTransport> createStatsTransportsFromOldConfig(String transportNames, Config legacyConfig)
    {
         List<StatsTransport> statsTransports = new ArrayList<>();

         for (String transportName : transportNames.split(","))
         {
             StatsTransport statsTransport = null;
             if (STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(transportName))
             {
                 statsTransport = new CallStatsIOTransport();
             }
             else if (STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(transportName))
             {
                 statsTransport = new ColibriStatsTransport();
             }
             else if (STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(transportName))
             {
                 Jid service;
                 try
                 {
                     service = JidCreate.from(legacyConfig.getString(PUBSUB_SERVICE_PNAME));
                 }
                 catch (XmppStringprepException e)
                 {
                     logger.error("Invalid pubsub service name", e);
                     continue;
                 }

                 String node = legacyConfig.getString(PUBSUB_NODE_PNAME);
                 if(service != null && node != null)
                 {
                     statsTransport = new PubSubStatsTransport(service, node);
                 }
                 else
                 {
                     logger.error(
                             "No configuration properties for PubSub service"
                                     + " and/or node found.");
                     continue;
                 }
             }
             else if (STAT_TRANSPORT_MUC.equalsIgnoreCase(transportName))
             {
                 logger.info("Using a MUC stats transport");
                 statsTransport = new MucStatsTransport();
             }
             else
             {
                 logger.error(
                         "Unknown/unsupported statistics transport: " + transportName);
             }

             if (statsTransport != null)
             {
                 statsTransports.add(statsTransport);
             }
         }

        return statsTransports;
    }
}
