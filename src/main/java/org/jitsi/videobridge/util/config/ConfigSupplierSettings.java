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
import org.jitsi.utils.logging2.*;

import java.nio.file.*;
import java.util.function.*;

public class ConfigSupplierSettings
{
    protected static Logger logger = new LoggerImpl(ConfigSupplierSettings.class.getName());
    /**
     * The supplier to load the new config.  Overridable for testing
     */
    public static Supplier<Config> configSupplier = ConfigFactory::load;

    /**
     * The supplier to load the legacy config.  Overridable for testing
     */
    public static Supplier<Config> legacyConfigSupplier = () -> {
        String oldConfigHomeDirLocation = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_LOCATION");
        String oldConfigHomeDirName = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_NAME");
        try
        {
            Config config = ConfigFactory.parseFile(
                Paths.get(oldConfigHomeDirLocation, oldConfigHomeDirName, "sip-communicator.properties")
                    .toFile());
            logger.info("Found a legacy config file: \n" + config.root().render());
            return config;
        }
        catch (InvalidPathException | NullPointerException e)
        {
            logger.info("No legacy config file found");
            return ConfigFactory.parseString("");
        }
    };

    /**
     * The supplier to load the command-line arguments.  Overridable for testing
     */
    public static Supplier<String[]> commandLineArgsSupplier = () -> new String[0];
}
