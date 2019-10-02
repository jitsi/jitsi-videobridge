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

package org.jitsi.videobridge.util;

import com.typesafe.config.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;

import java.nio.file.*;

public class JvbConfig
{
    protected static Config config = ConfigFactory.load();
    protected static Logger logger = new LoggerImpl(JvbConfig.class.getName());
    protected static Config legacyConfig;

    static
    {
        String oldConfigHomeDirLocation = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_LOCATION");
        String oldConfigHomeDirName = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_NAME");
        try
        {
            legacyConfig = ConfigFactory.parseFile(
                    Paths.get(oldConfigHomeDirLocation, oldConfigHomeDirName, "sip-communicator.properties")
                            .toFile());
        }
        catch (InvalidPathException | NullPointerException e)
        {
            logger.info("No legacy config file found");
            legacyConfig = ConfigFactory.parseString("");
        }
        logger.info("Loaded complete config: " + config.withFallback(legacyConfig).root().render());
        logger.info("Loaded JVB config: " + config.getConfig("videobridge").root().render());
    }

    public static @NotNull Config getConfig()
    {
        return config;
    }

    public static Config getLegacyConfig()
    {
        return legacyConfig;
    }
}
