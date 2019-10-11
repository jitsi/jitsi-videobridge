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

import com.typesafe.config.*;
import org.jitsi.videobridge.util.*;

import java.time.*;
import java.util.function.*;

/**
 * A helper class which contains specifically where to pull 'legacy' config
 * values from.
 *
 * @param <T> the type of the configuration property's value
 */
public class LegacyConfigValueSupplier<T> extends AbstractConfigValueSupplier<T>
{
    /**
     * This constructor is suitable to use for the simpler case, where
     * the caller merely wants to retrieve a prop named {@code propName}
     * as type {@code propValueType}
     *
     * @param propValueType
     * @param propName
     */
    public LegacyConfigValueSupplier(Class<T> propValueType, String propName)
    {
        super(JvbConfig.getLegacyConfig(), propValueType, propName);
    }

    /**
     * This constructor is for cases which require custom logic (like performing
     * some transformation on the retrieved type)
     *
     * @param customGetter
     */
    public LegacyConfigValueSupplier(Function<Config, T> customGetter)
    {
        super(JvbConfig.getLegacyConfig(), customGetter);
    }
}
