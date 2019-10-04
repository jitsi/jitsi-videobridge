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

import org.jitsi.videobridge.util.config.retriever.*;

import java.util.*;

/**
 * A property whose value is re-read from the underlying config
 * every time {@link ConfigProperty#get()} is called
 * @param <T> the value type of the config property
 */
public class ReadEveryTimeProperty<T> extends ConfigPropertyImpl<T>
{
    public ReadEveryTimeProperty(List<ConfigValueRetriever<T>> configValueRetrievers, T defaultValue)
    {
        super(configValueRetrievers, defaultValue);
    }

    @Override
    public T get()
    {
        return doGet();
    }
}
