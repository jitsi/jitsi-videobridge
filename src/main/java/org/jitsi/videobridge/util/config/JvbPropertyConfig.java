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

import java.util.function.*;

public class JvbPropertyConfig<T> extends PropertyConfig<T>
{
    public JvbPropertyConfig<T> fromLegacyConfig(Function<Config, T> getter)
    {
        suppliedBy(new LegacyConfigValueSupplier<>(getter));

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

    public JvbPropertyConfig<T> fromNewConfig(Function<Config, T> getter)
    {
        suppliedBy(new ConfigValueSupplier<>(getter));

        return this;
    }
}
