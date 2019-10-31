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

import org.jitsi.testutils.*;
import org.junit.*;

import static org.junit.Assert.*;

public class HealthConfigTest
{
    @Test
    public void testHealthIntervalProperty()
    {
        ConfigPropertyTest<HealthConfig.HealthIntervalProperty, Integer> configPropertyTest =
            new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            HealthConfig.HealthIntervalProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("60000", 60000),
            HealthConfig.HealthIntervalProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000),
            HealthConfig.HealthIntervalProperty::new
        );

        configPropertyTest.runReadOnceTest(
            HealthConfig.HealthIntervalProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000),
            new ConfigPropertyTest.ParamResult<>("30 seconds", 30000),
            HealthConfig.HealthIntervalProperty::new
        );
    }

    @Test
    public void testHealthTimeoutProperty()
    {
        ConfigPropertyTest<HealthConfig.HealthTimeoutProperty, Integer> configPropertyTest =
            new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            HealthConfig.HealthTimeoutProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("60000", 60000),
            HealthConfig.HealthTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000),
            HealthConfig.HealthTimeoutProperty::new
        );

        configPropertyTest.runReadOnceTest(
            HealthConfig.HealthTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000),
            new ConfigPropertyTest.ParamResult<>("30 seconds", 30000),
            HealthConfig.HealthTimeoutProperty::new
        );
    }

    @Test
    public void testStickyFailuresProperty()
    {
        ConfigPropertyTest<HealthConfig.StickyFailuresProperty, Boolean> configPropertyTest =
            new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            HealthConfig.StickyFailuresProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("true", true),
            HealthConfig.StickyFailuresProperty.propName,
            new ConfigPropertyTest.ParamResult<>("false", false),
            HealthConfig.StickyFailuresProperty::new
        );

        configPropertyTest.runReadOnceTest(
            HealthConfig.StickyFailuresProperty.propName,
            new ConfigPropertyTest.ParamResult<>("true", true),
            new ConfigPropertyTest.ParamResult<>("false", false),
            HealthConfig.StickyFailuresProperty::new
        );
    }
}