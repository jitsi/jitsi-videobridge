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

public class EndpointConnectionStatusTest
{
    @Test
    public void testFirstTransferTimePropertyConfig()
    {
        ConfigPropertyTest<EndpointConnectionStatus.FirstTransferTimeoutProperty, Long>
            configPropertyTest = new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            EndpointConnectionStatus.FirstTransferTimeoutProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("5000", 5000L),
            EndpointConnectionStatus.FirstTransferTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000L),
            EndpointConnectionStatus.FirstTransferTimeoutProperty::new
        );

        configPropertyTest.runReadOnceTest(
            EndpointConnectionStatus.FirstTransferTimeoutProperty.propName,
            new ConfigPropertyTest.ParamResult<>("5 seconds", 5000L),
            new ConfigPropertyTest.ParamResult<>("15 seconds", 15000L),
            EndpointConnectionStatus.FirstTransferTimeoutProperty::new
        );
    }

    @Test
    public void testMaxInactivityLimitProperty()
    {
        ConfigPropertyTest<EndpointConnectionStatus.MaxInactivityLimitProperty, Long>
            configPropertyTest = new ConfigPropertyTest<>();

        configPropertyTest.runBasicTests(
            EndpointConnectionStatus.MaxInactivityLimitProperty.legacyPropName,
            new ConfigPropertyTest.ParamResult<>("5000", 5000L),
            EndpointConnectionStatus.MaxInactivityLimitProperty.propName,
            new ConfigPropertyTest.ParamResult<>("10 seconds", 10000L),
            EndpointConnectionStatus.MaxInactivityLimitProperty::new
        );

        configPropertyTest.runReadOnceTest(
            EndpointConnectionStatus.MaxInactivityLimitProperty.propName,
            new ConfigPropertyTest.ParamResult<>("5 seconds", 5000L),
            new ConfigPropertyTest.ParamResult<>("15 seconds", 15000L),
            EndpointConnectionStatus.MaxInactivityLimitProperty::new
        );
    }
}