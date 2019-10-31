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

package org.jitsi.testutils;

import com.typesafe.config.*;
import org.jitsi.utils.config.*;

import java.util.function.*;

import static org.junit.Assert.assertEquals;

public class ConfigPropertyTest<T extends ConfigProperty, U>
{
    public void runBasicTests(
        String legacyPropName,
        ParamResult<U> legacyParamResult,
        String newPropName,
        ParamResult<U> newParamResult,
        Supplier<T> propInstanceSupplier
    )
    {
        // With the provided arguments, we test each of the following scenarios:
        // Old config is present, but doesn't provide a value
        // Old config isn't present
        // Old config is present and provides a value
        // NOTE: it's assumed that a new config file (in the form of the defaults)
        // will always be present, as it is bundled with the JVB itself

        Config dummyLegacyConfig =
            ConfigFactory.parseString("some.other.property.name=42");
        Config legacyConfig =
            ConfigFactory.parseString(legacyPropName + "=" + legacyParamResult.configFileValue);
        Config newConfig =
            ConfigFactory.parseString(newPropName + "=" + newParamResult.configFileValue);

        // Old config is present, but doesn't provide a value
        new ConfigSetup()
            .withLegacyConfig(dummyLegacyConfig)
            .withNewConfig(newConfig)
            .finishSetup();

        T configProperty = propInstanceSupplier.get();
        assertEquals("Old config is present, but doesn't provide a value",
            newParamResult.parsedValue, configProperty.get());

        // Old config isn't present
        new ConfigSetup()
            .withNewConfig(newConfig)
            .finishSetup();

        configProperty = propInstanceSupplier.get();
        assertEquals("No old config is present",
            newParamResult.parsedValue, configProperty.get());

        // Old config is present and provides a value
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(newConfig)
            .finishSetup();

        configProperty = propInstanceSupplier.get();
        assertEquals("Old config is present and provides a value",
            legacyParamResult.parsedValue, configProperty.get());
    }

   public void runReadOnceTest(
        String newPropName,
        ParamResult<U> firstValue,
        ParamResult<U> secondValue,
        Supplier<T> propInstanceSupplier
    )
    {
        Config newConfig = ConfigFactory.parseString(newPropName + "=" + firstValue.configFileValue);
        new ConfigSetup()
            .withNewConfig(newConfig)
            .finishSetup();

        T configProperty = propInstanceSupplier.get();

        Config changedConfig = ConfigFactory.parseString(newPropName + "=" + secondValue.configFileValue);
        new ConfigSetup()
            .withNewConfig(changedConfig)
            .finishSetup();

        assertEquals("Read once: Property value should still be the original value",
            firstValue.parsedValue, configProperty.get());
    }

    /**
     * ParamResult models the combination of a configuration's value as it is
     * written in a config value (always a String) and the expected result of
     * when it is parsed (the generic type T), as these often differ
     * @param <T> the type of the parsed result.
     */
    public static class ParamResult<T>
    {
        // The value for a config parameter as it would be written in the config file
        String configFileValue;
        // The value as it will be parsed by the configuration property class.  This
        // doesn't always match configParamValue above; for example a value might
        // be written as a Duration but parsed as a Long
        T parsedValue;
        public ParamResult(String configFileValue, T parsedValue)
        {
            this.configFileValue = configFileValue;
            this.parsedValue = parsedValue;
        }
    }
}
