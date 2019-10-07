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
import org.jitsi.videobridge.util.config.retriever.*;

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
    protected final List<Supplier<T>> configValueSuppliers;

    public ConfigPropertyImpl(List<Supplier<T>> configValueSuppliers)
    {
        this.configValueSuppliers = configValueSuppliers;
    }

    /**
     * Iterate through each of the retrievers, returning a value the first time
     * one is successfully retrieved.  If none are found, return the default value.
     * @return the retrieved value for this configuration property
     */
    protected T doGet()
    {
        for (Supplier<T> configValueSupplier : configValueSuppliers)
        {
            try
            {
                return configValueSupplier.get();
            }
            catch (ConfigException.Missing ignored) { }
        }
        throw new ConfigPropertyNotFoundException(this.getClass().toString());
    }

    public static class ConfigPropertyNotFoundException extends RuntimeException
    {
        public ConfigPropertyNotFoundException(String propName)
        {
            super("Config property " + propName + " not found");
        }
    }
}
