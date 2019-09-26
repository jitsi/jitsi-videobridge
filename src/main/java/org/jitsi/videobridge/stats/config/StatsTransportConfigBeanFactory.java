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
import org.jitsi.videobridge.stats.*;

import java.util.*;

/**
 * A bean factory to create {@link StatsTransportConfig} instances
 * from a config.  This differs from the built-in bean factory in
 * that is aware of the available stat classes and will create
 * the correct instance based on the config
 */
public class StatsTransportConfigBeanFactory
{
    public static StatsTransportConfig create(Config transportConfig)
    {
        final String name = transportConfig.getString("name");
        if (StatsManagerBundleActivator.STAT_TRANSPORT_PUBSUB.equalsIgnoreCase(name))
        {
            return ConfigBeanFactory.create(transportConfig, PubSubStatsTransportConfig.class);
        }
        else
        {
            return ConfigBeanFactory.create(transportConfig, StatsTransportConfig.class);
        }
    }
}
