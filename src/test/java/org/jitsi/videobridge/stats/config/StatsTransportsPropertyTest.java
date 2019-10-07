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

package org.jitsi.videobridge.stats.config;

import com.typesafe.config.*;
import org.jitsi.testutils.*;
import org.jitsi.videobridge.stats.*;
import org.junit.*;

import java.time.*;

import static org.junit.Assert.*;

public class StatsTransportsPropertyTest
{
    @Test
    public void whenOnlyOldConfigIsPresentSimple()
    {
        Config legacyConfig = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("old-config-simple");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNoNewConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(3, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof MucStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof CallStatsIOTransport));
    }

    @Test
    public void whenOnlyOldConfigIsPresentWithPubSub()
    {
        Config legacyConfig = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("old-config-with-pubsub");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNoNewConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(2, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof PubSubStatsTransport));
        //TODO: verify the service and node names
    }

    @Test
    public void whenOnlyOldConfigIsPresentCustomInterval()
    {
        Config legacyConfig =
            ConfigFactory.parseResources("stats-transports-property.conf")
                .getConfig("old-config-with-custom-interval");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNoNewConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(3, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof MucStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof CallStatsIOTransport));

        assertEquals(Duration.ofSeconds(5), statsTransports.get().stream().filter(t -> t instanceof ColibriStatsTransport).findFirst().get().getInterval());
        assertNull(statsTransports.get().stream().filter(t -> t instanceof MucStatsTransport).findFirst().get().getInterval());
        assertNull(statsTransports.get().stream().filter(t -> t instanceof CallStatsIOTransport).findFirst().get().getInterval());
    }

    @Test
    public void whenOnlyNewConfigIsPresentSimple()
    {
        Config config = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("new-config-simple");
        new ConfigSetup()
            .withNewConfig(config)
            .withNoLegacyConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(2, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof CallStatsIOTransport));
    }

    @Test
    public void whenOnlyNewConfigIsPresentWithPubSub()
    {
        Config config = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("new-config-with-pubsub");
        new ConfigSetup()
            .withNewConfig(config)
            .withNoLegacyConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(2, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof PubSubStatsTransport));
        //TODO: verify the service and node names
    }

    @Test
    public void whenOnlyNewConfigIsPresentCustomInterval()
    {
        Config config = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("new-config-custom-interval");
        new ConfigSetup()
            .withNewConfig(config)
            .withNoLegacyConfig()
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        assertEquals(2, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof CallStatsIOTransport));

        assertEquals(Duration.ofSeconds(7), statsTransports.get().stream().filter(t -> t instanceof CallStatsIOTransport).findFirst().get().getInterval());
        assertNull(statsTransports.get().stream().filter(t -> t instanceof ColibriStatsTransport).findFirst().get().getInterval());
    }

    @Test
    public void whenBothConfigsArePresentSimple()
    {
        Config legacyConfig = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("old-config-simple");
        Config config = ConfigFactory.parseResources("stats-transports-property.conf").getConfig("new-config-simple");
        new ConfigSetup()
            .withLegacyConfig(legacyConfig)
            .withNewConfig(config)
            .finishSetup();

        StatsTransportsProperty statsTransports = new StatsTransportsProperty();
        // We should take the values from the old config if its present
        assertEquals(3, statsTransports.get().size());
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof ColibriStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof MucStatsTransport));
        assertTrue(statsTransports.get().stream().anyMatch(t -> t instanceof CallStatsIOTransport));
    }
}