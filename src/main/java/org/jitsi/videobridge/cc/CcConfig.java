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

package org.jitsi.videobridge.cc;

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;

import java.util.concurrent.*;

public class CcConfig
{

    /**
     * The system property name that holds the interval/period in milliseconds
     * at which {@link BandwidthProbing#run()} is to be invoked.
     */
    public static class PaddingPeriodProperty extends AbstractConfigProperty<Long>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.PADDING_PERIOD_MS";
        protected static final String propName = "videobridge.cc.padding-period";

        protected PaddingPeriodProperty()
        {
            super(new JvbPropertyConfig<Long>()
                .fromLegacyConfig(config -> config.getLong(legacyPropName))
                .fromNewConfig(config -> config.getDuration(propName, TimeUnit.SECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static PaddingPeriodProperty paddingPeriod = new PaddingPeriodProperty();
}
