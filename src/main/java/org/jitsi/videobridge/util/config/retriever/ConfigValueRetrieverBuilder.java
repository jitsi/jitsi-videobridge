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

package org.jitsi.videobridge.util.config.retriever;

import com.typesafe.config.*;

import java.util.function.*;

public class ConfigValueRetrieverBuilder<PropValueType>
{
    protected String propKey;
    protected Config config;
    protected BiFunction<Config, String, PropValueType> getter;
    protected Function<PropValueType, PropValueType> transformer = (value) -> value;

    public ConfigValueRetrieverBuilder<PropValueType> property(String propKey)
    {
        if (this.propKey != null)
        {
            throw new RuntimeException("Property already set");
        }
        this.propKey = propKey;

        return this;
    }

    public ConfigValueRetrieverBuilder<PropValueType> fromConfig(Config config)
    {
        if (this.config != null)
        {
            throw new RuntimeException("Config already set");
        }
        this.config = config;

        return this;
    }

    public ConfigValueRetrieverBuilder<PropValueType> usingGetter(BiFunction<Config, String, PropValueType> getter)
    {
        // We allow the retrievers to have their own custom getter, which will be used in place of a
        // general getter, assigned later
        if (this.getter != null)
        {
            return this;
        }
        this.getter = getter;

        return this;
    }

    public ConfigValueRetrieverBuilder<PropValueType> withTransformation(Function<PropValueType, PropValueType> transformer)
    {
        this.transformer = transformer;

        return this;
    }

    public ConfigValueRetriever<PropValueType> build()
    {
        if (this.config == null || this.getter == null || this.propKey == null)
        {
            throw new RuntimeException("Missing required fields");
        }

        if (transformer != null)
        {
            return new TransformingConfigValueRetriever<>(
                    config,
                    propKey,
                    getter,
                    transformer
            );
        }
        return new SimpleConfigValueRetriever<>(config, propKey, getter);
    }
}
