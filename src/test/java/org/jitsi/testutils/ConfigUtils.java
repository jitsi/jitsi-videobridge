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
import org.jitsi.videobridge.util.config.*;

public class ConfigUtils
{
    public static Config EMPTY_CONFIG = ConfigFactory.parseString("");
    public static Config EMPTY_NEW_CONFIG = ConfigFactory.parseString("videobridge {}");

    public static void resetConfigSuppliers()
    {
        ConfigSupplierSettings.configSupplier = ConfigSupplierSettings.DEFAULT_NEW_CONFIG_SUPPLIER;
        ConfigSupplierSettings.legacyConfigSupplier = ConfigSupplierSettings.DEFAULT_LEGACY_CONFIG_SUPPLIER;
        ConfigSupplierSettings.commandLineArgsSupplier = ConfigSupplierSettings.DEFAULT_COMMAND_LINE_ARGS_SUPPLIER;
    }

}
