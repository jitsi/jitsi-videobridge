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

import org.jitsi.cmd.*;
import org.jitsi.utils.config.*;

import java.util.function.*;

/**
 * A supplier which reads from the parsed command-line arguments.
 *
 * @param <T> the type of the configuration property's value
 */
public class CommandLineConfigValueSupplier<T> implements Supplier<T>
{
    protected final String argName;
    protected final Function<CmdLine, T> getter;

    /**
     * This method is only capable returning strings, so we'll rely on the fact
     * that T has been properly set to String and perform an unchecked cast
     *
     * @param argName the name of the command-line argument
     */
    @SuppressWarnings("unchecked")
    public CommandLineConfigValueSupplier(String argName)
    {
        this(argName, cmdLine -> (T)cmdLine.getOptionValue(argName, null));
    }

    public CommandLineConfigValueSupplier(String argName, Function<CmdLine, T> getter)
    {
        this.argName = argName;
        this.getter = getter;
    }

    @Override
    public T get()
    {
        String[] commandLineArgs = JvbConfig.getCommandLineArgs();
        CmdLine cmdLine = new CmdLine();
        try
        {
            cmdLine.parse(commandLineArgs);
            T value = getter.apply(cmdLine);
            if (value == null)
            {
                throw new ConfigPropertyNotFoundException(argName);
            }
            return value;
        }
        catch (ParseException ignored) { }

        throw new ConfigPropertyNotFoundException(argName);
    }
}
