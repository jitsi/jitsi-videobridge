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

package org.jitsi.testutils;

import com.typesafe.config.*;
import org.jitsi.videobridge.util.*;

import static org.jitsi.testutils.ConfigUtils.EMPTY_CONFIG;

/**
 * Helper class to make installing new and legacy configurations
 * easier.
 */
public class ConfigSetup
{
    public ConfigSetup withNoLegacyConfig()
    {
        JvbConfig.legacyConfigSupplier = () -> EMPTY_CONFIG;

        return this;
    }

    public ConfigSetup withLegacyConfig(Config legacyConfig)
    {
        JvbConfig.legacyConfigSupplier = () -> legacyConfig;

        return this;
    }

    public ConfigSetup withNoNewConfig()
    {
        JvbConfig.configSupplier = () -> EMPTY_CONFIG;

        return this;
    }

    public ConfigSetup withNewConfig(Config newConfig)
    {
        JvbConfig.configSupplier = () -> newConfig;

        return this;
    }

    public void finishSetup()
    {
        JvbConfig.reloadConfig();
    }
}
