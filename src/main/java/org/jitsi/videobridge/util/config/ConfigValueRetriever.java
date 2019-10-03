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
 * Responsible for retrieving the value of a given property key
 * from a given config and performing any transformations on the retrieved
 * value.  It's necessary to have both the config and key held together here,
 * as the same property may use different keys in different configs (e.g.
 * a legacy key name in a legacy config and a new one in a newer config).
 *
 * @param <PropValueType>
 */
public class ConfigValueRetriever<PropValueType>
{
    protected final Config config;
    protected final String propKey;
    protected BiFunction<Config, String, PropValueType> getter;
    protected final Function<PropValueType, PropValueType> configValueTransformer;

    protected ConfigValueRetriever(
            Config config,
            String propKey,
            BiFunction<Config, String, PropValueType> getter,
            Function<PropValueType, PropValueType> transformer)
    {
        this.propKey = propKey;
        this.config = config;
        this.getter = getter;
        this.configValueTransformer = transformer;
    }

    public PropValueType getValue()
    {
        PropValueType value = getter.apply(config, propKey);
        return configValueTransformer.apply(value);
    }
}
