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

public class HealthIntervalPropertyTest
{
    @Test
    public void test()
    {
        ConfigPropertyTest<HealthConfig.HealthIntervalProperty, Integer> configPropertyTest =
            new ConfigPropertyTest<>();
        configPropertyTest.runBasicTests(
            HealthConfig.HealthIntervalProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<Integer>("60000", 60000),
            HealthConfig.HealthIntervalProperty.propName,
            new ConfigPropertyTest.ParamResult<Integer>("10 seconds", 10000),
            HealthConfig.HealthIntervalProperty::new
        );

        configPropertyTest.runReadOnceTest(
            HealthConfig.HealthIntervalProperty.propName,
            new ConfigPropertyTest.ParamResult<Integer>("10 seconds", 10000),
            new ConfigPropertyTest.ParamResult<Integer>("30 seconds", 30000),
            HealthConfig.HealthIntervalProperty::new
        );
    }
}