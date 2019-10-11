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
import org.jitsi.cmd.*;
import org.jitsi.utils.config.*;

import java.util.*;
import java.util.function.*;

public class JvbPropertyConfig<T> extends PropertyConfig<T>
{
    protected final Class<T> valueClassType;

    public JvbPropertyConfig(Class<T> valueClassType)
    {
        this.valueClassType = valueClassType;
    }

    /**
     * NOTE(brian): for complex types (such as {@code List<MyClass>}), it's
     * awkward to pass the {@link Class<T>} class type, and since those types
     * likely will require custom getter logic anyway, we have a constructor
     * which allows not passing the type in, but we'll throw if
     * one of the simple {@code fromXXX} methods is called without
     * having passed in a {@code valueClassType}
     */
    public JvbPropertyConfig()
    {
        this.valueClassType = null;
    }

    public JvbPropertyConfig<T> fromLegacyConfig(String propName)
    {
        Objects.requireNonNull(this.valueClassType);
        suppliedBy(new LegacyConfigValueSupplier<>(valueClassType, propName));

        return this;
    }

    public JvbPropertyConfig<T> fromLegacyConfig(Function<Config, T> getter)
    {
        suppliedBy(new LegacyConfigValueSupplier<>(getter));

        return this;
    }

    public JvbPropertyConfig<T> fromNewConfig(String propName)
    {
        Objects.requireNonNull(this.valueClassType);
        suppliedBy(new ConfigValueSupplier<>(valueClassType, propName));

        return this;
    }

    public JvbPropertyConfig<T> fromNewConfig(Function<Config, T> getter)
    {
        suppliedBy(new ConfigValueSupplier<>(getter));

        return this;
    }


    public JvbPropertyConfig<T> fromCommandLine(String argName)
    {
        suppliedBy(new CommandLineConfigValueSupplier<>(argName));

        return this;
    }

    public JvbPropertyConfig<T> fromCommandLine(String argName, Function<CmdLine, T> getter)
    {
        suppliedBy(new CommandLineConfigValueSupplier<>(argName,  getter));

        return this;
    }

}
