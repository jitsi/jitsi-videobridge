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

public class ConfigPropertyBuilder<T>
{
    protected BiFunction<Config, String, T> getter = null;
    protected T defaultValue = null;
    protected List<ConfigInfo> configInfos = null;
    protected boolean readOnce = false;

    public ConfigPropertyBuilder<T> withGetter(BiFunction<Config, String, T> getter)
    {
        if (this.getter != null)
        {
            throw new RuntimeException("Getter already set");
        }
        this.getter = getter;

        return this;
    }

    public ConfigPropertyBuilder<T> withConfigs(ConfigInfo... configInfos)
    {
        if (this.configInfos != null)
        {
            throw new RuntimeException("Configs already set");
        }
        this.configInfos = Arrays.asList(configInfos);

        return this;
    }

    public ConfigPropertyBuilder<T> withDefault(T defaultValue)
    {
        if (this.defaultValue != null)
        {
            throw new RuntimeException("Default value already set");
        }
        this.defaultValue = defaultValue;

        return this;
    }

    public ConfigPropertyBuilder<T> readOnce()
    {
        readOnce = true;

        return this;
    }

    public ConfigProperty<T> build()
    {
        if (readOnce)
        {
            return new ReadOnceProperty<T>(configInfos, getter, defaultValue);
        }
        return new ReadEveryTimeProperty<T>(configInfos, getter, defaultValue);
    }

    public static class ConfigInfo
    {
        protected final Config config;
        protected final String propKey;

        public ConfigInfo(Config config, String propKey)
        {
            this.config = config;
            this.propKey = propKey;
        }
    }
}
