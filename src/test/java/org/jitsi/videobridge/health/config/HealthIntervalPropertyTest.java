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

package org.jitsi.videobridge.health.config;

import com.typesafe.config.*;
import org.jitsi.testutils.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.health.config.*;
import org.junit.*;

import static org.junit.Assert.*;

public class HealthIntervalPropertyTest
{
    private Config emptyNewConfig = ConfigFactory.parseString("videobridge {}");
    @Test
    public void whenOnlyOldConfigProvidesAValue()
    {
        Config legacyConfig = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.legacyPropName + "=60000");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(emptyNewConfig)
            .finishSetup();

        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();
        assertEquals(60000, (int)healthIntervalProperty.get());
    }

    @Test
    public void whenOldConfigIsPresentButDoesntSetTheValue()
    {
        Config legacyConfig = ConfigFactory.parseString("some.other.property.name=10");
        Config config = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.propName + "=10 seconds");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(config)
            .finishSetup();

        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();
        assertEquals("The default value should be used", 10000, (int)healthIntervalProperty.get());
    }

    @Test
    public void whenOnlyNewConfigProvidesAValue()
    {
        Config config = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.propName + "=30 seconds");
        new ConfigSetup()
            .withNewConfig(config)
            .finishSetup();

        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();
        assertEquals(30000, (int)healthIntervalProperty.get());
    }

    @Test
    public void whenOldConfigAndNewConfigProvideAValue()
    {
        Config legacyConfig = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.legacyPropName + "=60000");
        Config config = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.propName + "=10 seconds");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(config)
            .finishSetup();

        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();
        assertEquals("The value in legacy config should be used", 60000, (int)healthIntervalProperty.get());
    }

    @Test(expected = ConfigPropertyNotFoundException.class)
    public void whenNoConfigProvidesTheValue()
    {
        new ConfigSetup().withNewConfig(emptyNewConfig).finishSetup();
        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();
        healthIntervalProperty.get();
    }

    @Test
    public void doesNotChangeWhenConfigIsReloaded()
    {
        Config legacyConfig = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.legacyPropName + "=60000");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(emptyNewConfig)
            .finishSetup();

        HealthConfig.HealthIntervalProperty healthIntervalProperty = new HealthConfig.HealthIntervalProperty();

        Config changedConfig = ConfigFactory.parseString(HealthConfig.HealthIntervalProperty.legacyPropName + "=90000");
        new ConfigSetup()
            .withLegacyConfig(changedConfig)
            .withNewConfig(emptyNewConfig)
            .finishSetup();

        assertEquals(60000, (int)healthIntervalProperty.get());
    }
}