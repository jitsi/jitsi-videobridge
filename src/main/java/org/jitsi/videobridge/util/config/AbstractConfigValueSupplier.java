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
import org.jitsi.videobridge.util.*;

import java.util.function.*;

public class AbstractConfigValueSupplier<T> implements Supplier<T>
{
    protected final Supplier<T> supplier;

    /**
     * This constructor is suitable to use for the simple case, where
     * the caller merely wants to retrieve a prop named {@code propName}
     * as type {@code propValueType}
     *
     * @param propValueType
     * @param propName
     */
    public AbstractConfigValueSupplier(Config config, Class<T> propValueType, String propName)
    {
        this.supplier =
            new TypesafeConfigValueSupplier<>(config, propValueType, propName);
    }

    /**
     * This constructor is for cases which require custom logic (like performing
     * some transformation on the retrieved type)
     *
     * NOTE: I considered letting the subclass just pass in a {@code Supplier<T>} which
     * would give even more flexibility, but that means a call to getConfig would
     * likely end up in the supplier, making the time at which it's retrieved
     * inconsistent with the other constructor, so I decided to explicitly
     * require the config and a {@code Function<Config, T>} instead
     *
     * @param customGetter
     */
    public AbstractConfigValueSupplier(Config config, Function<Config, T> customGetter)
    {
        this.supplier = new TypesafeConfigValueSupplier<>(() -> customGetter.apply(config));
    }

    /**
     * Get the value of the configuration property for which
     * this supplier was created
     *
     * @return the configuration property's value, or
     * {@link org.jitsi.utils.config.ConfigPropertyNotFoundException} if
     * the property was not found
     */
    @Override
    public T get()
    {
        return supplier.get();
    }
}
