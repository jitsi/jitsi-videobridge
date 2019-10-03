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

package org.jitsi.videobridge.util.config;

import org.jitsi.videobridge.util.*;

/**
 * A helper class to model a simple key retrieval from the legacy config
 * @param <PropValueType>
 */
public class DefaultLegacyConfigValueRetrieverBuilder<PropValueType> extends ConfigValueRetrieverBuilder<PropValueType>
{
    public DefaultLegacyConfigValueRetrieverBuilder(String legacyPropKey)
    {
        this.property(legacyPropKey).fromConfig(JvbConfig.getLegacyConfig());
    }
}
