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

package org.jitsi.videobridge.health.config;

import org.jitsi.videobridge.util.config.*;
import org.jitsi.videobridge.util.config.retriever.*;

import java.util.concurrent.*;

public class HealthTimeoutProperty
{
    protected static final String legacyPropKey = "org.jitsi.videobridge.health.TIMEOUT";
    protected static final String propKey = "videobridge.health.timeout";

    static ConfigProperty<Integer> createInstance()
    {
        return new ConfigPropertyBuilder<Integer>()
                .usingGetter((config, key) -> (int)config.getDuration(key, TimeUnit.MILLISECONDS))
                .fromConfigs(
                        new DefaultConfigValueRetrieverBuilder<>(propKey),
                        new DefaultLegacyConfigValueRetrieverBuilder<>(legacyPropKey)
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
