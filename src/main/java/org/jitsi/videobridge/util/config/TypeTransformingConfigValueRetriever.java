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

import java.util.function.*;

/**
 * Unlike a {@link TransformingConfigValueRetriever}, which transforms
 * the value but doesn't change its type, this retriever can retrieve
 * a property as one type from the config, but model it as another value.
 */
public class TypeTransformingConfigValueRetriever<ConfigValueType, ActualValueType>
        implements ConfigValueRetriever<ActualValueType>
{
    /**
     * We can't derive from {@link SimpleConfigValueRetriever} because it
     * requires the getter return type to be the same as the value type,
     * but here we'll retrieve it as one type and then convert it to another--but
     * we can leverage the logic in the simple retriever to get the config type
     * and then transform the type here.
     */
    protected final SimpleConfigValueRetriever<ConfigValueType> retriever;
    protected final Function<ConfigValueType, ActualValueType> configValueTransformer;

    protected TypeTransformingConfigValueRetriever(
            Config config,
            String propKey,
            BiFunction<Config, String, ConfigValueType> getter,
            Function<ConfigValueType, ActualValueType> transformer)
    {
        retriever = new SimpleConfigValueRetriever<>(config, propKey, getter);
        this.configValueTransformer = transformer;
    }

    @Override
    public ActualValueType getValue()
    {
        ConfigValueType configValue = retriever.getValue();
        return configValueTransformer.apply(configValue);
    }

    public static class Builder<ConfigValueType, ActualValueType>
    {
        protected Config config;
        protected String propKey;
        protected BiFunction<Config, String, ConfigValueType> getter;
        protected Function<ConfigValueType, ActualValueType> transformer;

        //TODO: validation

        public TypeTransformingConfigValueRetriever.Builder<ConfigValueType, ActualValueType> fromConfig(Config config)
        {
            this.config = config;

            return this;
        }

        public TypeTransformingConfigValueRetriever.Builder<ConfigValueType, ActualValueType> property(String propKey)
        {
            this.propKey = propKey;

            return this;
        }

        public TypeTransformingConfigValueRetriever.Builder<ConfigValueType, ActualValueType> usingGetter(BiFunction<Config, String, ConfigValueType> getter)
        {
            this.getter = getter;

            return this;
        }

        public TypeTransformingConfigValueRetriever.Builder<ConfigValueType, ActualValueType> withTransformer(Function<ConfigValueType, ActualValueType> transformer)
        {
            this.transformer = transformer;

            return this;
        }

        public TypeTransformingConfigValueRetriever<ConfigValueType, ActualValueType> build()
        {
            return new TypeTransformingConfigValueRetriever<>(config, propKey, getter, transformer);
        }
    }
}
