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
import org.jitsi.videobridge.util.config.retriever.*;

import java.util.function.*;

/**
 * Retrieves the value of a given property key from the given config, using
 * the provided 'getter' (which allows retrieving the value as the type
 * specified by `PropValueType).  Since a configuration property
 * may use different keys for different configs, we contain all the information
 * needed in a retriever such that it can be set up in one place and used
 * to retrieve the value when needed from another, which doesn't have to
 * worry about the underlying details.
 * @param <PropValueType> the type the configuration property's value will
 *                       be parsed as.
 */
public class SimpleConfigValueRetriever<PropValueType> implements ConfigValueRetriever<PropValueType>
{
//    protected final Config config;
    protected final String propKey;
    protected Function<String, PropValueType> getter;

    public SimpleConfigValueRetriever(
//            Config config,
            String propKey,
            Function<String, PropValueType> getter)
    {
        this.propKey = propKey;
//        this.config = config;
        this.getter = getter;
    }

    @Override
    public PropValueType getValue()
    {
        return getter.apply(propKey);
    }
}
