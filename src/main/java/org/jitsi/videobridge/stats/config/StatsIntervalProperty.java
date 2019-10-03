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
import org.jitsi.videobridge.util.config.*;

public class StatsIntervalProperty
{
    protected static final String legacyPropKey = "org.jitsi.videobridge.STATISTICS_INTERVAL";
    protected static final String propKey = "videobridge.stats.interval";

    static ConfigProperty<Integer> createInstance()
    {
        return new ConfigPropertyBuilder<Integer>()
            .usingGetter(Config::getInt)
            .fromConfigs(
                new DefaultLegacyConfigValueRetrieverBuilder<>(legacyPropKey),
                new DefaultConfigValueRetrieverBuilder<>(propKey)
            )
            .readOnce()
            .build();
    }

    private static ConfigProperty<Integer> singleInstance = createInstance();

    public static ConfigProperty<Integer> getInstance()
    {
        return singleInstance;
    }
}
