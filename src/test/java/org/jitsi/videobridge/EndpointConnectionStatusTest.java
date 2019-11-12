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

package org.jitsi.videobridge;

import org.jitsi.testutils.*;
import org.junit.*;

import java.time.*;

public class EndpointConnectionStatusTest
{
    @Test
    public void testFirstTransferTimePropertyConfig()
    {
        ConfigPropertyTest<EndpointConnectionStatus.Config.FirstTransferTimeoutProperty, Duration>
            configPropertyTest = new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            EndpointConnectionStatus.Config.FirstTransferTimeoutProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("5000", Duration.ofSeconds(5)),
            EndpointConnectionStatus.Config.FirstTransferTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", Duration.ofSeconds(10)),
            EndpointConnectionStatus.Config.FirstTransferTimeoutProperty::new
        );

        configPropertyTest.runReadOnceTest(
            EndpointConnectionStatus.Config.FirstTransferTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("5 seconds", Duration.ofSeconds(5)),
            new ConfigPropertyTest.ParamResult<>("15 seconds", Duration.ofSeconds(15)),
            EndpointConnectionStatus.Config.FirstTransferTimeoutProperty::new
        );
    }

    @Test
    public void testMaxInactivityLimitProperty()
    {
        ConfigPropertyTest<EndpointConnectionStatus.Config.MaxInactivityLimitProperty, Duration>
            configPropertyTest = new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            EndpointConnectionStatus.Config.MaxInactivityLimitProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("5000", Duration.ofSeconds(5)),
            EndpointConnectionStatus.Config.MaxInactivityLimitProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", Duration.ofSeconds(10)),
            EndpointConnectionStatus.Config.MaxInactivityLimitProperty::new
        );

        configPropertyTest.runReadOnceTest(
            EndpointConnectionStatus.Config.MaxInactivityLimitProperty.propName,
            new ConfigPropertyTest.ParamResult<>("5 seconds", Duration.ofSeconds(5)),
            new ConfigPropertyTest.ParamResult<>("15 seconds", Duration.ofSeconds(15)),
            EndpointConnectionStatus.Config.MaxInactivityLimitProperty::new
        );
    }
}