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

import java.util.*;
import java.util.function.*;

/**
 * A base helper class for modeling a configuration property.  Contains the
 * code for iterating over multiple {@link ConfigValueRetriever}s for the first
 * one which successfully returns a result; if none contain the property,
 * it returns the default value.
 *
 * @param <T> the type of the property value
 */
public abstract class ConfigPropertyImpl<T> implements ConfigProperty<T>
{
    protected final List<ConfigValueRetriever<T>> configValueRetrievers;
    protected final T defaultValue;

    public ConfigPropertyImpl(List<ConfigValueRetriever<T>> configValueRetrievers, T defaultValue)
    {
        this.configValueRetrievers = configValueRetrievers;
        this.defaultValue = defaultValue;
    }

    protected T doGet()
    {
        for (ConfigValueRetriever<T> configValueRetriever : configValueRetrievers)
        {
            try
            {
                return configValueRetriever.getValue();
            }
            catch (ConfigException.Missing ignored) { }
        }
        return defaultValue;
    }
}
