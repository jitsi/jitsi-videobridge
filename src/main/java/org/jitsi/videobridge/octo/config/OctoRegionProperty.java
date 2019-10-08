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

package org.jitsi.videobridge.octo.config;

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.stats.config.*;

/**
 * A singleton property representing the octo's region
 */
public class OctoRegionProperty extends ConfigPropertyImpl<String>
{
    protected static final String legacyPropName = "org.jitsi.videobridge.REGION";
    protected static final String propName = "videobridge.octo.region";

    static OctoRegionProperty singleton = new OctoRegionProperty();

    public OctoRegionProperty()
    {
        super(new JvbPropertyConfig<String>()
            .fromLegacyConfig(config -> config.getString(legacyPropName))
            .fromNewConfig(config -> config.getString(propName))
            .readOnce()
            .returnNullIfNotFound()
        );
    }

    public static OctoRegionProperty getInstance()
    {
        return singleton;
    }
}
