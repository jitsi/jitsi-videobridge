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

import java.nio.file.*;
import java.util.*;
import java.util.function.*;

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

    /**
     * Requiring explicit initialization of the config makes it more obvious
     * when the config is being read (instead of on first access) and makes testing
     * more straightforward (as we can ensure we re-initialize after setting up
     * a new dummy configuration)
     */
    public static void init()
    {
        config = configSupplier.get();
        legacyConfig = legacyConfigSupplier.get();
        commandLineArgs = commandLineArgsSupplier.get();

        logger.info("Loaded complete config: " + config.withFallback(legacyConfig).root().render());
        if (config.hasPath("videobridge"))
        {
            logger.info("Loaded JVB config: " + config.getConfig("videobridge").root().render());
        }
        else
        {
            logger.info("No new config found");
        }
        logger.info("Have command line args: '" + Arrays.toString(commandLineArgs) + "'");
   }

    public static void reloadConfig()
    {
        logger.info("Reloading config");
        ConfigFactory.invalidateCaches();
        init();
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
