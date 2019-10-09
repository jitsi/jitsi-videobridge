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

package org.jitsi.videobridge.config;

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.util.config.*;

import java.util.concurrent.*;

public class VideobridgeConfig
{
    /**
     * The name of the property which specifies the interval in seconds at which
     * a {@link VideobridgeExpireThread} instance should run.
     */
    public static class ExpireThreadIntervalProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC";
        protected static final String propName = "videobridge.expire-thread-interval";

        protected ExpireThreadIntervalProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> (int)config.getDuration(propName, TimeUnit.SECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static ExpireThreadIntervalProperty expireThreadInterval = new ExpireThreadIntervalProperty();

    public static EnabledApiConfigsProperty enabledApiConfigs = new EnabledApiConfigsProperty();

    public static boolean isApiEnabled(String apiName)
    {
        return enabledApiConfigs.isApiEnabled(apiName);
    }
}
