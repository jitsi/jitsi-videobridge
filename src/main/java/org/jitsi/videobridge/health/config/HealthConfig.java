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

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;

import java.util.concurrent.*;

public class HealthConfig
{
    /**
     * The property which configures the interval between health
     * checks.
     */
    public static class HealthIntervalProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.health.INTERVAL";
        protected static final String propName = "videobridge.health.interval";

        private static HealthIntervalProperty singleton = new HealthIntervalProperty();

        protected HealthIntervalProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> (int)config.getDuration(propName, TimeUnit.MILLISECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }

        public static Integer getValue()
        {
            return singleton.get();
        }
    }

    public static int getInterval()
    {
        return HealthIntervalProperty.getValue();
    }

    /**
     * The property which configures the timeout for health checks.
     */
    public static class HealthTimeoutProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.health.TIMEOUT";
        protected static final String propName = "videobridge.health.timeout";

        private static HealthTimeoutProperty singleton = new HealthTimeoutProperty();

        public HealthTimeoutProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> (int)config.getDuration(propName, TimeUnit.MILLISECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }

        public static Integer getValue()
        {
            return singleton.get();
        }
    }

    public static int getTimeout()
    {
        return HealthTimeoutProperty.getValue();
    }

    /**
     * The the property which makes any failures sticky (i.e. once the
     * bridge becomes unhealthy it will never go back to a healthy state).
     */
    public static class StickyFailuresProperty extends AbstractConfigProperty<Boolean>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.health.STICKY_FAILURES";
        protected static final String propName = "videobridge.health.sticky-failures";

        private static StickyFailuresProperty singleton = new StickyFailuresProperty();

        protected StickyFailuresProperty()
        {
            super(new JvbPropertyConfig<Boolean>()
                .fromLegacyConfig(config -> config.getBoolean(legacyPropName))
                .fromNewConfig(config -> config.getBoolean(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }

        public static boolean getValue()
        {
            return singleton.get();
        }
    }

    public static boolean stickyFailures()
    {
        return StickyFailuresProperty.getValue();
    }
}
