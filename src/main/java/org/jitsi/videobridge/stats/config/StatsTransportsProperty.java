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
import org.jitsi.utils.collections.*;
import org.jitsi.utils.config.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.config.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

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
public class StatsTransportsProperty extends ReadOnceProperty<List<StatsTransport>>
{
    protected static final String legacyPropKey = "org.jitsi.videobridge.STATISTICS_TRANSPORT";
    protected static final String propKey = "videobridge.stats.transports";
    protected static final Logger logger = new LoggerImpl(StatsTransportsProperty.class.getName());

    /**
     * The name of the property which specifies the name of the PubSub node that
     * will receive the statistics about the Videobridge if PubSub transport is
     * used to send statistics.
     */
    protected static final String PUBSUB_NODE_PNAME
        = "org.jitsi.videobridge.PUBSUB_NODE";

    /**
     * The name of the property which specifies the name of the service that
     * will receive the statistics about the Videobridge if PubSub transport is
     * used to send statistics.
     */
    public static final String PUBSUB_SERVICE_PNAME
        = "org.jitsi.videobridge.PUBSUB_SERVICE";

    /**
     * The value for callstats.io statistics transport.
     */
    public static final String STAT_TRANSPORT_CALLSTATS_IO = "callstats.io";

    /**
     * The value for COLIBRI statistics transport.
     */
    public static final String STAT_TRANSPORT_COLIBRI = "colibri";

    /**
     * The value for PubSub statistics transport.
     */
    public static final String STAT_TRANSPORT_PUBSUB = "pubsub";

    /**
     * The value used to enable the MUC statistics transport.
     */
    public static final String STAT_TRANSPORT_MUC = "muc";

    private static StatsTransportsProperty singleton = new StatsTransportsProperty();

    protected StatsTransportsProperty()
    {
        // Create the retrievers when the instance is created, so they read the config
        // at property creation time (this gives unit tests a chance to inject
        // test configs)
        super(JList.of(createLegacyConfigValueSupplier(), createNewConfigValueSupplier()));
    }

    public static StatsTransportsProperty getInstance()
    {
        return singleton;
    }

    /**
     * Return a stats transport of the given type, null if none exists
     * @param className
     * @return
     */
    public StatsTransport getStatsTransportByType(Class className)
    {
        return get().stream().filter(st -> st.getClass() == className).findFirst().orElse(null);
    }

    /**
     * Creates a supplier which pulls data from a new config object and returns a configuration
     * value of type {@code List<StatsTransport>}
     * @return
     */
    static Supplier<List<StatsTransport>> createNewConfigValueSupplier()
    {
        return new ConfigValueSupplier<>(config ->
            config.getConfigList(propKey).stream()
               .map(NewConfigTransportsFactory::create)
               .collect(Collectors.toList()));
    }

    /**
     * Creates a Supplier which pulls data from a legacy config object and returns a configuration
     * value of type {@code List<StatsTransport>}
     * @return
     */
    static Supplier<List<StatsTransport>> createLegacyConfigValueSupplier()
    {
        return new LegacyConfigValueSupplier<>(config -> {
            String transportNames = config.getString(legacyPropKey);
            return OldConfigTransportsFactory.create(transportNames, config);
        });
    }

    /**
     * Given a new-style {@link Config}, create the appropriate
     * {@link org.jitsi.videobridge.stats.StatsTransport}
     */
    static class NewConfigTransportsFactory
    {
        public static StatsTransport create(Config config)
        {
            //TODO: constants for the prop strings used here (a new property class? only for rnew config?)
            String name = config.getString("name");
            Duration interval = config.hasPath("interval") ? config.getDuration("interval") : null;
            if (STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(name))
            {
                return new CallStatsIOTransport(interval);
            }
            else if (STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(name))
            {
                return new ColibriStatsTransport(interval);
            }
            else if (STAT_TRANSPORT_MUC.equalsIgnoreCase(name))
            {
                return new MucStatsTransport(interval);
            }
            else if (STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(name))
            {
                Jid service;
                try
                {
                    service = JidCreate.from(config.getString("service"));
                }
                catch (XmppStringprepException e)
                {
                    return null;
                }
                String nodeName = config.getString("node");

                return new PubSubStatsTransport(service, nodeName, interval);
            }

            return null;
        }
    }

    static class OldConfigTransportsFactory
    {
        /**
         * A helper function to take a {@code String} with comma-delimited stats transport names
         * and return a list of {@code List<StatsTransport>}
         * @param transportNames a comma-separate list of transport names
         * @param legacyConfig the top-level legacy config object.  We need this because there are
         *                     other properties at the top-level scope needed when creating a
         *                     {@link PubSubStatsTransport} instance
         * @return
         */
        static List<StatsTransport> create(String transportNames, Config legacyConfig)
        {
            List<StatsTransport> statsTransports = new ArrayList<>();

            for (String transportName : transportNames.split(","))
            {
                StatsTransport statsTransport = null;
                if (STAT_TRANSPORT_CALLSTATS_IO.equalsIgnoreCase(transportName))
                {
                    statsTransport = new CallStatsIOTransport(getInterval(legacyConfig, STAT_TRANSPORT_CALLSTATS_IO));
                }
                else if (STAT_TRANSPORT_COLIBRI.equalsIgnoreCase(transportName))
                {
                    statsTransport = new ColibriStatsTransport(getInterval(legacyConfig, STAT_TRANSPORT_COLIBRI));
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
                        statsTransport = new PubSubStatsTransport(service, node, getInterval(legacyConfig, STAT_TRANSPORT_PUBSUB));
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
                    statsTransport = new MucStatsTransport(getInterval(legacyConfig, STAT_TRANSPORT_MUC));
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

    /**
     * Given a  legacy config and the name of a transport, look up a custom interval
     * for that transport and return it; if no custom interval exists return null
     * @param legacyConfig
     * @param transportName
     * @return
     */
    private static Duration getInterval(Config legacyConfig, String transportName)
    {
        String intervalKey = StatsIntervalProperty.legacyPropKey + "." + transportName;
        return legacyConfig.hasPath(intervalKey) ? legacyConfig.getDuration(intervalKey) : null;
    }
}
