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
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;

import java.util.*;

import static org.jitsi.videobridge.util.config.ConfigSupplierSettings.*;

/**
 * Helper class for retrieving configuration sources.  Currently, this supports reading config from
 * 1) Command line arguments
 * 2) The sip-communicator.properties file (requires defining the
 * net.java.sip.communicator.SC_HOME_DIR_LOCATION and net.java.sip.communicator.SC_HOME_DIR_NAME system properties. It
 * is expected that <net.java.sip.communicator.SC_HOME_DIR_LOCATION>/<net.java.sip.communicator.SC_HOME_DIR_NAME>/sip-communicator.properties
 * will exist.
 * 3) A new config file (reference.conf and/or application.conf in the classpath, or -Dconfig.file system property)
 */
public class JvbConfig
{
    protected static Logger logger = new LoggerImpl(JvbConfig.class.getName());

    protected static Config config;
    protected static Config legacyConfig;
    protected static String[] commandLineArgs;

    static {
        loadConfig();
    }

    public static void loadConfig()
    {
        legacyConfig = legacyConfigSupplier.get();
        commandLineArgs = commandLineArgsSupplier.get();

        config = configSupplier.get();
        if (config.hasPath("videobridge"))
        {
            logger.info("Loaded JVB config: " + config.getConfig("videobridge").root().render());
        }
        else
        {
            throw new RuntimeException("Videobridge config block missing!");
        }

        logger.info("Loaded complete config: " + config.withFallback(legacyConfig).root().render());
        logger.info("Have command line args: '" + Arrays.toString(commandLineArgs) + "'");
   }

    public static void reloadConfig()
    {
        logger.info("Reloading config");
        ConfigFactory.invalidateCaches();
        loadConfig();
    }

    public static @NotNull Config getConfig()
    {
        return config;
    }

    public static Config getLegacyConfig()
    {
        return legacyConfig;
    }

    public static String[] getCommandLineArgs()
    {
        return commandLineArgs;
    }
}
