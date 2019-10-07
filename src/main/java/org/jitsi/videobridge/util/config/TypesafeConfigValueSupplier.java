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
import org.jitsi.utils.config.*;

import java.util.function.*;

/**
 * When retrieving configuration values, {@link ConfigPropertyImpl} will iterate
 * through each of the suppliers and stop at at the first one which does not
 * throw {@link ConfigPropertyNotFoundException}.  The typesafe.config library
 * doesn't throw that exception (since that's one we define), so we have to
 * translate its {@link ConfigException.Missing} to our {@link ConfigPropertyNotFoundException},
 * which is what this class does.
 *
 * @param <T> the configuration value type
 */
public class TypesafeConfigValueSupplier<T> implements Supplier<T>
{
    protected final Supplier<T> supplier;

    public TypesafeConfigValueSupplier(Supplier<T> supplier)
    {
        this.supplier = supplier;
    }

    @Override
    public T get()
    {
        try
        {
            return supplier.get();
        }
        catch (ConfigException.Missing ex)
        {
            throw new ConfigPropertyNotFoundException(ex.getMessage());
        }
    }
}
