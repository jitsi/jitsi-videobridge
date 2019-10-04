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
import java.util.stream.*;

/**
 * A builder class for creating a {@link ConfigProperty} instance
 * @param <PropValueType> the type of the property value
 */
public class ConfigPropertyBuilder<PropValueType>
{
    protected BiFunction<Config, String, PropValueType> getter = null;
    protected PropValueType defaultValue = null;
    protected List<ConfigValueRetrieverBuilder<PropValueType>> configValueRetrieverBuilders = null;
    protected boolean readOnce = false;

    public ConfigPropertyBuilder<PropValueType> usingGetter(BiFunction<Config, String, PropValueType> getter)
    {
        if (this.getter != null)
        {
            throw new RuntimeException("Getter already set");
        }
        this.getter = getter;

        return this;
    }

    /**
     * We take the builders here, instead of the built type, so that we can pass
     * a 'getter' set at the top level to each of the retrievers (so the same
     * getter doesn't have to be set explicitly on each retriever)
     *
     * @param configValueRetrieverBuilders the {@link ConfigValueRetrieverBuilder}s, in
     *                                     the order in which they should be queried for
     *                                     the config property
     * @return this {@link ConfigPropertyBuilder} instance, for method chaining
     */
    @SafeVarargs
    public final ConfigPropertyBuilder<PropValueType> fromConfigs(
            ConfigValueRetrieverBuilder<PropValueType>... configValueRetrieverBuilders)
    {
        if (this.configValueRetrieverBuilders != null)
        {
            throw new RuntimeException("Configs already set");
        }
        this.configValueRetrieverBuilders = Arrays.asList(configValueRetrieverBuilders);

        return this;
    }

    public ConfigPropertyBuilder<PropValueType> withDefault(PropValueType defaultValue)
    {
        if (this.defaultValue != null)
        {
            throw new RuntimeException("Default value already set");
        }
        this.defaultValue = defaultValue;

        return this;
    }

    public ConfigPropertyBuilder<PropValueType> readOnce()
    {
        readOnce = true;

        return this;
    }

    public ConfigProperty<PropValueType> build()
    {
        List<ConfigValueRetriever<PropValueType>> retrievers = this.configValueRetrieverBuilders
                .stream()
                .map(retrieverBuilder -> {
                    if (this.getter != null)
                    {
                        retrieverBuilder.usingGetter(this.getter);
                    }
                    return retrieverBuilder.build();
                })
                .collect(Collectors.toList());
        if (readOnce)
        {
            return new ReadOnceProperty<>(retrievers, defaultValue);
        }
        return new ReadEveryTimeProperty<>(retrievers, defaultValue);
    }

}
